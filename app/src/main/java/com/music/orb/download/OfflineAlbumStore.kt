package com.music.orb.download

import android.content.Context
import com.music.orb.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Album-folder identity and authored track order; media files remain owned by Downloads. */
@Serializable
data class OfflineAlbum(
    val browseId: String,
    val title: String,
    val artworkUrl: String? = null,
    val trackIds: List<String>,
    val savedAtMs: Long,
)

object OfflineAlbumStore {
    private const val PREFS = "orb_offline_album_catalog"
    private const val KEY = "albums_v1"
    private val serializer = ListSerializer(OfflineAlbum.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableAlbums = MutableStateFlow<List<OfflineAlbum>>(emptyList())
    val albums: StateFlow<List<OfflineAlbum>> = mutableAlbums.asStateFlow()

    fun init(context: Context) {
        val serialized = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: "[]"
        mutableAlbums.value = runCatching { json.decodeFromString(serializer, serialized) }
            .getOrDefault(emptyList())
    }

    @Synchronized
    fun save(context: Context, browseId: String, title: String, artworkUrl: String?, songs: List<Song>) {
        val orderedIds = songs.map(Song::videoId).filter(String::isNotBlank)
        if (browseId.isBlank() || orderedIds.isEmpty()) return
        val updated = mutableAlbums.value.filterNot { it.browseId == browseId } + OfflineAlbum(
            browseId = browseId,
            title = title,
            artworkUrl = artworkUrl,
            trackIds = orderedIds,
            savedAtMs = System.currentTimeMillis(),
        )
        write(context, updated)
    }

    @Synchronized
    fun remove(context: Context, browseId: String) {
        write(context, mutableAlbums.value.filterNot { it.browseId == browseId })
    }

    @Synchronized
    fun clear(context: Context) {
        write(context, emptyList())
    }

    private fun write(context: Context, updated: List<OfflineAlbum>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.encodeToString(serializer, updated)).apply()
        mutableAlbums.value = updated
    }
}
