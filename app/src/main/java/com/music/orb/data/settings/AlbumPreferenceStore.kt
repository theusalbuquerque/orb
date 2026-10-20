package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import java.text.Normalizer
import java.util.Locale

/** Persistent account-scoped album playback exclusions. */
object AlbumPreferenceStore {
    private const val PREFS_NAME = "orb_album_preferences"
    private const val KEY_PREFIX = "albums_v1_"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(accountKey: String?): Set<String> {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return emptySet()
        return prefs.getStringSet(storageKey(accountKey), emptySet())
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
    }

    @Synchronized
    fun setBlocked(accountKey: String?, albumId: String, blocked: Boolean) {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return
        val key = albumKey(albumId)
        if (key.isBlank()) return
        val current = load(accountKey).toMutableSet()
        if (blocked) current += key else current -= key
        prefs.edit().putStringSet(storageKey(accountKey), current).apply()
    }

    fun albumKey(albumId: String): String = Normalizer.normalize(albumId, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)

    private fun storageKey(accountKey: String): String =
        KEY_PREFIX + Integer.toHexString(accountKey.lowercase(Locale.ROOT).hashCode())
}
