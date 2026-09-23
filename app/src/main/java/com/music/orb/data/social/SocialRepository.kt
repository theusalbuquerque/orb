package com.music.orb.data.social

import com.music.orb.data.Http
import com.music.orb.data.model.Song
import com.music.orb.data.stats.TrackLanguageResolver
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Database boundary for Orb's social features.
 *
 * UI/playback code never supplies a trusted identity: every write derives the
 * user UUID from the active Supabase session, and the database independently
 * enforces the same rule with RLS/auth.uid().
 */
object SocialRepository {
    private val supabase get() = OrbSupabase.client

    /**
     * Process-wide snapshot of the signed-in Orb profile.
     *
     * The account avatar is rendered in several independent Compose surfaces
     * (Home/Explore/Library/Stats headers, account settings and the profile).
     * Keeping one StateFlow here means an edit is visible everywhere in the
     * same frame instead of waiting for every screen to refetch Supabase.
     */
    private val _myProfileState = MutableStateFlow<OrbProfile?>(null)
    val myProfileState: StateFlow<OrbProfile?> = _myProfileState.asStateFlow()

    fun currentUserId(): String? =
        if (OrbSupabase.configured) supabase.auth.currentUserOrNull()?.id else null

    fun clearMyProfileState() {
        _myProfileState.value = null
    }

    suspend fun myProfile(): OrbProfile? {
        val userId = currentUserId() ?: run {
            clearMyProfileState()
            return null
        }
        if (_myProfileState.value?.id != userId) {
            _myProfileState.value = null
        }
        return profile(userId)
    }

    suspend fun profile(userId: String): OrbProfile? {
        val resolved = supabase.from("profiles")
            .select {
                filter { eq("id", userId) }
            }
            .decodeList<OrbProfile>()
            .firstOrNull()
        if (userId == currentUserId()) {
            _myProfileState.value = resolved
        }
        return resolved
    }

    suspend fun profileByUsername(username: String): OrbProfile? {
        val normalized = normalizeUsername(username) ?: return null
        return supabase.from("profiles")
            .select {
                filter { eq("username", normalized) }
            }
            .decodeList<OrbProfile>()
            .firstOrNull()
    }

    /** Prefix search used by Friends. The current user is never returned. */
    suspend fun searchProfiles(
        query: String,
        limit: Int = 20,
    ): List<OrbProfile> {
        val prefix = normalizeUsernamePrefix(query) ?: return emptyList()
        if (prefix.isBlank()) return emptyList()

        val me = currentUserId() ?: return emptyList()
        val safeLimit = limit.coerceIn(1, 50)

        return supabase.from("profiles")
            .select {
                // A lexical prefix range keeps '.' and '_' literal instead of
                // treating '_' as a wildcard like SQL LIKE would.
                filter {
                    gte("username", prefix)
                    lt("username", prefix + "\uFFFF")
                }
                // Fetch one spare row so filtering ourselves does not shrink a
                // complete result page.
                range(0L..safeLimit.toLong())
            }
            .decodeList<OrbProfile>()
            .asSequence()
            .filter { it.id != me }
            .filter { it.username?.startsWith(prefix) == true }
            .take(safeLimit)
            .toList()
    }

    suspend fun followingIds(): List<String> {
        val me = currentUserId() ?: return emptyList()
        return followingIdsForUser(me)
    }

    suspend fun followingIdsForUser(userId: String): List<String> {
        if (userId.isBlank()) return emptyList()
        val visible = runCatching {
            supabase.from("profile_visible_following")
                .select {
                    filter { eq("follower_id", userId) }
                }
                .decodeList<FollowInsert>()
        }.getOrNull()
        val rows = visible ?: if (userId == currentUserId()) {
            // Migration-safe owner fallback. Never fall back to the raw graph for
            // somebody else's profile, because that could bypass their new
            // profile visibility choice on an older database schema.
            supabase.from("follows")
                .select { filter { eq("follower_id", userId) } }
                .decodeList<FollowInsert>()
        } else {
            emptyList()
        }
        return rows.map { it.followingId }.distinct()
    }

    suspend fun followingProfiles(): List<OrbProfile> {
        val me = currentUserId() ?: return emptyList()
        return followingProfilesForUser(me)
    }

    suspend fun followingProfilesForUser(userId: String): List<OrbProfile> {
        val ids = followingIdsForUser(userId)
        if (ids.isEmpty()) return emptyList()
        val profiles = profilesForIds(ids)
        val byId = profiles.associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    suspend fun followersProfiles(): List<OrbProfile> {
        val me = currentUserId() ?: return emptyList()
        return followersProfilesForUser(me)
    }

    suspend fun newFollowersCountSince(since: Instant): Int {
        val me = currentUserId() ?: return 0
        return supabase.from("follows")
            .select {
                filter {
                    eq("following_id", me)
                    gte("created_at", since.toString())
                }
            }
            .decodeList<FollowStatsRow>()
            .map { it.followerId }
            .distinct()
            .size
    }

    suspend fun recentFollowersForMe(since: Instant): List<FollowStatsRow> {
        val me = currentUserId() ?: return emptyList()
        return supabase.from("follows")
            .select {
                filter {
                    eq("following_id", me)
                    gte("created_at", since.toString())
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<FollowStatsRow>()
            .distinctBy { it.followerId to it.createdAt }
    }

    suspend fun followerIdsForUser(userId: String): List<String> {
        if (userId.isBlank()) return emptyList()
        val visible = runCatching {
            supabase.from("profile_visible_followers")
                .select {
                    filter { eq("following_id", userId) }
                }
                .decodeList<FollowInsert>()
        }.getOrNull()
        val rows = visible ?: if (userId == currentUserId()) {
            supabase.from("follows")
                .select { filter { eq("following_id", userId) } }
                .decodeList<FollowInsert>()
        } else {
            emptyList()
        }
        return rows.map { it.followerId }.distinct()
    }

    suspend fun followersProfilesForUser(userId: String): List<OrbProfile> {
        val ids = followerIdsForUser(userId)
        if (ids.isEmpty()) return emptyList()
        val profiles = profilesForIds(ids)
        val byId = profiles.associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    /**
     * Orb-owned ratings for the signed-in account. This remains independent
     * from YouTube Music so hearts survive with library sync disabled, across
     * process restarts, reinstalls and devices.
     */
    suspend fun mySongRatings(
        pageSize: Int = 1000,
        maxRows: Int = 20_000,
    ): List<OrbSongRating> {
        val userId = currentUserId() ?: return emptyList()
        val size = pageSize.coerceIn(100, 1000)
        val ceiling = maxRows.coerceIn(size, 20_000)
        val rows = ArrayList<OrbSongRating>(minOf(size, ceiling))
        var offset = 0L

        while (rows.size < ceiling) {
            val remaining = ceiling - rows.size
            val take = minOf(size, remaining)
            val page = supabase.from("song_ratings")
                .select {
                    filter { eq("user_id", userId) }
                    order("updated_at", Order.DESCENDING)
                    range(offset..(offset + take - 1L))
                }
                .decodeList<OrbSongRating>()
            rows += page
            if (page.size < take) break
            offset += page.size.toLong()
        }
        return rows
    }

    /** Immediate account-level persistence for one explicit heart/thumb action. */
    suspend fun setMySongRating(rating: OrbSongRatingMutation) {
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        require(rating.videoId.isNotBlank()) { "videoId is required" }
        supabase.from("song_ratings").upsert(
            OrbSongRatingUpsert(
                userId = userId,
                videoId = rating.videoId,
                recordingKey = rating.recordingKey,
                status = rating.status,
                title = rating.title,
                artist = rating.artist,
                album = rating.album,
                durationText = rating.durationText,
                updatedAt = rating.updatedAt,
            ),
        )
    }

    /** Bulk path used only to migrate/reconcile pre-Supabase local hearts. */
    suspend fun setMySongRatings(ratings: Collection<OrbSongRatingMutation>) {
        if (ratings.isEmpty()) return
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        ratings
            .asSequence()
            .filter { it.videoId.isNotBlank() }
            .chunked(200)
            .forEach { chunk ->
                supabase.from("song_ratings").upsert(
                    chunk.map { rating ->
                        OrbSongRatingUpsert(
                            userId = userId,
                            videoId = rating.videoId,
                            recordingKey = rating.recordingKey,
                            status = rating.status,
                            title = rating.title,
                            artist = rating.artist,
                            album = rating.album,
                            durationText = rating.durationText,
                            updatedAt = rating.updatedAt,
                        )
                    },
                )
            }
    }

    /**
     * Batch read of live metadata for people followed by the current account.
     * RLS decides which rows are visible; hidden activity never reaches the UI.
     */
    suspend fun nowPlayingForFollowing(): List<FriendNowPlaying> =
        nowPlayingForUsers(followingIds()).values.toList()

    /** Internal batch helper so the UI never performs one request per friend. */
    suspend fun nowPlayingForUsers(
        userIds: Collection<String>,
    ): Map<String, FriendNowPlaying> {
        val ids = userIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyMap()

        val rows = ids.chunked(MAX_BATCH_SIZE).flatMap { chunk ->
            supabase.from("now_playing")
                .select {
                    filter { isIn("user_id", chunk) }
                }
                .decodeList<FriendNowPlaying>()
        }
        val now = System.currentTimeMillis()
        return rows
            .filter { row -> row.isFresh(now) }
            .associateBy { it.userId }
    }

    /**
     * Realtime stream scoped to followed user IDs. IDs are chunked because the
     * Supabase IN filter supports at most 100 values. No playback polling is
     * performed; pull-to-refresh remains the fallback if Realtime is unavailable.
     */
    fun nowPlayingChangesForUsers(
        userIds: Collection<String>,
    ): Flow<FriendNowPlayingChange> {
        val ids = userIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty() || currentUserId() == null) return emptyFlow()

        val flows = ids.chunked(MAX_BATCH_SIZE).mapIndexed { index, chunk ->
            nowPlayingChangesForChunk(chunk, index)
        }
        return if (flows.size == 1) flows.first() else merge(*flows.toTypedArray())
    }

    /**
     * Recent qualified-enough social feed rows for followed users. Unlike Stats,
     * the social feed becomes eligible at 30 seconds, while Stats keeps its 30%
     * threshold by filtering the same row independently.
     */
    suspend fun recentListeningActivityForUsers(
        userIds: Collection<String>,
        since: Instant,
        maxRows: Int = 240,
    ): List<ListeningActivity> {
        val ids = userIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty() || currentUserId() == null) return emptyList()
        val safeLimit = maxRows.coerceIn(1, 1_000)
        return ids.chunked(MAX_BATCH_SIZE).flatMap { chunk ->
            supabase.from("listening_activity")
                .select {
                    filter {
                        isIn("user_id", chunk)
                        gte("finished_at", since.toString())
                    }
                    order("started_at", Order.DESCENDING)
                    range(0L..(safeLimit - 1).toLong())
                }
                .decodeList<ListeningActivity>()
        }
            .asSequence()
            .filter { it.reachedSocialFeedThreshold() }
            .sortedByDescending { row ->
                runCatching { Instant.parse(row.startedAt) }.getOrNull() ?: Instant.EPOCH
            }
            .take(safeLimit)
            .toList()
    }

    /** Realtime activity rows are what make the Stats social feed append without refresh. */
    fun listeningActivityChangesForUsers(
        userIds: Collection<String>,
    ): Flow<ListeningActivityChange> {
        val ids = userIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty() || currentUserId() == null) return emptyFlow()
        val flows = ids.chunked(MAX_BATCH_SIZE).mapIndexed { index, chunk ->
            listeningActivityChangesForChunk(chunk, index)
        }
        return if (flows.size == 1) flows.first() else merge(*flows.toTypedArray())
    }

    /** Batch-loads reactions for recent feed sessions, including songs that already ended. */
    suspend fun reactionsForListeningActivity(
        sessions: Collection<ListeningActivity>,
    ): Map<String, List<NowPlayingReaction>> {
        if (currentUserId() == null) return emptyMap()
        val activeByKey = sessions.associateBy { it.reactionSessionKey() }
        if (activeByKey.isEmpty()) return emptyMap()
        val listenerIds = sessions.map { it.userId }.distinct()
        val rows = listenerIds.chunked(MAX_BATCH_SIZE).flatMap { chunk ->
            supabase.from("now_playing_reactions")
                .select { filter { isIn("listener_id", chunk) } }
                .decodeList<NowPlayingReaction>()
        }
        return rows
            .filter { it.sessionKey() in activeByKey }
            .groupBy { it.sessionKey() }
            .mapValues { (_, reactions) -> reactions.sortedBy { it.reactedAt } }
    }

    /** Reactions received by the signed-in listener during the requested window. */
    suspend fun recentReactionsForMe(
        since: Instant,
    ): List<NowPlayingReaction> {
        val me = currentUserId() ?: return emptyList()
        return supabase.from("now_playing_reactions")
            .select {
                filter {
                    eq("listener_id", me)
                    gte("reacted_at", since.toString())
                }
                order("reacted_at", Order.DESCENDING)
                range(0L..199L)
            }
            .decodeList<NowPlayingReaction>()
            .sortedByDescending { it.reactedAt }
    }

    suspend fun profilesForUserIds(ids: Collection<String>): List<OrbProfile> =
        profilesForIds(ids.distinct().filter { it.isNotBlank() })

    /** Batch-loads reactions only for the exact live sessions on screen. */
    suspend fun reactionsForNowPlaying(
        sessions: Collection<FriendNowPlaying>,
    ): Map<String, List<NowPlayingReaction>> {
        if (currentUserId() == null) return emptyMap()
        val activeByKey = sessions
            .filter { it.isFresh() }
            .associateBy { it.reactionSessionKey() }
        if (activeByKey.isEmpty()) return emptyMap()

        val listenerIds = sessions.map { it.userId }.distinct()
        val rows = listenerIds.chunked(MAX_BATCH_SIZE).flatMap { chunk ->
            supabase.from("now_playing_reactions")
                .select {
                    filter { isIn("listener_id", chunk) }
                }
                .decodeList<NowPlayingReaction>()
        }
        return rows
            .filter { it.sessionKey() in activeByKey }
            .groupBy { it.sessionKey() }
            .mapValues { (_, reactions) -> reactions.sortedBy { it.reactedAt } }
    }

    /** One reaction per signed-in account and live listening session. */
    suspend fun setNowPlayingReaction(
        session: FriendNowPlaying,
        reaction: NowPlayingReactionType?,
    ) {
        val reactorId = currentUserId() ?: return
        if (reactorId == session.userId) return

        if (reaction == null) {
            supabase.from("now_playing_reactions").delete {
                filter {
                    eq("listener_id", session.userId)
                    eq("reactor_id", reactorId)
                    eq("video_id", session.videoId)
                    eq("activity_started_at", session.startedAt)
                }
            }
        } else {
            supabase.from("now_playing_reactions").upsert(
                NowPlayingReactionUpsert(
                    listenerId = session.userId,
                    reactorId = reactorId,
                    videoId = session.videoId,
                    activityStartedAt = session.startedAt,
                    reaction = reaction.wireValue,
                    reactedAt = Instant.now().toString(),
                ),
            )
        }
    }

    /** Reactions are pushed independently so a card never waits for them. */
    fun reactionChangesForUsers(
        userIds: Collection<String>,
    ): Flow<NowPlayingReactionChange> {
        val ids = userIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty() || currentUserId() == null) return emptyFlow()

        val flows = ids.chunked(MAX_BATCH_SIZE).mapIndexed { index, chunk ->
            reactionChangesForChunk(chunk, index)
        }
        return if (flows.size == 1) flows.first() else merge(*flows.toTypedArray())
    }

    suspend fun setUsername(username: String): Result<String> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        val normalized = requireNotNull(normalizeUsername(username)) {
            "Username must contain 3-30 lowercase letters, numbers, dots or underscores."
        }
        supabase.from("profiles").update(
            {
                set("username", normalized)
            },
        ) {
            filter { eq("id", userId) }
        }
        val current = _myProfileState.value?.takeIf { it.id == userId }
        if (current != null) {
            _myProfileState.value = current.copy(username = normalized)
        }
        normalized
    }

    suspend fun follow(userId: String) {
        val me = currentUserId() ?: return
        if (me == userId) return
        supabase.from("follows").upsert(
            FollowInsert(
                followerId = me,
                followingId = userId,
            ),
        )
    }

    suspend fun unfollow(userId: String) {
        val me = currentUserId() ?: return
        supabase.from("follows").delete {
            filter {
                eq("follower_id", me)
                eq("following_id", userId)
            }
        }
    }

    /** Removes another account from the current user's follower list. */
    suspend fun removeFollower(userId: String) {
        val me = currentUserId() ?: return
        if (me == userId) return
        supabase.from("follows").delete {
            filter {
                eq("follower_id", userId)
                eq("following_id", me)
            }
        }
    }

    /**
     * Blocks [userId] and severs both follow directions. The database trigger
     * also prevents either account from recreating the relationship while the
     * block exists.
     */
    suspend fun blockUser(userId: String) {
        val me = currentUserId() ?: return
        if (me == userId) return
        supabase.from("profile_blocks").upsert(
            ProfileBlockInsert(
                blockerId = me,
                blockedId = userId,
            ),
        )
        // Both directions are intentionally removed after the block is stored.
        // A dedicated RLS policy lets the followed account remove an incoming
        // follower without granting it permission to mutate unrelated rows.
        unfollow(userId)
        removeFollower(userId)
    }

    suspend fun isFollowing(userId: String): Boolean {
        val me = currentUserId() ?: return false
        return supabase.from("follows")
            .select {
                filter {
                    eq("follower_id", me)
                    eq("following_id", userId)
                }
            }
            .decodeList<FollowInsert>()
            .isNotEmpty()
    }

    suspend fun socialCounts(userId: String): ProfileSocialCounts? =
        supabase.from("profile_social_counts")
            .select {
                filter { eq("user_id", userId) }
            }
            .decodeList<ProfileSocialCounts>()
            .firstOrNull()

    /**
     * Four discovery recommendations for an account that follows nobody.
     *
     * @theus is pinned first (unless it is the current account), then the
     * remaining slots are filled by the globally most-played profiles.
     */
    suspend fun recommendedProfilesForEmptyNetwork(
        limit: Int = 4,
    ): List<OrbProfile> {
        val me = currentUserId() ?: return emptyList()
        val safeLimit = limit.coerceIn(1, 4)

        val pinned = profileByUsername("theus")
            ?.takeIf { it.id != me }

        val activityRows = supabase.from("profile_play_counts")
            .select {
                order("plays_count", Order.DESCENDING)
                range(0L..49L)
            }
            .decodeList<ProfilePlayCount>()

        val activeIds = activityRows
            .asSequence()
            .map { it.userId }
            .filter { it != me }
            .filter { it != pinned?.id }
            .distinct()
            .take(16)
            .toList()

        val activeProfiles = profilesForIds(activeIds)
            .associateBy { it.id }

        return buildList {
            pinned?.let(::add)
            activeIds.forEach { id ->
                if (size >= safeLimit) return@forEach
                activeProfiles[id]
                    ?.takeIf { !it.username.isNullOrBlank() }
                    ?.let(::add)
            }
        }.distinctBy { it.id }.take(safeLimit)
    }

    suspend fun profilePlayCount(userId: String): Long =
        supabase.from("profile_play_counts")
            .select {
                filter { eq("user_id", userId) }
            }
            .decodeList<ProfilePlayCount>()
            .firstOrNull()
            ?.playsCount
            ?: 0L

    /**
     * Privacy-safe compatibility score for a profile. The database computes
     * the overlap and returns only the final percentage, so this card can be
     * visible to every authenticated profile viewer without exposing the
     * target user's follower-only listening history.
     */
    suspend fun profileMusicCompatibility(userId: String): Int? {
        if (userId.isBlank()) return null
        val accessToken = supabase.auth.currentSessionOrNull()?.accessToken ?: return null
        val payload = """{"target_user_id":"$userId"}"""
        val request = Request.Builder()
            .url("${OrbSupabase.baseUrl}/rest/v1/rpc/orb_profile_music_compatibility")
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", OrbSupabase.publishableKey)
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val raw = response.body?.string().orEmpty().trim()
                    Regex("\"compatibility_percent\"\\s*:\\s*(\\d+)")
                        .find(raw)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: raw.trim('[', ']', ' ', '\n', '\r', '\t')
                            .toIntOrNull()
                }
            }.getOrNull()
        }?.coerceIn(0, 100)
    }

    /**
     * Profile projection used only to build the favorite artists/albums/
     * songs shown on a profile. The backing SQL view is deliberately separate
     * from listening_activity RLS: Stats/Friends activity remains follower-only
     * (or hidden), while this projection obeys the profile favorites audience.
     */
    suspend fun profileMonthlyActivityForUser(
        userId: String,
        maxRows: Int = 10_000,
    ): List<ListeningActivity> = profileFavoriteActivityForUser(
        userId = userId,
        view = "profile_public_monthly_activity",
        maxRows = maxRows,
    )

    suspend fun profileFavoriteArtistActivityForUser(
        userId: String,
        maxRows: Int = 10_000,
    ): List<ListeningActivity> = profileFavoriteActivityForUser(
        userId = userId,
        view = "profile_public_favorite_artist_activity",
        maxRows = maxRows,
    )

    suspend fun profileFavoriteAlbumActivityForUser(
        userId: String,
        maxRows: Int = 10_000,
    ): List<ListeningActivity> = profileFavoriteActivityForUser(
        userId = userId,
        view = "profile_public_favorite_album_activity",
        maxRows = maxRows,
    )

    suspend fun profileFavoriteSongActivityForUser(
        userId: String,
        maxRows: Int = 10_000,
    ): List<ListeningActivity> = profileFavoriteActivityForUser(
        userId = userId,
        view = "profile_public_favorite_song_activity",
        maxRows = maxRows,
    )

    private suspend fun profileFavoriteActivityForUser(
        userId: String,
        view: String,
        maxRows: Int,
    ): List<ListeningActivity> {
        if (userId.isBlank()) return emptyList()
        val safeLimit = maxRows.coerceIn(1, 20_000)
        return supabase.from(view)
            .select {
                filter { eq("user_id", userId) }
                range(0L..(safeLimit - 1).toLong())
            }
            .decodeList<ListeningActivity>()
    }

    /**
     * Raw listening history. Server RLS keeps this surface follower-only when
     * sharing is enabled, or invisible when the listener selected NOBODY.
     * Profile favorites must use [profileMonthlyActivityForUser] instead.
     */
    suspend fun listeningActivityForUser(
        userId: String,
        since: Instant = Instant.now().minusSeconds(365L * 24L * 60L * 60L),
        maxRows: Int = 2_000,
    ): List<ListeningActivity> {
        if (userId.isBlank()) return emptyList()
        val safeLimit = maxRows.coerceIn(1, 10_000)
        return supabase.from("listening_activity")
            .select {
                filter {
                    eq("user_id", userId)
                    gte("started_at", since.toString())
                }
                // Range must be applied to newest rows first. Profile favorites
                // are monthly, so a prolific account must not have this month
                // pushed out of the 2,000-row window by older plays.
                order("started_at", Order.DESCENDING)
                range(0L..(safeLimit - 1).toLong())
            }
            .decodeList<ListeningActivity>()
            .sortedByDescending { row ->
                runCatching { Instant.parse(row.startedAt) }.getOrNull() ?: Instant.EPOCH
            }
            .take(safeLimit)
    }

    /**
     * Persists every profile privacy control as one server-authoritative write.
     *
     * Do not silently fall back to the legacy two-column schema: doing that made
     * the UI report a successful save while artist/album audiences were actually
     * discarded and then reset on the next profile refresh.
     */
    suspend fun updateProfilePrivacy(
        nowPlayingVisibility: String,
        listeningVisibility: String,
        followingVisibility: String = "everyone",
        followersVisibility: String = "everyone",
        playCountVisibility: String = "everyone",
        favoriteArtistsVisibility: String = "everyone",
        favoriteAlbumsVisibility: String = "everyone",
        favoriteSongsVisibility: String = "everyone",
        // Kept for source compatibility with older call sites. When supplied,
        // it is only used as a fallback for category values still at defaults.
        favoriteContentVisibility: String? = null,
    ): Result<Unit> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        fun normalized(value: String, legacy: String? = null): String {
            val candidate = if (value.equals("everyone", ignoreCase = true) && legacy != null) legacy else value
            return when (candidate.lowercase()) {
                "everyone", "public", "anyone" -> "everyone"
                "followers" -> "followers"
                "nobody", "private", "none" -> "nobody"
                // Older generic privacy UIs exposed mutuals. The new profile
                // contract has only Everyone / Followers / Hidden, so retain a
                // restrictive audience rather than widening it.
                "mutuals", "friends", "following" -> "followers"
                else -> "everyone"
            }
        }

        val artists = normalized(favoriteArtistsVisibility, favoriteContentVisibility)
        val albums = normalized(favoriteAlbumsVisibility, favoriteContentVisibility)
        val songs = normalized(favoriteSongsVisibility, favoriteContentVisibility)
        val legacyUnifiedFavorites = if (artists == albums && albums == songs) {
            artists
        } else {
            // Older clients understand only one favorite-content audience. If
            // categories differ, hide the legacy unified projection so an old
            // build cannot widen a category selected as Followers/Hidden.
            "nobody"
        }

        supabase.from("profiles").update(
            {
                set("now_playing_visibility", nowPlayingVisibility)
                set("listening_visibility", listeningVisibility)
                set("following_visibility", normalized(followingVisibility))
                set("followers_visibility", normalized(followersVisibility))
                set("play_count_visibility", normalized(playCountVisibility))
                set("favorite_artists_visibility", artists)
                set("favorite_albums_visibility", albums)
                set("favorite_songs_visibility", songs)
                set("favorite_content_visibility", legacyUnifiedFavorites)
            },
        ) {
            filter { eq("id", userId) }
        }

        val current = _myProfileState.value?.takeIf { it.id == userId }
        if (current != null) {
            _myProfileState.value = current.copy(
                nowPlayingVisibility = nowPlayingVisibility,
                listeningVisibility = listeningVisibility,
                followingVisibility = normalized(followingVisibility),
                followersVisibility = normalized(followersVisibility),
                playCountVisibility = normalized(playCountVisibility),
                favoriteContentVisibility = legacyUnifiedFavorites,
                favoriteArtistsVisibility = artists,
                favoriteAlbumsVisibility = albums,
                favoriteSongsVisibility = songs,
            )
        }
    }

    /**
     * Saves only coarse ISO/locale metadata. This never sends GPS coordinates,
     * an address, or a raw network identifier to Supabase.
     */
    suspend fun updateRegionalInfo(info: UserRegionalInfo): Result<Unit> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        val current = _myProfileState.value?.takeIf { it.id == userId } ?: myProfile()
        if (current != null &&
            current.countryCode == info.countryCode &&
            current.languageCode == info.languageCode &&
            current.localeTag == info.localeTag
        ) return@runCatching

        val updatedAt = Instant.now().toString()
        supabase.from("profiles").update(
            {
                info.countryCode?.let { set("country_code", it) }
                set("language_code", info.languageCode)
                set("locale_tag", info.localeTag)
                set("locale_updated_at", updatedAt)
            },
        ) {
            filter { eq("id", userId) }
        }

        if (current != null) {
            _myProfileState.value = current.copy(
                countryCode = info.countryCode ?: current.countryCode,
                languageCode = info.languageCode,
                localeTag = info.localeTag,
                localeUpdatedAt = updatedAt,
            )
        }
    }

    /** Keeps the public Orb profile in step with the Google account metadata. */
    suspend fun updateProfileMetadata(
        displayName: String?,
        avatarUrl: String?,
    ): Result<Unit> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        if (displayName.isNullOrBlank() && avatarUrl.isNullOrBlank()) return@runCatching
        val cleanName = displayName?.trim()?.takeIf { it.isNotBlank() }
        val cleanAvatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        supabase.from("profiles").update(
            {
                cleanName?.let { set("display_name", it) }
                cleanAvatar?.let { set("avatar_url", it) }
            },
        ) {
            filter { eq("id", userId) }
        }
        val current = _myProfileState.value?.takeIf { it.id == userId }
        if (current != null) {
            _myProfileState.value = current.copy(
                displayName = cleanName ?: current.displayName,
                avatarUrl = cleanAvatar ?: current.avatarUrl,
            )
        }
    }

    /** Atomically updates every editable profile field; the database enforces username cooldown. */
    suspend fun updateOwnProfile(
        displayName: String,
        username: String,
        avatarUrl: String?,
        avatarIconUrl: String? = null,
    ): Result<Unit> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        val cleanName = displayName.trim().takeIf { it.length in 1..80 }
            ?: error("Display name must contain 1-80 characters.")
        val normalizedUsername = requireNotNull(normalizeUsername(username)) {
            "Username must contain 3-30 lowercase letters, numbers, dots or underscores."
        }
        val cleanAvatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        val cleanAvatarIcon = avatarIconUrl?.trim()?.takeIf { it.isNotBlank() }
        supabase.from("profiles").update(
            {
                set("display_name", cleanName)
                set("username", normalizedUsername)
                cleanAvatar?.let { set("avatar_url", it) }
                cleanAvatarIcon?.let { set("avatar_icon_url", it) }
            },
        ) {
            filter { eq("id", userId) }
        }

        // Optimistically publish the exact saved metadata. All account avatars
        // subscribe to this StateFlow, so the new image propagates throughout
        // the app immediately after the save succeeds.
        val current = _myProfileState.value?.takeIf { it.id == userId }
        _myProfileState.value = (current ?: OrbProfile(id = userId)).copy(
            displayName = cleanName,
            username = normalizedUsername,
            avatarUrl = cleanAvatar ?: current?.avatarUrl,
            avatarIconUrl = cleanAvatarIcon ?: current?.avatarIconUrl,
        )
    }

    /** Uploads the signed-in user's 10:16 profile portrait. */
    suspend fun uploadProfileAvatar(
        bytes: ByteArray,
        mimeType: String,
    ): Result<String> = uploadProfileImage(bytes, mimeType, objectName = "avatar")

    /** Uploads the separately framed square image used by circular profile icons. */
    suspend fun uploadProfileAvatarIcon(
        bytes: ByteArray,
        mimeType: String,
    ): Result<String> = uploadProfileImage(bytes, mimeType, objectName = "avatar_icon")

    private suspend fun uploadProfileImage(
        bytes: ByteArray,
        mimeType: String,
        objectName: String,
    ): Result<String> = runCatching {
        require(bytes.isNotEmpty()) { "The selected image is empty." }
        require(bytes.size <= MAX_PROFILE_IMAGE_BYTES) { "Profile images can be at most 5 MB." }
        require(mimeType.startsWith("image/")) { "The selected file is not an image." }

        val userId = requireNotNull(currentUserId()) { "Orb account is not signed in." }
        val accessToken = requireNotNull(supabase.auth.currentSessionOrNull()?.accessToken) {
            "The Orb session has expired."
        }
        require(objectName == "avatar" || objectName == "avatar_icon") { "Invalid profile image slot." }
        val objectPath = "$userId/$objectName"
        val request = Request.Builder()
            .url("${OrbSupabase.baseUrl}/storage/v1/object/profile-avatars/$objectPath")
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", OrbSupabase.publishableKey)
            .header("x-upsert", "true")
            .put(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        withContext(Dispatchers.IO) {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Profile image upload failed (${response.code}): ${response.body?.string().orEmpty()}")
                }
            }
        }
        "${OrbSupabase.baseUrl}/storage/v1/object/public/profile-avatars/$objectPath?v=${System.currentTimeMillis()}"
    }

    internal suspend fun publishNowPlaying(
        song: Song,
        startedAtMs: Long,
        heartbeatAtMs: Long = System.currentTimeMillis(),
    ) {
        val userId = currentUserId() ?: return
        supabase.from("now_playing").upsert(
            NowPlayingUpsert(
                userId = userId,
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                artworkUrl = song.thumbnailUrl,
                startedAt = instant(startedAtMs),
                heartbeatAt = instant(heartbeatAtMs),
            ),
        )
    }

    internal suspend fun clearNowPlaying() {
        val userId = currentUserId() ?: return
        supabase.from("now_playing").delete {
            filter { eq("user_id", userId) }
        }
    }

    /**
     * Reads the signed-in listener's own activity in pages. The UI never asks
     * Supabase for somebody else's private history; RLS remains authoritative.
     * A single date-bounded stream is then aggregated locally for Stats.
     */
    suspend fun myListeningActivitySince(
        since: Instant,
        pageSize: Int = 1000,
        maxRows: Int = 10_000,
    ): List<ListeningActivity> {
        val userId = currentUserId() ?: return emptyList()
        val size = pageSize.coerceIn(100, 1000)
        val ceiling = maxRows.coerceIn(size, 20_000)

        // Stats can need up to 20 pages of history. Fetching those pages one by
        // one multiplied network latency and made first-open time grow linearly
        // with the user's history. Small parallel batches keep the same row cap
        // and pagination semantics while reducing the number of sequential RTTs.
        return supervisorScope {
            val rows = ArrayList<ListeningActivity>(minOf(size * 4, ceiling))
            var offset = 0L
            var reachedEnd = false

            while (rows.size < ceiling && !reachedEnd) {
                val remaining = ceiling - rows.size
                val pageCount = minOf(4, (remaining + size - 1) / size)
                val ranges = (0 until pageCount).map { pageIndex ->
                    val start = offset + pageIndex.toLong() * size.toLong()
                    val end = minOf(start + size - 1L, ceiling.toLong() - 1L)
                    start..end
                }

                val pages = ranges.map { range ->
                    async {
                        supabase.from("listening_activity")
                            .select {
                                filter {
                                    eq("user_id", userId)
                                    gte("started_at", since.toString())
                                }
                                range(range)
                            }
                            .decodeList<ListeningActivity>()
                    }
                }.awaitAll()

                pages.forEach { page ->
                    if (!reachedEnd) {
                        val room = ceiling - rows.size
                        if (room > 0) rows += page.take(room)
                        if (page.size < size) reachedEnd = true
                    }
                }
                offset += pageCount.toLong() * size.toLong()
            }

            rows
        }
    }

    /**
     * Latest qualified playback sessions for Home's cross-device Recents row.
     * The reporter inserts one row per playback after 30%, including repeats.
     */
    suspend fun myRecentListeningActivity(limit: Int = 10): List<ListeningActivity> {
        val userId = currentUserId() ?: return emptyList()
        val safeLimit = limit.coerceIn(1, 50)
        return supabase.from("listening_activity")
            .select {
                filter { eq("user_id", userId) }
                order("started_at", Order.DESCENDING)
                range(0L..(safeLimit * 3L - 1L))
            }
            .decodeList<ListeningActivity>()
            .asSequence()
            .filter { it.reachedListenThreshold() }
            .take(safeLimit)
            .toList()
    }

    /**
     * Persists the raw listening event used to build Stats. Playlist attribution
     * lives on the same RLS-protected listening_activity row, so Stats can move
     * between devices without a second analytics identity or database client.
     *
     * Returns true when the server accepted the Stats playlist columns. During
     * rollout against an older schema we retry the legacy row so listening
     * history is never lost; callers may keep a tiny local playlist fallback.
     */
    internal suspend fun recordListeningActivity(
        song: Song,
        startedAtMs: Long,
        finishedAtMs: Long,
        durationMs: Long?,
        playedMs: Long?,
    ): Boolean {
        val userId = currentUserId() ?: return false
        val startedAt = instant(startedAtMs)
        val finishedAt = instant(finishedAtMs)
        val safeDuration = durationMs?.takeIf { it >= 0 }
        val safePlayed = playedMs?.takeIf { it >= 0 }

        val languageCode = TrackLanguageResolver.knownOrMetadata(
            videoId = song.videoId,
            title = song.title,
            album = song.albumName,
        )
        val statsRow = ListeningActivityInsert(
            userId = userId,
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            artworkUrl = song.thumbnailUrl,
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = safeDuration,
            playedMs = safePlayed,
            languageCode = languageCode,
            sourcePlaylistId = song.sourcePlaylistId,
            sourcePlaylistTitle = song.sourcePlaylistTitle,
            sourcePlaylistArtworkUrl = song.sourcePlaylistArtworkUrl,
        )

        val extendedWrite = runCatching {
            supabase.from("listening_activity").insert(statsRow)
        }
        if (extendedWrite.isSuccess) return true

        val extendedError = requireNotNull(extendedWrite.exceptionOrNull())
        if (!extendedError.looksLikeMissingLanguageColumn()) {
            if (!extendedError.looksLikeMissingStatsColumns()) throw extendedError
            return insertLegacyListeningActivity(
                userId = userId,
                song = song,
                startedAt = startedAt,
                finishedAt = finishedAt,
                safeDuration = safeDuration,
                safePlayed = safePlayed,
            )
        }

        // During rollout, a database may already have the playlist Stats columns
        // but not language_code. Retry the exact previous row shape first so a
        // missing language migration never drops playlist attribution.
        val withoutLanguage = runCatching {
            supabase.from("listening_activity").insert(
                ListeningActivityInsertWithoutLanguage(
                    userId = userId,
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    album = song.albumName,
                    artworkUrl = song.thumbnailUrl,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    durationMs = safeDuration,
                    playedMs = safePlayed,
                    sourcePlaylistId = song.sourcePlaylistId,
                    sourcePlaylistTitle = song.sourcePlaylistTitle,
                    sourcePlaylistArtworkUrl = song.sourcePlaylistArtworkUrl,
                ),
            )
        }
        if (withoutLanguage.isSuccess) return true
        val fallbackError = requireNotNull(withoutLanguage.exceptionOrNull())
        if (!fallbackError.looksLikeMissingStatsColumns()) throw fallbackError
        return insertLegacyListeningActivity(
            userId = userId,
            song = song,
            startedAt = startedAt,
            finishedAt = finishedAt,
            safeDuration = safeDuration,
            safePlayed = safePlayed,
        )
    }

    /** Updates the already-qualified row instead of creating a second play. */
    internal suspend fun updateListeningActivity(
        song: Song,
        startedAtMs: Long,
        finishedAtMs: Long,
        durationMs: Long?,
        playedMs: Long,
    ) {
        val userId = currentUserId() ?: return
        val languageCode = TrackLanguageResolver.knownOrMetadata(
            videoId = song.videoId,
            title = song.title,
            album = song.albumName,
        )
        val update = runCatching {
            supabase.from("listening_activity").update(
                {
                    set("finished_at", instant(finishedAtMs))
                    durationMs?.takeIf { it > 0L }?.let { set("duration_ms", it) }
                    set("played_ms", playedMs.coerceAtLeast(0L))
                    languageCode?.let { set("language_code", it) }
                },
            ) {
                filter {
                    eq("user_id", userId)
                    eq("video_id", song.videoId)
                    eq("started_at", instant(startedAtMs))
                }
            }
        }
        if (update.isSuccess) return
        val error = requireNotNull(update.exceptionOrNull())
        if (languageCode == null || !error.looksLikeMissingLanguageColumn()) throw error
        // Old Supabase schema: keep progress updates working while the language
        // migration is pending.
        supabase.from("listening_activity").update(
            {
                set("finished_at", instant(finishedAtMs))
                durationMs?.takeIf { it > 0L }?.let { set("duration_ms", it) }
                set("played_ms", playedMs.coerceAtLeast(0L))
            },
        ) {
            filter {
                eq("user_id", userId)
                eq("video_id", song.videoId)
                eq("started_at", instant(startedAtMs))
            }
        }
    }

    internal suspend fun backfillListeningLanguage(videoId: String, languageCode: String) {
        val userId = currentUserId() ?: return
        val code = languageCode.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z]{2,3}$")) } ?: return
        val result = runCatching {
            supabase.from("listening_activity").update(
                { set("language_code", code) },
            ) {
                filter {
                    eq("user_id", userId)
                    eq("video_id", videoId)
                }
            }
        }
        val error = result.exceptionOrNull() ?: return
        if (!error.looksLikeMissingLanguageColumn()) throw error
    }

    private suspend fun insertLegacyListeningActivity(
        userId: String,
        song: Song,
        startedAt: String,
        finishedAt: String,
        safeDuration: Long?,
        safePlayed: Long?,
    ): Boolean {
        // Backward-compatible rollout path only for a server predating the Stats
        // playlist columns. Network/auth failures never enter this path.
        supabase.from("listening_activity").insert(
            LegacyListeningActivityInsert(
                userId = userId,
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                artworkUrl = song.thumbnailUrl,
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = safeDuration,
                playedMs = safePlayed,
            ),
        )
        return false
    }

    private suspend fun profilesForIds(ids: List<String>): List<OrbProfile> =
        ids.chunked(MAX_BATCH_SIZE).flatMap { chunk ->
            supabase.from("profiles")
                .select {
                    filter { isIn("id", chunk) }
                }
                .decodeList<OrbProfile>()
        }

    fun followChangesForMe(): Flow<FollowStatsRow> {
        val me = currentUserId() ?: return emptyFlow()
        return channelFlow {
            val channelId = "orb-follow-notifications-${me.take(8)}-${System.nanoTime()}"
            val channel = supabase.realtime.channel(channelId)
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "follows"
            }

            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                changes.collect { action ->
                    if (action is PostgresAction.Insert) {
                        action.decodeRecordOrNull<FollowStatsRow>()
                            ?.takeIf { it.followingId == me }
                            ?.let { send(it) }
                    }
                }
            }

            try {
                channel.subscribe(blockUntilSubscribed = true)
                awaitCancellation()
            } finally {
                collector.cancel()
                withContext(NonCancellable) {
                    runCatching { supabase.realtime.removeChannel(channel) }
                }
            }
        }
    }

    private fun nowPlayingChangesForChunk(
        userIds: List<String>,
        chunkIndex: Int,
    ): Flow<FriendNowPlayingChange> = channelFlow {
        val me = currentUserId() ?: return@channelFlow
        val channelId = "orb-friends-now-playing-${me.take(8)}-$chunkIndex-${System.nanoTime()}"
        val channel = supabase.realtime.channel(channelId)
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "now_playing"
        }

        // Start collecting before subscribe() so the callback is installed before
        // the channel can receive its first database event.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            changes.collect { action ->
                when (action) {
                    is PostgresAction.Insert -> {
                        action.decodeRecordOrNull<FriendNowPlaying>()
                            ?.takeIf { it.userId in userIds }
                            ?.let { send(FriendNowPlayingChange.Upsert(it)) }
                    }
                    is PostgresAction.Update -> {
                        action.decodeRecordOrNull<FriendNowPlaying>()
                            ?.takeIf { it.userId in userIds }
                            ?.let { send(FriendNowPlayingChange.Upsert(it)) }
                    }
                    is PostgresAction.Delete -> {
                        val userId = action.oldRecord["user_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                        if (userId != null && userId in userIds) {
                            send(FriendNowPlayingChange.Remove(userId))
                        }
                    }
                    is PostgresAction.Select -> Unit
                }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitCancellation()
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private fun listeningActivityChangesForChunk(
        userIds: List<String>,
        chunkIndex: Int,
    ): Flow<ListeningActivityChange> = channelFlow {
        val me = currentUserId() ?: return@channelFlow
        val channelId = "orb-friends-activity-${me.take(8)}-$chunkIndex-${System.nanoTime()}"
        val channel = supabase.realtime.channel(channelId)
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "listening_activity"
        }

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            changes.collect { action ->
                when (action) {
                    is PostgresAction.Insert -> {
                        action.decodeRecordOrNull<ListeningActivity>()
                            ?.takeIf { it.userId in userIds }
                            ?.let { send(ListeningActivityChange.Upsert(it)) }
                    }
                    is PostgresAction.Update -> {
                        action.decodeRecordOrNull<ListeningActivity>()
                            ?.takeIf { it.userId in userIds }
                            ?.let { send(ListeningActivityChange.Upsert(it)) }
                    }
                    is PostgresAction.Delete, is PostgresAction.Select -> Unit
                }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitCancellation()
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private fun reactionChangesForChunk(
        userIds: List<String>,
        chunkIndex: Int,
    ): Flow<NowPlayingReactionChange> = channelFlow {
        val me = currentUserId() ?: return@channelFlow
        val channelId = "orb-friends-reactions-${me.take(8)}-$chunkIndex-${System.nanoTime()}"
        val channel = supabase.realtime.channel(channelId)
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "now_playing_reactions"
            filter("listener_id", FilterOperator.IN, userIds)
        }

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            changes.collect { action ->
                when (action) {
                    is PostgresAction.Insert -> {
                        action.decodeRecordOrNull<NowPlayingReaction>()
                            ?.takeIf { it.listenerId in userIds }
                            ?.let { send(NowPlayingReactionChange.Upsert(it)) }
                    }
                    is PostgresAction.Update -> {
                        action.decodeRecordOrNull<NowPlayingReaction>()
                            ?.takeIf { it.listenerId in userIds }
                            ?.let { send(NowPlayingReactionChange.Upsert(it)) }
                    }
                    is PostgresAction.Delete -> {
                        action.deletedReactionOrNull()
                            ?.takeIf { it.listenerId in userIds }
                            ?.let { send(NowPlayingReactionChange.Remove(it)) }
                    }
                    is PostgresAction.Select -> Unit
                }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitCancellation()
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private fun PostgresAction.Delete.deletedReactionOrNull(): NowPlayingReaction? = runCatching {
        NowPlayingReaction(
            listenerId = requireNotNull(oldRecord["listener_id"]?.jsonPrimitive?.contentOrNull),
            reactorId = requireNotNull(oldRecord["reactor_id"]?.jsonPrimitive?.contentOrNull),
            videoId = requireNotNull(oldRecord["video_id"]?.jsonPrimitive?.contentOrNull),
            activityStartedAt = requireNotNull(
                oldRecord["activity_started_at"]?.jsonPrimitive?.contentOrNull,
            ),
            reaction = oldRecord["reaction"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            reactedAt = oldRecord["reacted_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

    private fun Throwable.looksLikeMissingLanguageColumn(): Boolean {
        val text = buildString {
            append(message.orEmpty())
            cause?.message?.let { append(' ').append(it) }
        }.lowercase()
        return ("language_code" in text) &&
            ("pgrst204" in text || "column" in text || "schema cache" in text)
    }

    private fun Throwable.looksLikeMissingStatsColumns(): Boolean {
        val text = buildString {
            append(message.orEmpty())
            cause?.message?.let { append(' ').append(it) }
        }.lowercase()
        return "pgrst204" in text ||
            (("column" in text || "schema cache" in text) && "source_playlist_" in text)
    }

    private fun normalizeUsername(value: String): String? {
        val normalized = value.trim().removePrefix("@").lowercase()
        return normalized.takeIf {
            it.length in 3..30 && USERNAME.matches(it)
        }
    }

    private fun normalizeUsernamePrefix(value: String): String? {
        val normalized = value.trim().removePrefix("@").lowercase()
        if (normalized.length > 30) return null
        return normalized.takeIf { text ->
            text.all { ch ->
                ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_'
            }
        }
    }

    private fun instant(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs.coerceAtLeast(0L)).toString()

    private const val MAX_BATCH_SIZE = 100
    private const val MAX_PROFILE_IMAGE_BYTES = 5 * 1024 * 1024
    private val USERNAME = Regex("^[a-z0-9._]+$")
}
