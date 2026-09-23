package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, account-scoped history for the Stats artist ranking.
 *
 * The ranking is intentionally snapshot based: positions only advance when the
 * listener opens the ranking page. Keeping this state outside the ViewModel
 * prevents every process restart from making long-standing artists look "New".
 */
data class ArtistRankingHistoryEntry(
    val artistName: String,
    val position: Int,
    val peakPosition: Int,
    val movement: String,
    val movementAmount: Int = 0,
    val movementSinceMs: Long = 0L,
    val listenedMs: Long = 0L,
    val plays: Int = 0,
    val active: Boolean = true,
)

data class ArtistRankingHistorySnapshot(
    val seeded: Boolean = false,
    val seenArtists: Set<String> = emptySet(),
    val periods: Map<String, Map<String, ArtistRankingHistoryEntry>> = emptyMap(),
)

object ArtistRankingHistoryStore {
    private const val PREFS_NAME = "orb_artist_ranking_history"
    private const val KEY_PREFIX = "history_v1_"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(userId: String): ArtistRankingHistorySnapshot {
        if (!::prefs.isInitialized || userId.isBlank()) return ArtistRankingHistorySnapshot()
        val raw = prefs.getString(key(userId), null) ?: return ArtistRankingHistorySnapshot()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(ArtistRankingHistorySnapshot())
    }

    fun save(userId: String, snapshot: ArtistRankingHistorySnapshot) {
        if (!::prefs.isInitialized || userId.isBlank()) return
        prefs.edit().putString(key(userId), encode(snapshot).toString()).apply()
    }

    private fun key(userId: String): String = KEY_PREFIX + userId

    private fun encode(snapshot: ArtistRankingHistorySnapshot): JSONObject = JSONObject().apply {
        put("seeded", snapshot.seeded)
        put("seen", JSONArray().apply { snapshot.seenArtists.sorted().forEach { artistKey -> put(artistKey) } })
        put("periods", JSONObject().apply {
            snapshot.periods.forEach { (period, entries) ->
                put(period, JSONObject().apply {
                    entries.forEach { (artistKey, entry) ->
                        put(artistKey, JSONObject().apply {
                            put("artistName", entry.artistName)
                            put("position", entry.position)
                            put("peakPosition", entry.peakPosition)
                            put("movement", entry.movement)
                            put("movementAmount", entry.movementAmount)
                            put("movementSinceMs", entry.movementSinceMs)
                            put("listenedMs", entry.listenedMs)
                            put("plays", entry.plays)
                            put("active", entry.active)
                        })
                    }
                })
            }
        })
    }

    private fun decode(root: JSONObject): ArtistRankingHistorySnapshot {
        val seen = linkedSetOf<String>()
        root.optJSONArray("seen")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(seen::add)
            }
        }

        val periods = linkedMapOf<String, Map<String, ArtistRankingHistoryEntry>>()
        root.optJSONObject("periods")?.let { periodsJson ->
            val periodKeys = periodsJson.keys()
            while (periodKeys.hasNext()) {
                val period = periodKeys.next()
                val entriesJson = periodsJson.optJSONObject(period) ?: continue
                val entries = linkedMapOf<String, ArtistRankingHistoryEntry>()
                val entryKeys = entriesJson.keys()
                while (entryKeys.hasNext()) {
                    val artistKey = entryKeys.next()
                    val json = entriesJson.optJSONObject(artistKey) ?: continue
                    val position = json.optInt("position", 0)
                    if (artistKey.isBlank() || position <= 0) continue
                    entries[artistKey] = ArtistRankingHistoryEntry(
                        artistName = json.optString("artistName").ifBlank { artistKey },
                        position = position,
                        peakPosition = json.optInt("peakPosition", position).coerceAtLeast(1),
                        movement = json.optString("movement", "SAME"),
                        movementAmount = json.optInt("movementAmount", 0).coerceAtLeast(0),
                        movementSinceMs = json.optLong("movementSinceMs", 0L).coerceAtLeast(0L),
                        listenedMs = json.optLong("listenedMs", 0L).coerceAtLeast(0L),
                        plays = json.optInt("plays", 0).coerceAtLeast(0),
                        active = json.optBoolean("active", true),
                    )
                }
                periods[period] = entries
            }
        }

        return ArtistRankingHistorySnapshot(
            seeded = root.optBoolean("seeded", false),
            seenArtists = seen,
            periods = periods,
        )
    }
}
