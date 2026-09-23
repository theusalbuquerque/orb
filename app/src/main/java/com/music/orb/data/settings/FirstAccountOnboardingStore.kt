package com.music.orb.data.settings

import android.content.Context

/**
 * Remembers the two recommendations shown immediately after a user's first
 * explicit Google sign-in to Orb.
 *
 * State is scoped to the Supabase user id. A user who dismisses a recommendation
 * is not nagged on every launch, while an interrupted first-run flow can resume
 * after process death.
 */
object FirstAccountOnboardingStore {
    private const val PREFS_NAME = "orb_first_account_onboarding"
    private const val STARTED = "started"
    private const val YOUTUBE_HANDLED = "youtube_handled"
    private const val USERNAME_HANDLED = "username_handled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(prefix: String, userId: String): String = "$prefix:$userId"

    fun hasStarted(context: Context, userId: String): Boolean =
        prefs(context).getBoolean(key(STARTED, userId), false)

    fun markStarted(context: Context, userId: String) {
        prefs(context).edit().putBoolean(key(STARTED, userId), true).apply()
    }

    fun youtubeHandled(context: Context, userId: String): Boolean =
        prefs(context).getBoolean(key(YOUTUBE_HANDLED, userId), false)

    fun markYoutubeHandled(context: Context, userId: String) {
        prefs(context).edit().putBoolean(key(YOUTUBE_HANDLED, userId), true).apply()
    }

    fun usernameHandled(context: Context, userId: String): Boolean =
        prefs(context).getBoolean(key(USERNAME_HANDLED, userId), false)

    fun markUsernameHandled(context: Context, userId: String) {
        prefs(context).edit().putBoolean(key(USERNAME_HANDLED, userId), true).apply()
    }

    fun isComplete(context: Context, userId: String): Boolean =
        youtubeHandled(context, userId) && usernameHandled(context, userId)
}
