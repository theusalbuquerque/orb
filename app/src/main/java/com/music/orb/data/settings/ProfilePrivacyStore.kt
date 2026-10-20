package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Visibility choices for social-profile surfaces. Wire values intentionally
 * match Supabase so privacy follows the account across devices.
 */
enum class ProfileAudience(val wireValue: String) {
    EVERYONE("everyone"),
    FOLLOWERS("followers"),
    NOBODY("nobody");

    companion object {
        fun fromWire(value: String?): ProfileAudience = when (value?.lowercase()) {
            "everyone", "public", "anyone" -> EVERYONE
            "followers", "mutuals", "friends", "following" -> FOLLOWERS
            "nobody", "private", "none" -> NOBODY
            else -> EVERYONE
        }

        val profileSectionChoices: List<ProfileAudience> = listOf(EVERYONE, FOLLOWERS, NOBODY)
    }
}

/** Device cache for profile controls; the server remains authoritative when available. */
object ProfilePrivacyStore {
    private lateinit var prefs: SharedPreferences

    /** Who can see listening / now-playing activity. Legacy default stays followers-only. */
    val listeningAudience = MutableStateFlow(ProfileAudience.FOLLOWERS)

    /** Compatibility mirror for older two-state call sites. */
    val shareNowPlaying = MutableStateFlow(true)

    /** Independent profile-page audiences. */
    val followingAudience = MutableStateFlow(ProfileAudience.EVERYONE)
    val followersAudience = MutableStateFlow(ProfileAudience.EVERYONE)
    val playCountAudience = MutableStateFlow(ProfileAudience.EVERYONE)
    val favoriteArtistsAudience = MutableStateFlow(ProfileAudience.EVERYONE)
    val favoriteAlbumsAudience = MutableStateFlow(ProfileAudience.EVERYONE)
    val favoriteSongsAudience = MutableStateFlow(ProfileAudience.EVERYONE)

    /** Recent ratings were previously opt-in public; preserve private as the migration default. */
    val recentRatingsAudience = MutableStateFlow(ProfileAudience.NOBODY)

    /** Compatibility mirror for the old public/private album-rating toggle. */
    val shareAlbumRatings = MutableStateFlow(false)

    // Boolean compatibility mirrors for older call sites. FOLLOWERS is enabled,
    // so only NOBODY maps to false.
    val shareFollowing = MutableStateFlow(true)
    val shareFollowers = MutableStateFlow(true)
    val sharePlayCount = MutableStateFlow(true)
    val shareFavoriteArtists = MutableStateFlow(true)
    val shareFavoriteAlbums = MutableStateFlow(true)
    val shareFavoriteSongs = MutableStateFlow(true)

    /** Legacy unified favorite audience kept for older surfaces/builds. */
    val favoriteContentAudience = MutableStateFlow(ProfileAudience.EVERYONE)
    val artistAudience = favoriteContentAudience
    val albumAudience = favoriteContentAudience

    fun init(context: Context) {
        prefs = context.getSharedPreferences("orb_profile_privacy", Context.MODE_PRIVATE)

        listeningAudience.value = prefs.getString(KEY_LISTENING_AUDIENCE, null)
            ?.let(::normalizeProfileSectionAudience)
            ?: if (prefs.getBoolean(KEY_SHARE_NOW_PLAYING, true)) {
                ProfileAudience.FOLLOWERS
            } else {
                ProfileAudience.NOBODY
            }

        followingAudience.value = readAudience(KEY_FOLLOWING_AUDIENCE, KEY_SHARE_FOLLOWING)
        followersAudience.value = readAudience(KEY_FOLLOWERS_AUDIENCE, KEY_SHARE_FOLLOWERS)
        playCountAudience.value = readAudience(KEY_PLAY_COUNT_AUDIENCE, KEY_SHARE_PLAY_COUNT)
        favoriteArtistsAudience.value = readAudience(KEY_FAVORITE_ARTISTS_AUDIENCE, KEY_SHARE_FAVORITE_ARTISTS)
        favoriteAlbumsAudience.value = readAudience(KEY_FAVORITE_ALBUMS_AUDIENCE, KEY_SHARE_FAVORITE_ALBUMS)
        favoriteSongsAudience.value = readAudience(KEY_FAVORITE_SONGS_AUDIENCE, KEY_SHARE_FAVORITE_SONGS)
        recentRatingsAudience.value = prefs.getString(KEY_RECENT_RATINGS_AUDIENCE, null)
            ?.let(::normalizeProfileSectionAudience)
            ?: if (prefs.getBoolean(KEY_SHARE_ALBUM_RATINGS, false)) {
                ProfileAudience.EVERYONE
            } else {
                ProfileAudience.NOBODY
            }

        refreshCompatibilityMirrors()
    }

    private fun readAudience(stringKey: String, legacyBooleanKey: String): ProfileAudience {
        prefs.getString(stringKey, null)?.let { return normalizeProfileSectionAudience(it) }
        return if (prefs.contains(legacyBooleanKey)) {
            if (prefs.getBoolean(legacyBooleanKey, true)) ProfileAudience.EVERYONE else ProfileAudience.NOBODY
        } else {
            ProfileAudience.EVERYONE
        }
    }

    fun syncFromProfile(
        nowPlayingVisibility: String?,
        listeningVisibility: String?,
        followingVisibility: String? = null,
        followersVisibility: String? = null,
        playCountVisibility: String? = null,
        favoriteContentVisibility: String? = null,
        favoriteArtistsVisibility: String? = null,
        favoriteAlbumsVisibility: String? = null,
        favoriteSongsVisibility: String? = null,
        recentRatingsVisibility: String? = null,
    ) {
        listeningAudience.value = normalizeProfileSectionAudience(
            nowPlayingVisibility ?: listeningVisibility ?: ProfileAudience.FOLLOWERS.wireValue,
        )
        followingAudience.value = normalizeProfileSectionAudience(followingVisibility)
        followersAudience.value = normalizeProfileSectionAudience(followersVisibility)
        playCountAudience.value = normalizeProfileSectionAudience(playCountVisibility)
        favoriteArtistsAudience.value = normalizeProfileSectionAudience(
            favoriteArtistsVisibility ?: favoriteContentVisibility,
        )
        favoriteAlbumsAudience.value = normalizeProfileSectionAudience(
            favoriteAlbumsVisibility ?: favoriteContentVisibility,
        )
        favoriteSongsAudience.value = normalizeProfileSectionAudience(
            favoriteSongsVisibility ?: favoriteContentVisibility,
        )
        if (recentRatingsVisibility != null) {
            recentRatingsAudience.value = normalizeProfileSectionAudience(recentRatingsVisibility)
        }
        refreshCompatibilityMirrors()
        persistAudiences()
    }

    fun setListeningAudience(value: ProfileAudience) {
        listeningAudience.value = normalizeProfileSectionAudience(value.wireValue)
        refreshCompatibilityMirrors()
        persistAudiences()
    }

    /** Backward-compatible toggle: ON retains the historical followers-only behavior. */
    fun setShareNowPlaying(value: Boolean) {
        setListeningAudience(if (value) ProfileAudience.FOLLOWERS else ProfileAudience.NOBODY)
    }

    fun setRecentRatingsAudience(value: ProfileAudience) {
        recentRatingsAudience.value = normalizeProfileSectionAudience(value.wireValue)
        refreshCompatibilityMirrors()
        persistAudiences()
    }

    /** Backward-compatible public/private setter. */
    fun setShareAlbumRatings(value: Boolean) {
        setRecentRatingsAudience(if (value) ProfileAudience.EVERYONE else ProfileAudience.NOBODY)
    }

    fun setProfileVisibilityAudiences(
        following: ProfileAudience = followingAudience.value,
        followers: ProfileAudience = followersAudience.value,
        playCount: ProfileAudience = playCountAudience.value,
        favoriteArtists: ProfileAudience = favoriteArtistsAudience.value,
        favoriteAlbums: ProfileAudience = favoriteAlbumsAudience.value,
        favoriteSongs: ProfileAudience = favoriteSongsAudience.value,
        recentRatings: ProfileAudience = recentRatingsAudience.value,
    ) {
        followingAudience.value = normalizeProfileSectionAudience(following.wireValue)
        followersAudience.value = normalizeProfileSectionAudience(followers.wireValue)
        playCountAudience.value = normalizeProfileSectionAudience(playCount.wireValue)
        favoriteArtistsAudience.value = normalizeProfileSectionAudience(favoriteArtists.wireValue)
        favoriteAlbumsAudience.value = normalizeProfileSectionAudience(favoriteAlbums.wireValue)
        favoriteSongsAudience.value = normalizeProfileSectionAudience(favoriteSongs.wireValue)
        recentRatingsAudience.value = normalizeProfileSectionAudience(recentRatings.wireValue)
        refreshCompatibilityMirrors()
        persistAudiences()
    }

    /** Backward-compatible boolean setter: ON means EVERYONE, OFF means NOBODY. */
    fun setProfileVisibility(
        following: Boolean = shareFollowing.value,
        followers: Boolean = shareFollowers.value,
        playCount: Boolean = sharePlayCount.value,
        favoriteArtists: Boolean = shareFavoriteArtists.value,
        favoriteAlbums: Boolean = shareFavoriteAlbums.value,
        favoriteSongs: Boolean = shareFavoriteSongs.value,
    ) = setProfileVisibilityAudiences(
        following = if (following) ProfileAudience.EVERYONE else ProfileAudience.NOBODY,
        followers = if (followers) ProfileAudience.EVERYONE else ProfileAudience.NOBODY,
        playCount = if (playCount) ProfileAudience.EVERYONE else ProfileAudience.NOBODY,
        favoriteArtists = if (favoriteArtists) ProfileAudience.EVERYONE else ProfileAudience.NOBODY,
        favoriteAlbums = if (favoriteAlbums) ProfileAudience.EVERYONE else ProfileAudience.NOBODY,
        favoriteSongs = if (favoriteSongs) ProfileAudience.EVERYONE else ProfileAudience.NOBODY,
    )

    fun setFavoriteContentAudience(value: ProfileAudience) {
        val normalized = normalizeProfileSectionAudience(value.wireValue)
        setProfileVisibilityAudiences(
            favoriteArtists = normalized,
            favoriteAlbums = normalized,
            favoriteSongs = normalized,
        )
    }

    @Deprecated("Favorites now have independent per-section audiences.")
    fun setArtistAudience(value: ProfileAudience) = setProfileVisibilityAudiences(favoriteArtists = value)

    @Deprecated("Favorites now have independent per-section audiences.")
    fun setAlbumAudience(value: ProfileAudience) = setProfileVisibilityAudiences(favoriteAlbums = value)

    private fun refreshCompatibilityMirrors() {
        shareNowPlaying.value = listeningAudience.value != ProfileAudience.NOBODY
        shareAlbumRatings.value = recentRatingsAudience.value != ProfileAudience.NOBODY
        shareFollowing.value = followingAudience.value != ProfileAudience.NOBODY
        shareFollowers.value = followersAudience.value != ProfileAudience.NOBODY
        sharePlayCount.value = playCountAudience.value != ProfileAudience.NOBODY
        shareFavoriteArtists.value = favoriteArtistsAudience.value != ProfileAudience.NOBODY
        shareFavoriteAlbums.value = favoriteAlbumsAudience.value != ProfileAudience.NOBODY
        shareFavoriteSongs.value = favoriteSongsAudience.value != ProfileAudience.NOBODY

        val favorites = listOf(
            favoriteArtistsAudience.value,
            favoriteAlbumsAudience.value,
            favoriteSongsAudience.value,
        )
        favoriteContentAudience.value = if (favorites.distinct().size == 1) {
            favorites.first()
        } else {
            ProfileAudience.NOBODY
        }
    }

    private fun persistAudiences() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_LISTENING_AUDIENCE, listeningAudience.value.wireValue)
            .putBoolean(KEY_SHARE_NOW_PLAYING, shareNowPlaying.value)
            .putString(KEY_FOLLOWING_AUDIENCE, followingAudience.value.wireValue)
            .putString(KEY_FOLLOWERS_AUDIENCE, followersAudience.value.wireValue)
            .putString(KEY_PLAY_COUNT_AUDIENCE, playCountAudience.value.wireValue)
            .putString(KEY_FAVORITE_ARTISTS_AUDIENCE, favoriteArtistsAudience.value.wireValue)
            .putString(KEY_FAVORITE_ALBUMS_AUDIENCE, favoriteAlbumsAudience.value.wireValue)
            .putString(KEY_FAVORITE_SONGS_AUDIENCE, favoriteSongsAudience.value.wireValue)
            .putString(KEY_RECENT_RATINGS_AUDIENCE, recentRatingsAudience.value.wireValue)
            .putBoolean(KEY_SHARE_ALBUM_RATINGS, shareAlbumRatings.value)
            .putBoolean(KEY_SHARE_FOLLOWING, shareFollowing.value)
            .putBoolean(KEY_SHARE_FOLLOWERS, shareFollowers.value)
            .putBoolean(KEY_SHARE_PLAY_COUNT, sharePlayCount.value)
            .putBoolean(KEY_SHARE_FAVORITE_ARTISTS, shareFavoriteArtists.value)
            .putBoolean(KEY_SHARE_FAVORITE_ALBUMS, shareFavoriteAlbums.value)
            .putBoolean(KEY_SHARE_FAVORITE_SONGS, shareFavoriteSongs.value)
            .putString(KEY_FAVORITE_CONTENT_AUDIENCE, favoriteContentAudience.value.wireValue)
            .apply()
    }

    private fun normalizeProfileSectionAudience(value: String?): ProfileAudience = when (ProfileAudience.fromWire(value)) {
        ProfileAudience.EVERYONE -> ProfileAudience.EVERYONE
        ProfileAudience.FOLLOWERS -> ProfileAudience.FOLLOWERS
        ProfileAudience.NOBODY -> ProfileAudience.NOBODY
    }

    private const val KEY_SHARE_NOW_PLAYING = "share_now_playing"
    private const val KEY_LISTENING_AUDIENCE = "profile_listening_audience_v3"
    private const val KEY_SHARE_FOLLOWING = "share_profile_following"
    private const val KEY_SHARE_FOLLOWERS = "share_profile_followers"
    private const val KEY_SHARE_PLAY_COUNT = "share_profile_play_count"
    private const val KEY_SHARE_FAVORITE_ARTISTS = "share_profile_favorite_artists"
    private const val KEY_SHARE_FAVORITE_ALBUMS = "share_profile_favorite_albums"
    private const val KEY_SHARE_FAVORITE_SONGS = "share_profile_favorite_songs"

    private const val KEY_FOLLOWING_AUDIENCE = "profile_following_audience_v2"
    private const val KEY_FOLLOWERS_AUDIENCE = "profile_followers_audience_v2"
    private const val KEY_PLAY_COUNT_AUDIENCE = "profile_play_count_audience_v2"
    private const val KEY_FAVORITE_ARTISTS_AUDIENCE = "profile_favorite_artists_audience_v2"
    private const val KEY_FAVORITE_ALBUMS_AUDIENCE = "profile_favorite_albums_audience_v2"
    private const val KEY_FAVORITE_SONGS_AUDIENCE = "profile_favorite_songs_audience_v2"
    private const val KEY_RECENT_RATINGS_AUDIENCE = "profile_recent_ratings_audience_v3"
    private const val KEY_SHARE_ALBUM_RATINGS = "profile_share_album_ratings_v1"

    private const val KEY_FAVORITE_CONTENT_AUDIENCE = "favorite_content_audience"
}
