package com.music.orb.data.social

import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Community-only aggregates; no per-user information is returned. */
@Serializable
data class CommunityRankEntry(
    val category: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val plays: Long = 0L,
    val listeners: Long = 0L,
    val score: Double? = null,
    @SerialName("ratings_count") val ratingsCount: Long = 0L,
)

object CommunityStatsRepository {
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision = _catalogRevision.asStateFlow()
    suspend fun rankings(days: Int): List<CommunityRankEntry> = withContext(Dispatchers.IO) {
        OrbSupabase.client.postgrest.rpc(
            "orb_stats_global_rankings",
            buildJsonObject { put("p_days", days.coerceIn(1, 365)) },
        ).decodeList<CommunityRankEntry>()
    }

    /** Previous matching window, with zero counts for tracks not previously heard. Requires the v8 SQL. */
    suspend fun previousRankings(days: Int): List<CommunityRankEntry> = withContext(Dispatchers.IO) {
        OrbSupabase.client.postgrest.rpc(
            "orb_stats_previous_rankings",
            buildJsonObject { put("p_days", days.coerceIn(1, 365)) },
        ).decodeList<CommunityRankEntry>()
    }

    @Serializable
    private data class MissingRatedAlbum(@SerialName("album_id") val albumId: String)

    /** Only IDs rated by the signed-in account. No other listener's ratings are exposed. */
    suspend fun missingRatedAlbumIds(): List<String> = withContext(Dispatchers.IO) {
        OrbSupabase.client.postgrest.rpc("orb_stats_missing_rated_albums")
            .decodeList<MissingRatedAlbum>()
            .map { it.albumId }
    }

    /** Best-effort catalogue registration; never block or roll back saving a personal rating. */
    fun announceCatalogueChanged() { _catalogRevision.value += 1 }

    suspend fun registerRatedAlbum(
        albumId: String,
        title: String,
        artist: String,
        artwork: String?,
        announceChange: Boolean = true,
    ) {
        if (albumId.isBlank() || title.isBlank() || artist.isBlank()) return
        withContext(Dispatchers.IO) {
            OrbSupabase.client.postgrest.rpc(
                "orb_stats_register_rated_album",
                buildJsonObject {
                    put("p_album_id", albumId)
                    put("p_album_title", title)
                    put("p_artist_name", artist)
                    put("p_artwork_url", artwork)
                },
            )
            if (announceChange) announceCatalogueChanged()
        }
    }
}
