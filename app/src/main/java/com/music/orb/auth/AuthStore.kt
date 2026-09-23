package com.music.orb.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.orb.data.DebugLog as Log
import com.music.orb.data.model.Account
import com.music.orb.data.model.YouTubeAccountIdentity

/**
 * Encrypted-at-rest account state for Orb.
 *
 * The primary app identity is now Google Sign in via Credential Manager. The
 * short-lived YouTube OAuth access token is cached here only so PlaybackService
 * can keep authenticated playback/history working while no Activity exists.
 * Google Play services remains the authority and silently refreshes that token
 * when needed.
 *
 * [cookie] is deliberately retained as a compatibility credential for the
 * private YouTube Music Innertube API. It is no longer the user's Orb login.
 * Existing installs can keep using it only when it belongs to the same Google
 * account selected for Orb; new installs normally never need it.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "bitchord_auth",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        Log.w("BitChord", "EncryptedSharedPreferences unavailable, falling back: ${it.message}")
        context.getSharedPreferences("bitchord_auth_plain", Context.MODE_PRIVATE)
    }

    /** Legacy YouTube Music browser session; compatibility fallback only. */
    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    var googleUniqueId: String?
        get() = prefs.getString(KEY_GOOGLE_UNIQUE_ID, null)
        private set(value) = prefs.edit().putString(KEY_GOOGLE_UNIQUE_ID, value).apply()

    var googleEmail: String?
        get() = prefs.getString(KEY_GOOGLE_EMAIL, null)
        private set(value) = prefs.edit().putString(KEY_GOOGLE_EMAIL, value).apply()

    var googleDisplayName: String?
        get() = prefs.getString(KEY_GOOGLE_DISPLAY_NAME, null)
        private set(value) = prefs.edit().putString(KEY_GOOGLE_DISPLAY_NAME, value).apply()

    var googleAvatarUrl: String?
        get() = prefs.getString(KEY_GOOGLE_AVATAR_URL, null)
        private set(value) = prefs.edit().putString(KEY_GOOGLE_AVATAR_URL, value).apply()

    /** Cached short-lived Google OAuth token for the YouTube scope. */
    var youtubeAccessToken: String?
        get() = prefs.getString(KEY_YOUTUBE_ACCESS_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_YOUTUBE_ACCESS_TOKEN, value).apply()

    var youtubeAccessTokenExpiresAtMs: Long
        get() = prefs.getLong(KEY_YOUTUBE_ACCESS_TOKEN_EXPIRES_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_YOUTUBE_ACCESS_TOKEN_EXPIRES_AT, value).apply()

    /** Delegated YouTube/Brand Account selected under the Google account. */
    var youtubePageId: String?
        get() = prefs.getString(KEY_YOUTUBE_PAGE_ID, null)
        private set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_YOUTUBE_PAGE_ID) else putString(KEY_YOUTUBE_PAGE_ID, value)
        }.apply()

    val hasYoutubeIdentitySelection: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_IDENTITY_SELECTED, false)

    var youtubeAuthUserIndex: Int
        get() = prefs.getInt(KEY_YOUTUBE_AUTH_USER_INDEX, 0).coerceAtLeast(0)
        private set(value) = prefs.edit().putInt(KEY_YOUTUBE_AUTH_USER_INDEX, value.coerceAtLeast(0)).apply()

    fun storedYoutubeAccount(): Account? {
        val name = prefs.getString(KEY_YOUTUBE_ACCOUNT_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return Account(
            name = name,
            email = prefs.getString(KEY_YOUTUBE_ACCOUNT_LABEL, "").orEmpty(),
            thumbnailUrl = prefs.getString(KEY_YOUTUBE_ACCOUNT_AVATAR, null),
            handle = prefs.getString(KEY_YOUTUBE_ACCOUNT_HANDLE, "").orEmpty(),
        )
    }

    fun storedYoutubeIdentity(): YouTubeAccountIdentity? {
        if (!hasYoutubeIdentitySelection) return null
        return YouTubeAccountIdentity(
            name = prefs.getString(KEY_YOUTUBE_IDENTITY_NAME, null).orEmpty(),
            handle = prefs.getString(KEY_YOUTUBE_IDENTITY_HANDLE, null).orEmpty(),
            thumbnailUrl = prefs.getString(KEY_YOUTUBE_IDENTITY_AVATAR, null),
            pageId = youtubePageId,
            isSelected = true,
        )
    }

    /** Orb sign-in is Google identity, never the presence of a browser cookie. */
    val isSignedIn: Boolean
        get() = !googleUniqueId.isNullOrBlank()

    val hasLegacyYouTubeSession: Boolean
        get() = cookie?.contains("SAPISID") == true

    fun saveGoogleIdentity(
        uniqueId: String,
        email: String?,
        displayName: String?,
        avatarUrl: String?,
    ) {
        prefs.edit()
            .putString(KEY_GOOGLE_UNIQUE_ID, uniqueId)
            .putString(KEY_GOOGLE_EMAIL, email)
            .putString(KEY_GOOGLE_DISPLAY_NAME, displayName)
            .putString(KEY_GOOGLE_AVATAR_URL, avatarUrl)
            .apply()
    }

    fun saveYoutubeAccessToken(token: String, expiresAtMs: Long) {
        prefs.edit()
            .putString(KEY_YOUTUBE_ACCESS_TOKEN, token)
            .putLong(KEY_YOUTUBE_ACCESS_TOKEN_EXPIRES_AT, expiresAtMs)
            .apply()
    }

    fun clearYoutubeAccessToken() {
        prefs.edit()
            .remove(KEY_YOUTUBE_ACCESS_TOKEN)
            .remove(KEY_YOUTUBE_ACCESS_TOKEN_EXPIRES_AT)
            .apply()
    }

    fun saveYoutubeAccount(account: Account) {
        prefs.edit()
            .putString(KEY_YOUTUBE_ACCOUNT_NAME, account.name)
            .putString(KEY_YOUTUBE_ACCOUNT_LABEL, account.email)
            .putString(KEY_YOUTUBE_ACCOUNT_AVATAR, account.thumbnailUrl)
            .putString(KEY_YOUTUBE_ACCOUNT_HANDLE, account.handle)
            .apply()
    }

    fun saveYoutubeIdentity(identity: YouTubeAccountIdentity) {
        prefs.edit().apply {
            putBoolean(KEY_YOUTUBE_IDENTITY_SELECTED, true)
            putString(KEY_YOUTUBE_IDENTITY_NAME, identity.name)
            putString(KEY_YOUTUBE_IDENTITY_HANDLE, identity.handle)
            putString(KEY_YOUTUBE_IDENTITY_AVATAR, identity.thumbnailUrl)
            if (identity.pageId.isNullOrBlank()) remove(KEY_YOUTUBE_PAGE_ID)
            else putString(KEY_YOUTUBE_PAGE_ID, identity.pageId)
        }.apply()
    }


    fun saveYoutubeRouting(pageId: String?, authUserIndex: Int) {
        prefs.edit().apply {
            putBoolean(KEY_YOUTUBE_IDENTITY_SELECTED, true)
            putInt(KEY_YOUTUBE_AUTH_USER_INDEX, authUserIndex.coerceAtLeast(0))
            if (pageId.isNullOrBlank()) remove(KEY_YOUTUBE_PAGE_ID)
            else putString(KEY_YOUTUBE_PAGE_ID, pageId)
        }.apply()
    }

    fun clearYoutubeIdentity() {
        prefs.edit()
            .remove(KEY_YOUTUBE_IDENTITY_SELECTED)
            .remove(KEY_YOUTUBE_IDENTITY_NAME)
            .remove(KEY_YOUTUBE_IDENTITY_HANDLE)
            .remove(KEY_YOUTUBE_IDENTITY_AVATAR)
            .remove(KEY_YOUTUBE_PAGE_ID)
            .remove(KEY_YOUTUBE_AUTH_USER_INDEX)
            .remove(KEY_YOUTUBE_ACCOUNT_NAME)
            .remove(KEY_YOUTUBE_ACCOUNT_LABEL)
            .remove(KEY_YOUTUBE_ACCOUNT_AVATAR)
            .remove(KEY_YOUTUBE_ACCOUNT_HANDLE)
            .apply()
    }

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /**
     * Clears the Orb/Google account and all YouTube credentials. Discord is a
     * separate integration and intentionally survives this action.
     */
    fun signOut() {
        prefs.edit()
            .remove(KEY_GOOGLE_UNIQUE_ID)
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_DISPLAY_NAME)
            .remove(KEY_GOOGLE_AVATAR_URL)
            .remove(KEY_YOUTUBE_ACCESS_TOKEN)
            .remove(KEY_YOUTUBE_ACCESS_TOKEN_EXPIRES_AT)
            .remove(KEY_YOUTUBE_IDENTITY_SELECTED)
            .remove(KEY_YOUTUBE_IDENTITY_NAME)
            .remove(KEY_YOUTUBE_IDENTITY_HANDLE)
            .remove(KEY_YOUTUBE_IDENTITY_AVATAR)
            .remove(KEY_YOUTUBE_PAGE_ID)
            .remove(KEY_YOUTUBE_AUTH_USER_INDEX)
            .remove(KEY_YOUTUBE_ACCOUNT_NAME)
            .remove(KEY_YOUTUBE_ACCOUNT_LABEL)
            .remove(KEY_YOUTUBE_ACCOUNT_AVATAR)
            .remove(KEY_COOKIE)
            .apply()
    }

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_GOOGLE_UNIQUE_ID = "google_unique_id"
        const val KEY_GOOGLE_EMAIL = "google_email"
        const val KEY_GOOGLE_DISPLAY_NAME = "google_display_name"
        const val KEY_GOOGLE_AVATAR_URL = "google_avatar_url"
        const val KEY_YOUTUBE_ACCESS_TOKEN = "youtube_access_token"
        const val KEY_YOUTUBE_ACCESS_TOKEN_EXPIRES_AT = "youtube_access_token_expires_at"
        const val KEY_YOUTUBE_IDENTITY_SELECTED = "youtube_identity_selected"
        const val KEY_YOUTUBE_IDENTITY_NAME = "youtube_identity_name"
        const val KEY_YOUTUBE_IDENTITY_HANDLE = "youtube_identity_handle"
        const val KEY_YOUTUBE_IDENTITY_AVATAR = "youtube_identity_avatar"
        const val KEY_YOUTUBE_PAGE_ID = "youtube_page_id"
        const val KEY_YOUTUBE_AUTH_USER_INDEX = "youtube_auth_user_index"
        const val KEY_YOUTUBE_ACCOUNT_NAME = "youtube_account_name"
        const val KEY_YOUTUBE_ACCOUNT_LABEL = "youtube_account_label"
        const val KEY_YOUTUBE_ACCOUNT_AVATAR = "youtube_account_avatar"
        const val KEY_YOUTUBE_ACCOUNT_HANDLE = "youtube_account_handle"
        const val KEY_DISCORD_TOKEN = "discord_token"
    }
}
