package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class PlaylistListeningEvent(
    val playlistId: String,
    val title: String,
    val artworkUrl: String? = null,
    val listenedMs: Long,
    val finishedAtMs: Long,
)

/**
 * Migration fallback for playlist attribution collected before Stats started
 * persisting playlist metadata in Supabase listening_activity. New plays stop
 * entering this store once the server accepts the Stats columns, while old local
 * events remain readable so an update does not erase the user's earlier totals.
 */
object PlaylistListeningStore {
    private const val KEY = "stats_playlist_listening_events_v1"
    private const val MAX_EVENTS = 10_000
    private const val RETENTION_MS = 370L * 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences
    private var events: List<PlaylistListeningEvent> = emptyList()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        events = decode(prefs.getString(KEY, null)).let(::pruned)
    }

    @Synchronized
    fun record(
        playlistId: String?,
        title: String?,
        artworkUrl: String?,
        listenedMs: Long,
        finishedAtMs: Long,
    ) {
        val id = playlistId?.takeIf { it.isNotBlank() } ?: return
        val name = title?.takeIf { it.isNotBlank() } ?: return
        val event = PlaylistListeningEvent(
            playlistId = id,
            title = name,
            artworkUrl = artworkUrl,
            listenedMs = listenedMs.coerceAtLeast(0L),
            finishedAtMs = finishedAtMs.coerceAtLeast(0L),
        )
        events = pruned((events + event).takeLast(MAX_EVENTS))
        prefs.edit().putString(KEY, json.encodeToString(events)).apply()
    }

    @Synchronized
    fun eventsSince(epochMs: Long): List<PlaylistListeningEvent> =
        events.filter { it.finishedAtMs >= epochMs }

    private fun decode(raw: String?): List<PlaylistListeningEvent> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<PlaylistListeningEvent>>(raw) }
            .getOrDefault(emptyList())

    private fun pruned(source: List<PlaylistListeningEvent>): List<PlaylistListeningEvent> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return source.filter { it.finishedAtMs >= cutoff }.takeLast(MAX_EVENTS)
    }
}
