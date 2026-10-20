package com.music.orb.auth

/** A private Music API failure must not revoke a valid Google OAuth grant. */
internal object YouTubeAuthorizationPolicy {
    const val PRIVATE_MUSIC_RETRY_MS: Long = 10L * 60L * 1000L

    // HTTP 400 can indicate a malformed browse payload, not authentication.
    fun isPrivateOAuthRejection(status: Int): Boolean = status == 401 || status == 403

    fun nextPrivateRetryAt(nowMs: Long): Long =
        if (nowMs >= Long.MAX_VALUE - PRIVATE_MUSIC_RETRY_MS) Long.MAX_VALUE
        else nowMs + PRIVATE_MUSIC_RETRY_MS
}
