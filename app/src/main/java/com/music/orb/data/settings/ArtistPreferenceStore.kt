package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

enum class ArtistPreference {
    NEUTRAL,
    LIKE,
    BLOCK,
}

/**
 * Small account-scoped artist preference store used by the artist page.
 *
 * BLOCK is a playback rule, not just a visual affordance: callers use it to
 * remove the artist from personalised recommendations and future queue items.
 * LIKE is persisted as an explicit positive taste signal for Orb without
 * pretending it is a YouTube Music subscription/follow action.
 */
object ArtistPreferenceStore {
    private const val PREFS_NAME = "orb_artist_preferences"
    private const val KEY_PREFIX = "artists_v1_"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(accountKey: String?): Map<String, ArtistPreference> {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return emptyMap()
        val raw = prefs.getString(storageKey(accountKey), null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val artistKey = keys.next()
                    val value = runCatching {
                        ArtistPreference.valueOf(json.optString(artistKey))
                    }.getOrNull() ?: continue
                    if (artistKey.isNotBlank() && value != ArtistPreference.NEUTRAL) {
                        put(artistKey, value)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun set(accountKey: String?, artistName: String, preference: ArtistPreference) {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return
        val key = artistKey(artistName)
        if (key.isBlank()) return
        val current = load(accountKey).toMutableMap()
        if (preference == ArtistPreference.NEUTRAL) current.remove(key)
        else current[key] = preference
        persist(accountKey, current)
    }

    fun artistKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun persist(accountKey: String, values: Map<String, ArtistPreference>) {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value.name) }
        prefs.edit().putString(storageKey(accountKey), json.toString()).apply()
    }

    private fun storageKey(accountKey: String): String =
        KEY_PREFIX + Integer.toHexString(accountKey.lowercase(Locale.ROOT).hashCode())
}
