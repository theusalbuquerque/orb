package com.music.orb.data.settings

import android.content.Context
import com.music.orb.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

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
    private const val RECORDING_PREFIX = "album_recordings_v1_"
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
        val videoIds = songs.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toSet()
        if (videoIds.isNotEmpty() || albumBrowseId !in current) {
            current[albumBrowseId] = videoIds
        }
        write(key, current)

        // Recommendations do not always carry an album browse id (Quick Picks,
        // AutoPlay and some continuation rows are common examples). Preserve a
        // lightweight recording identity for this exact blocked release so those
        // rows cannot immediately re-enter suggestions under another carrier id.
        val recordings = readRecordings(key)
        val recordingKeys = songs.mapNotNull(::recordingKey).toSet()
        if (recordingKeys.isNotEmpty() || albumBrowseId !in recordings) {
            recordings[albumBrowseId] = recordingKeys
        }
        writeRecordings(key, recordings)
        AccountSettingsSync.notifyAccountPreferenceChanged()
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

        val recordings = readRecordings(key)
        if (recordings.remove(albumBrowseId) != null) writeRecordings(key, recordings)
        AccountSettingsSync.notifyAccountPreferenceChanged()
    }

    @Synchronized
    fun isBlocked(
        accountKey: String,
        song: Song,
    ): Boolean {
        val blocked = read(accountKey(accountKey))

        // Exact album identity is authoritative whenever the row carries it.
        // The page stamps its browse id onto every song before any transport
        // action, so blocking works immediately even if the page's track list
        // was still loading when the user tapped the button.
        song.albumId
            ?.takeIf(String::isNotBlank)
            ?.let { return it in blocked }

        // Keep concrete video ids as a fallback for surfaces that do not carry
        // albumId (older cached rows, AutoPlay, lightweight recommendations).
        val videoId = song.videoId.takeIf(String::isNotBlank)
        if (videoId != null && blocked.values.any { videoId in it }) return true

        // If the catalogue row *does* identify a different album, preserve the
        // exact-release rule: blocking Bad must not automatically block HIStory.
        // Only metadata-light rows without album identity fall back to the
        // recording fingerprint captured from the blocked release.
        if (!song.albumId.isNullOrBlank()) return false
        val recording = recordingKey(song) ?: return false
        return readRecordings(accountKey(accountKey)).values.any { recording in it }
    }

    @Synchronized
    fun isAlbumBlocked(accountKey: String, albumBrowseId: String?): Boolean {
        val id = albumBrowseId?.takeIf(String::isNotBlank) ?: return false
        return id in read(accountKey(accountKey))
    }

    @Synchronized
    fun blockedAlbumIds(accountKey: String): Set<String> =
        read(accountKey(accountKey)).keys.toSet()

    @Synchronized
    fun accountSyncSnapshot(accountKey: String): Map<String, List<String>> =
        read(accountKey(accountKey)).mapValues { it.value.toList() }

    @Synchronized
    fun accountRecordingSyncSnapshot(accountKey: String): Map<String, List<String>> =
        readRecordings(accountKey(accountKey)).mapValues { it.value.toList() }

    @Synchronized
    fun applyAccountSyncSnapshot(
        accountKey: String,
        albums: Map<String, List<String>>,
        recordings: Map<String, List<String>>,
    ) {
        val key = accountKey(accountKey)
        if (key.isBlank()) return
        write(key, albums.mapValues { it.value.toSet() })
        writeRecordings(key, recordings.mapValues { it.value.toSet() })
    }

    private fun accountKey(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._@-]+"), "_")

    private fun recordingKey(song: Song): String? {
        val title = normalize(song.title).takeIf(String::isNotBlank) ?: return null
        val artist = normalize(song.artist).takeIf(String::isNotBlank) ?: return null
        return "$title|$artist"
    }

    private fun normalize(raw: String): String = Normalizer
        .normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun readRecordings(key: String): MutableMap<String, Set<String>> {
        val prefs = preferences ?: return linkedMapOf()
        val raw = prefs.getString(RECORDING_PREFIX + key, null).orEmpty()
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
            result[albumId] = tracks
        }
        return result
    }

    private fun writeRecordings(
        key: String,
        values: Map<String, Set<String>>,
    ) {
        val prefs = preferences ?: return
        val root = JSONObject()
        values.entries
            .toList()
            .takeLast(MAX_ALBUMS)
            .forEach { entry ->
                root.put(entry.key, JSONArray(entry.value.toList()))
            }
        prefs.edit().putString(RECORDING_PREFIX + key, root.toString()).apply()
    }

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
            // An empty array is still a real block: it means the album was
            // blocked before its rows finished loading. allSongs() can enrich
            // the id set later, but the browse id must be effective instantly.
            result[albumId] = tracks
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
            .toList()
            .takeLast(MAX_ALBUMS)
            .forEach { entry ->
                root.put(entry.key, JSONArray(entry.value.toList()))
            }
        prefs.edit().putString(KEY_PREFIX + key, root.toString()).apply()
    }
}
