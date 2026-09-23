package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable positive cache for artist genre labels used by Stats and profile
 * compatibility. Provider outages must not make a previously-known genre
 * disappear on the next cold start, so empty lookups are never persisted.
 */
object ArtistGenreStore {
    private const val PREFS_NAME = "orb_artist_genres_v1"
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(artistKey: String): List<String> {
        if (!::prefs.isInitialized || artistKey.isBlank()) return emptyList()
        val raw = prefs.getString(artistKey, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .distinct()
    }

    @Synchronized
    fun save(artistKey: String, genres: List<String>) {
        if (!::prefs.isInitialized || artistKey.isBlank()) return
        val clean = genres.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return
        prefs.edit().putString(artistKey, json.encodeToString(clean)).apply()
    }
}
