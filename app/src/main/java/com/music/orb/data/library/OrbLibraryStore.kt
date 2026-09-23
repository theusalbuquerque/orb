package com.music.orb.data.library

import android.content.Context
import com.music.orb.R
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.LibraryPage
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orb's device-local music library.
 *
 * This is deliberately separate from downloads and from the signed-in YouTube
 * Music account. Choosing the Orb library in Settings changes only where
 * "Add to library" reads/writes after the user is authenticated; catalogue,
 * playback and account features keep working normally. The data survives
 * process death/app restarts in ordinary SharedPreferences and is small metadata
 * only — audio remains in AudioCache.
 */
object OrbLibraryStore {
    private const val PREFS = "orb_own_library"
    private const val KEY_SONGS = "songs"
    private const val KEY_COLLECTIONS = "collections"

    private val lock = Any()

    fun containsSong(context: Context, videoId: String): Boolean = synchronized(lock) {
        readSongs(context).any { it.videoId == videoId }
    }

    fun setSong(context: Context, song: Song, saved: Boolean): Boolean = synchronized(lock) {
        val songs = readSongs(context).toMutableList()
        val index = songs.indexOfFirst { it.videoId == song.videoId }
        if (saved) {
            if (index >= 0) songs[index] = song else songs.add(0, song)
        } else if (index >= 0) {
            songs.removeAt(index)
        }
        writeSongs(context, songs)
        saved
    }

    fun containsCollection(context: Context, browseId: String): Boolean = synchronized(lock) {
        readCollections(context).any { it.browseId == browseId }
    }

    fun setCollection(context: Context, item: ShelfItem, saved: Boolean): Boolean = synchronized(lock) {
        val id = item.browseId ?: return@synchronized false
        val collections = readCollections(context).toMutableList()
        val index = collections.indexOfFirst { it.browseId == id }
        if (saved) {
            if (index >= 0) collections[index] = item else collections.add(0, item)
        } else if (index >= 0) {
            collections.removeAt(index)
        }
        writeCollections(context, collections)
        saved
    }

    fun page(context: Context): LibraryPage = synchronized(lock) {
        val songs = readSongs(context)
        val collections = readCollections(context)
        val shelves = buildList {
            if (songs.isNotEmpty()) {
                add(
                    HomeShelf(
                        title = context.getString(R.string.local_tab_songs),
                        items = songs.map { song ->
                            ShelfItem(
                                title = song.title,
                                subtitle = song.artist,
                                thumbnailUrl = song.thumbnailUrl,
                                videoId = song.videoId,
                                browseId = null,
                                isExplicit = song.isExplicit,
                                type = BrowseType.OTHER,
                            )
                        },
                    ),
                )
            }

            collections.filter { it.type == BrowseType.ALBUM }.takeIf { it.isNotEmpty() }?.let {
                add(HomeShelf(context.getString(R.string.local_tab_albums), it))
            }
            collections.filter { it.type == BrowseType.PLAYLIST }.takeIf { it.isNotEmpty() }?.let {
                add(HomeShelf(context.getString(R.string.beta_ui_playlists), it))
            }
        }
        LibraryPage(
            likedSongs = emptyList(),
            librarySongs = songs,
            shelves = shelves,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSongs(context: Context): List<Song> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_SONGS, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                add(
                    Song(
                        videoId = id,
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        thumbnailUrl = obj.nullableString("thumbnailUrl"),
                        durationText = obj.nullableString("durationText"),
                        artistId = obj.nullableString("artistId"),
                        albumId = obj.nullableString("albumId"),
                        albumName = obj.nullableString("albumName"),
                        isVideo = obj.optBoolean("isVideo", false),
                        isExplicit = obj.optBoolean("isExplicit", false),
                        releaseYear = obj.optInt("releaseYear", 0).takeIf { it > 0 },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeSongs(context: Context, songs: List<Song>) {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(
                JSONObject().apply {
                    put("videoId", song.videoId)
                    put("title", song.title)
                    put("artist", song.artist)
                    putNullable("thumbnailUrl", song.thumbnailUrl)
                    putNullable("durationText", song.durationText)
                    putNullable("artistId", song.artistId)
                    putNullable("albumId", song.albumId)
                    putNullable("albumName", song.albumName)
                    put("isVideo", song.isVideo)
                    put("isExplicit", song.isExplicit)
                    song.releaseYear?.let { put("releaseYear", it) }
                },
            )
        }
        prefs(context).edit().putString(KEY_SONGS, array.toString()).apply()
    }

    private fun readCollections(context: Context): List<ShelfItem> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_COLLECTIONS, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val browseId = obj.optString("browseId").takeIf { it.isNotBlank() } ?: continue
                val type = runCatching {
                    BrowseType.valueOf(obj.optString("type", BrowseType.ALBUM.name))
                }.getOrDefault(BrowseType.ALBUM)
                if (type != BrowseType.ALBUM && type != BrowseType.PLAYLIST) continue
                add(
                    ShelfItem(
                        title = obj.optString("title"),
                        subtitle = obj.optString("subtitle"),
                        thumbnailUrl = obj.nullableString("thumbnailUrl"),
                        videoId = null,
                        browseId = browseId,
                        isExplicit = obj.optBoolean("isExplicit", false),
                        type = type,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeCollections(context: Context, collections: List<ShelfItem>) {
        val array = JSONArray()
        collections.forEach { item ->
            val browseId = item.browseId ?: return@forEach
            array.put(
                JSONObject().apply {
                    put("browseId", browseId)
                    put("title", item.title)
                    put("subtitle", item.subtitle)
                    putNullable("thumbnailUrl", item.thumbnailUrl)
                    put("isExplicit", item.isExplicit)
                    put("type", item.type.name)
                },
            )
        }
        prefs(context).edit().putString(KEY_COLLECTIONS, array.toString()).apply()
    }

    private fun JSONObject.nullableString(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }
}
