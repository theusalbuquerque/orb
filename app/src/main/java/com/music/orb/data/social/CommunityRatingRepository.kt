package com.music.orb.data.social

import com.music.orb.data.model.ArtistPage
import com.music.orb.data.model.BrowseType
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Public Orb release ratings used by catalogue/search summary cards. */
object CommunityRatingRepository {
    private data class AverageCacheEntry(val value: Float?, val storedAtMs: Long)
    private val averageCache = ConcurrentHashMap<String, AverageCacheEntry>()
    /** A public rating belongs to one release ID, not its other versions. */
    suspend fun albumAverage(albumId: String): Float? {
        if (albumId.isBlank()) return null
        val result = try {
            OrbSupabase.client.postgrest.rpc(
                "orb_community_album_rating",
                buildJsonObject { put("p_album_id", albumId) },
            ).decodeList<CommunityArtistRatingRow>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return averagesForAlbums(listOf(albumId))
        }
        return result.firstOrNull()?.score?.takeIf { it.isFinite() }?.toFloat()
    }

    /** One score per user per artist, with every rated release version included. */
    suspend fun artistAverage(artistName: String): Float? = withContext(Dispatchers.IO) {
        if (artistName.isBlank()) return@withContext null
        val result = try {
            OrbSupabase.client.postgrest.rpc(
                "orb_community_artist_rating",
                buildJsonObject { put("p_artist_name", artistName) },
            ).decodeList<CommunityArtistRatingRow>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext null
        }
        result.firstOrNull()?.score?.takeIf { it.isFinite() }?.toFloat()
    }

    /**
     * Weighted mean across individually rated release IDs; singles and EPs count.
     */
    suspend fun averagesForAlbums(albumIds: List<String>): Float? = withContext(Dispatchers.IO) {
        val ids = albumIds.asSequence().filter(String::isNotBlank).distinct().sorted().toList()
        if (ids.isEmpty()) return@withContext null
        val cacheKey = ids.joinToString("\u001F")
        val now = System.currentTimeMillis()
        averageCache[cacheKey]
            ?.takeIf { now - it.storedAtMs <= AVERAGE_CACHE_TTL_MS }
            ?.let { return@withContext it.value }

        val parameters = buildJsonObject {
            putJsonArray("p_album_ids") {
                ids.forEach { add(JsonPrimitive(it)) }
            }
        }
        val rows = runCatching {
            OrbSupabase.client.postgrest.rpc("orb_album_rating_averages", parameters)
                .decodeList<CommunityAlbumRatingAverageRow>()
        }.getOrElse { return@withContext null }

        var weightedTenths = 0.0
        var count = 0L
        rows.forEach { row ->
            val rowCount = row.ratingsCount.coerceAtLeast(0L)
            weightedTenths += row.averageTenths.coerceIn(0.0, 50.0) * rowCount.toDouble()
            count += rowCount
        }
        val result = if (count == 0L) null else (weightedTenths / count.toDouble() / 10.0).toFloat()
        averageCache[cacheKey] = AverageCacheEntry(result, now)
        result
    }

    /** Release IDs that form an artist score: albums + singles + EPs only. */
    fun artistReleaseIds(page: ArtistPage?): List<String> {
        if (page == null) return emptyList()
        return page.sections.asSequence()
            .filter { shelf -> normalized(shelf.title) in RELEASE_SHELF_ALIASES }
            .flatMap { it.items.asSequence() }
            .filter { it.type == BrowseType.ALBUM }
            .mapNotNull { it.browseId }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

    private fun normalized(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val RELEASE_SHELF_ALIASES = setOf(
        "albums", "albumes", "albuns",
        "singles", "single", "sencillos", "sencillo",
        "eps", "ep",
        "singles and eps", "singles e eps", "singles y eps",
        "albums and singles", "albuns e singles", "albumes y singles",
    )

    // Just long enough to bridge ViewModel prewarming -> first composition.
    // This is not a long-lived rating cache, so fresh user ratings remain fresh.
    private const val AVERAGE_CACHE_TTL_MS = 5_000L
}

@Serializable
private data class CommunityAlbumRatingAverageRow(
    @SerialName("album_id") val albumId: String,
    @SerialName("average_tenths") val averageTenths: Double,
    @SerialName("ratings_count") val ratingsCount: Long,
)

@Serializable
private data class CommunityArtistRatingRow(
    val score: Double? = null,
    @SerialName("ratings_count") val ratingsCount: Long = 0L,
)
