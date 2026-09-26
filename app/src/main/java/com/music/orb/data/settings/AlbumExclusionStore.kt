package com.music.orb.data.settings

import android.content.Context
import com.music.orb.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account-scoped "do not play this album" state.
 *
 * The exclusion is deliberately stored by the exact album browse id and the
 * concrete track ids that belonged to that release when the user blocked it.
 * Album title matching is intentionally never used: deluxe, remix, remaster
 * and other editions are separate releases and must remain playable.
 */
object AlbumExclusionStore {
    private const val PREFS = "orb_album_exclusions"
    private const val KEY_PREFIX = "albums_v1_"
    private const val MAX_ALBUMS = 500

    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun block(
        accountKey: String,
        albumBrowseId: String,
        songs: List<Song>,
    ) {
        val key = accountKey(accountKey)
        if (key.isBlank() || albumBrowseId.isBlank()) return
        val current = read(key)
        current[albumBrowseId] = songs.mapNotNull { it.videoId.takeIf(String::isNotBlank) }
            .toSet()
        write(key, current)
    }

    @Synchronized
    fun unblock(
        accountKey: String,
        albumBrowseId: String,
    ) {
        val key = accountKey(accountKey)
        if (key.isBlank() || albumBrowseId.isBlank()) return
        val current = read(key)
        if (current.remove(albumBrowseId) != null) write(key, current)
    }

    @Synchronized
    fun isBlocked(
        accountKey: String,
        song: Song,
    ): Boolean {
        val videoId = song.videoId.takeIf(String::isNotBlank) ?: return false
        return read(accountKey(accountKey)).values.any { videoId in it }
    }

    @Synchronized
    fun blockedAlbumIds(accountKey: String): Set<String> =
        read(accountKey(accountKey)).keys.toSet()

    private fun accountKey(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._@-]+"), "_")

    private fun read(key: String): MutableMap<String, Set<String>> {
        val prefs = preferences ?: return linkedMapOf()
        val raw = prefs.getString(KEY_PREFIX + key, null).orEmpty()
        if (raw.isBlank()) return linkedMapOf()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return linkedMapOf()
        val result = linkedMapOf<String, Set<String>>()
        root.keys().forEach { albumId ->
            val array = root.optJSONArray(albumId) ?: return@forEach
            val tracks = buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            if (tracks.isNotEmpty()) result[albumId] = tracks
        }
        return result
    }

    private fun write(
        key: String,
        values: Map<String, Set<String>>,
    ) {
        val prefs = preferences ?: return
        val root = JSONObject()
        values.entries
            .takeLast(MAX_ALBUMS)
            .forEach { (albumId, tracks) ->
                root.put(albumId, JSONArray(tracks.toList()))
            }
        prefs.edit().putString(KEY_PREFIX + key, root.toString()).apply()
    }
}
