package com.music.orb.data.settings

import android.content.Context
import com.music.orb.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small device-local source of truth for the Home "Recently played" shelf.
 *
 * It intentionally does not depend on YouTube history: local files, downloaded
 * tracks and offline playback are just as valid as an online YouTube listen.
 * The playback reporter is responsible for recording only after the listener
 * has heard at least 30% of the track.
 */
object RecentPlaybackStore {
    private const val PREFS = "orb_recent_playback"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 200

    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun record(
        song: Song,
        playedAtMs: Long = System.currentTimeMillis(),
        startedAtMs: Long = playedAtMs,
    ) {
        val prefs = preferences ?: return
        val current = read(prefs).toMutableList()
        // Every qualified playback is an event of its own. Listening to the
        // same track twice must therefore occupy two positions in Recents.
        current.add(0, RecentPlaybackEntry(song, playedAtMs, startedAtMs))
        val result = JSONArray()
        current.take(MAX_ITEMS).forEach { result.put(toJson(it)) }
        prefs.edit().putString(KEY_ITEMS, result.toString()).apply()
    }

    @Synchronized
    fun snapshot(): List<Song> {
        val prefs = preferences ?: return emptyList()
        return read(prefs).take(MAX_ITEMS).map { it.song }
    }

    @Synchronized
    fun snapshotEntries(): List<RecentPlaybackEntry> {
        val prefs = preferences ?: return emptyList()
        return read(prefs).take(MAX_ITEMS)
    }

    private fun read(prefs: android.content.SharedPreferences): List<RecentPlaybackEntry> {
        val raw = prefs.getString(KEY_ITEMS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val song = songFromJson(item) ?: continue
                val playedAt = item.optLong("playedAt", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val startedAt = item.optLong("startedAt", 0L).takeIf { it > 0L }
                    ?: playedAt
                add(RecentPlaybackEntry(song, playedAt, startedAt))
            }
        }
    }

    private fun toJson(entry: RecentPlaybackEntry): JSONObject = JSONObject().apply {
        val song = entry.song
        put("playedAt", entry.playedAtMs)
        put("startedAt", entry.startedAtMs)
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        putNullable("thumbnailUrl", song.thumbnailUrl)
        putNullable("durationText", song.durationText)
        putNullable("artistId", song.artistId)
        putNullable("albumId", song.albumId)
        putNullable("albumName", song.albumName)
        put("isVideo", song.isVideo)
        putNullable("setVideoId", song.setVideoId)
        put("fromAutoplay", song.fromAutoplay)
        putNullable("localUri", song.localUri)
        putNullable("localPath", song.localPath)
        putNullable("sourceQuality", song.sourceQuality)
        put("queuePinned", song.queuePinned)
        put("isExplicit", song.isExplicit)
        putNullable("releaseYear", song.releaseYear)
        put("sourceExplicitKnown", song.sourceExplicitKnown)
        putNullable("sourcePlaylistId", song.sourcePlaylistId)
        putNullable("sourcePlaylistTitle", song.sourcePlaylistTitle)
        putNullable("sourcePlaylistArtworkUrl", song.sourcePlaylistArtworkUrl)
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun songFromJson(item: JSONObject): Song? {
        val videoId = item.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = item.optString("title").takeIf { it.isNotBlank() } ?: return null
        return Song(
            videoId = videoId,
            title = title,
            artist = item.optString("artist"),
            thumbnailUrl = item.optNullableString("thumbnailUrl"),
            durationText = item.optNullableString("durationText"),
            artistId = item.optNullableString("artistId"),
            albumId = item.optNullableString("albumId"),
            albumName = item.optNullableString("albumName"),
            isVideo = item.optBoolean("isVideo", false),
            setVideoId = item.optNullableString("setVideoId"),
            fromAutoplay = item.optBoolean("fromAutoplay", false),
            localUri = item.optNullableString("localUri"),
            localPath = item.optNullableString("localPath"),
            sourceQuality = item.optNullableString("sourceQuality"),
            queuePinned = item.optBoolean("queuePinned", false),
            isExplicit = item.optBoolean("isExplicit", false),
            releaseYear = if (item.isNull("releaseYear")) null else item.optInt("releaseYear"),
            sourceExplicitKnown = item.optBoolean("sourceExplicitKnown", true),
            sourcePlaylistId = item.optNullableString("sourcePlaylistId"),
            sourcePlaylistTitle = item.optNullableString("sourcePlaylistTitle"),
            sourcePlaylistArtworkUrl = item.optNullableString("sourcePlaylistArtworkUrl"),
        )
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

}

data class RecentPlaybackEntry(
    val song: Song,
    val playedAtMs: Long,
    val startedAtMs: Long,
) {
    fun sessionKey(): String {
        val mediaKey = song.localUri?.takeIf { it.isNotBlank() } ?: song.videoId
        return "$mediaKey|$startedAtMs"
    }
}
