package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedStatsLeader(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val sourceId: String? = null,
    val listenedMs: Long = 0L,
    val plays: Int = 0,
    val kind: String = "UNKNOWN",
)

@Serializable
data class CachedListeningStats(
    val savedAtMs: Long = System.currentTimeMillis(),
    val totalListenedMs: Long = 0L,
    val trackCount: Int = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
    val activeDays: Int = 0,
    val peakDate: String? = null,
    val peakDayOfWeek: Int? = null,
    val peakHour: Int? = null,
    val averageTrackMs: Long = 0L,
    val comparisonPercent: Int? = null,
    val topGenre: String? = null,
    val topGenres: List<String> = emptyList(),
    val topLanguage: String? = null,
    val topLanguages: List<String> = emptyList(),
    val newFollowersCount: Int = 0,
    val topArtist: CachedStatsLeader? = null,
    val topSong: CachedStatsLeader? = null,
    val topAlbum: CachedStatsLeader? = null,
    val topPlaylist: CachedStatsLeader? = null,
    val rhythmSeries: List<Int> = emptyList(),
    val previousRhythmSeries: List<Int> = emptyList(),
)

/** Compact cold-start cache for the five Stats periods. */
object StatsSnapshotStore {
    private const val PREFS_NAME = "orb_stats_snapshots"
    private const val KEY_PREFIX = "stats_v1_"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(userId: String): Map<String, CachedListeningStats> {
        if (!::prefs.isInitialized || userId.isBlank()) return emptyMap()
        val raw = prefs.getString(key(userId), null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, CachedListeningStats>>(raw) }
            .getOrDefault(emptyMap())
    }

    @Synchronized
    fun save(userId: String, period: String, snapshot: CachedListeningStats) {
        if (!::prefs.isInitialized || userId.isBlank() || period.isBlank()) return
        val current = load(userId).toMutableMap()
        current[period] = snapshot.copy(savedAtMs = System.currentTimeMillis())
        prefs.edit().putString(key(userId), json.encodeToString(current)).apply()
    }

    private fun key(userId: String): String = KEY_PREFIX + userId
}
