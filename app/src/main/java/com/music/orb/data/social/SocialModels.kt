package com.music.orb.data.social

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrbProfile(
    val id: String,
    val username: String? = null,
    @SerialName("username_updated_at") val usernameUpdatedAt: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_icon_url") val avatarIconUrl: String? = null,
    val bio: String? = null,
    @SerialName("listening_visibility") val listeningVisibility: String = "followers",
    @SerialName("now_playing_visibility") val nowPlayingVisibility: String = "followers",
    @SerialName("following_visibility") val followingVisibility: String? = null,
    @SerialName("followers_visibility") val followersVisibility: String? = null,
    @SerialName("play_count_visibility") val playCountVisibility: String? = null,
    // Legacy unified field remains readable for older accounts/app versions.
    @SerialName("favorite_content_visibility") val favoriteContentVisibility: String? = null,
    @SerialName("favorite_artists_visibility") val favoriteArtistsVisibility: String? = null,
    @SerialName("favorite_albums_visibility") val favoriteAlbumsVisibility: String? = null,
    @SerialName("favorite_songs_visibility") val favoriteSongsVisibility: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("locale_tag") val localeTag: String? = null,
    @SerialName("locale_updated_at") val localeUpdatedAt: String? = null,
)

@Serializable
data class FollowInsert(
    @SerialName("follower_id") val followerId: String,
    @SerialName("following_id") val followingId: String,
)

@Serializable
data class ProfileBlockInsert(
    @SerialName("blocker_id") val blockerId: String,
    @SerialName("blocked_id") val blockedId: String,
)

@Serializable
data class FollowStatsRow(
    @SerialName("follower_id") val followerId: String,
    @SerialName("following_id") val followingId: String = "",
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ProfileSocialCounts(
    @SerialName("user_id") val userId: String,
    @SerialName("followers_count") val followersCount: Long = 0,
    @SerialName("following_count") val followingCount: Long = 0,
)

@Serializable
data class ProfilePlayCount(
    @SerialName("user_id") val userId: String,
    @SerialName("plays_count") val playsCount: Long = 0,
)

/** Public read model used by the Friends UI. It contains metadata only. */
@Serializable
data class FriendNowPlaying(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("heartbeat_at") val heartbeatAt: String,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        runCatching { java.time.Instant.parse(heartbeatAt).toEpochMilli() }
            .getOrNull()
            ?.let { heartbeat -> nowMs - heartbeat <= LIVE_WINDOW_MS }
            ?: false

    private companion object {
        // Playback reports approximately once per minute. Three minutes keeps
        // normal mobile jitter safe while preventing abandoned rows from
        // looking live indefinitely after an abrupt process death.
        const val LIVE_WINDOW_MS = 180_000L
    }
}

/** Stable identity for one person's current listening session. */
fun FriendNowPlaying.reactionSessionKey(): String =
    "$userId|$videoId|${startedAt.stableInstantKey()}"

/** The only reactions accepted by Orb's live-listening feed. */
enum class NowPlayingReactionType(
    val wireValue: String,
    val emoji: String,
) {
    HEART("heart", "❤️"),
    CLAP("clap", "👏"),
    SAD("sad", "😢"),
    SURPRISED("surprised", "😮"),
    SMILE("smile", "😊"),
    THUMBS_UP("thumbs_up", "👍"),
    THUMBS_DOWN("thumbs_down", "👎");

    companion object {
        fun fromWireValue(value: String): NowPlayingReactionType? =
            values().firstOrNull { it.wireValue == value }
    }
}

@Serializable
data class NowPlayingReaction(
    @SerialName("listener_id") val listenerId: String,
    @SerialName("reactor_id") val reactorId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("activity_started_at") val activityStartedAt: String,
    val reaction: String,
    @SerialName("reacted_at") val reactedAt: String,
) {
    fun sessionKey(): String = "$listenerId|$videoId|${activityStartedAt.stableInstantKey()}"
    fun type(): NowPlayingReactionType? = NowPlayingReactionType.fromWireValue(reaction)
}

private fun String.stableInstantKey(): String =
    runCatching { java.time.Instant.parse(this).toEpochMilli().toString() }
        .getOrDefault(this)

sealed interface NowPlayingReactionChange {
    val value: NowPlayingReaction

    data class Upsert(override val value: NowPlayingReaction) : NowPlayingReactionChange
    data class Remove(override val value: NowPlayingReaction) : NowPlayingReactionChange
}

@Serializable
internal data class NowPlayingReactionUpsert(
    @SerialName("listener_id") val listenerId: String,
    @SerialName("reactor_id") val reactorId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("activity_started_at") val activityStartedAt: String,
    val reaction: String,
    @SerialName("reacted_at") val reactedAt: String,
)

sealed interface FriendNowPlayingChange {
    val userId: String

    data class Upsert(val value: FriendNowPlaying) : FriendNowPlayingChange {
        override val userId: String get() = value.userId
    }

    data class Remove(override val userId: String) : FriendNowPlayingChange
}

@Serializable
internal data class NowPlayingUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("tidal_id") val tidalId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("heartbeat_at") val heartbeatAt: String,
)


@Serializable
data class ListeningActivity(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("played_ms") val playedMs: Long? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("source_playlist_id") val sourcePlaylistId: String? = null,
    @SerialName("source_playlist_title") val sourcePlaylistTitle: String? = null,
    @SerialName("source_playlist_artwork_url") val sourcePlaylistArtworkUrl: String? = null,
)

/** True only after real forward playback reached 30% of the track. */
fun ListeningActivity.reachedListenThreshold(): Boolean {
    val duration = durationMs?.takeIf { it > 0L } ?: return false
    val listened = playedMs?.takeIf { it >= 0L } ?: run {
        val start = runCatching { Instant.parse(startedAt) }.getOrNull()
        val finish = runCatching { Instant.parse(finishedAt) }.getOrNull()
        if (start != null && finish != null && finish > start) {
            Duration.between(start, finish).toMillis()
        } else {
            0L
        }
    }
    return listened.toDouble() / duration.toDouble() >= 0.30
}


sealed interface ListeningActivityChange {
    val value: ListeningActivity

    data class Upsert(override val value: ListeningActivity) : ListeningActivityChange
    data class Remove(override val value: ListeningActivity) : ListeningActivityChange
}

/** Stable identity shared by activity feed rows and reaction rows. */
fun ListeningActivity.reactionSessionKey(): String =
    "$userId|$videoId|${startedAt.stableInstantKey()}"

/** Stats social feed qualification is intentionally independent from Stats' 30% rule. */
fun ListeningActivity.reachedSocialFeedThreshold(): Boolean =
    (playedMs ?: 0L) >= 30_000L

@Serializable
internal data class ListeningActivityInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("tidal_id") val tidalId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("played_ms") val playedMs: Long? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("source_playlist_id") val sourcePlaylistId: String? = null,
    @SerialName("source_playlist_title") val sourcePlaylistTitle: String? = null,
    @SerialName("source_playlist_artwork_url") val sourcePlaylistArtworkUrl: String? = null,
)

/** Rollout insert for servers that have playlist Stats columns but not language_code yet. */
@Serializable
internal data class ListeningActivityInsertWithoutLanguage(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("tidal_id") val tidalId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("played_ms") val playedMs: Long? = null,
    @SerialName("source_playlist_id") val sourcePlaylistId: String? = null,
    @SerialName("source_playlist_title") val sourcePlaylistTitle: String? = null,
    @SerialName("source_playlist_artwork_url") val sourcePlaylistArtworkUrl: String? = null,
)

/** Legacy insert used only while a server has not applied the Stats migration yet. */
@Serializable
internal data class LegacyListeningActivityInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("tidal_id") val tidalId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("played_ms") val playedMs: Long? = null,
)

/** Persistent Orb-owned song rating, independent from the YouTube library. */
@Serializable
data class OrbSongRating(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("recording_key") val recordingKey: String? = null,
    val status: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("duration_text") val durationText: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

/** App-side mutation; SocialRepository injects the authenticated user id. */
data class OrbSongRatingMutation(
    val videoId: String,
    val recordingKey: String? = null,
    val status: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationText: String? = null,
    val updatedAt: String,
)

@Serializable
internal data class OrbSongRatingUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("recording_key") val recordingKey: String? = null,
    val status: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("duration_text") val durationText: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)
