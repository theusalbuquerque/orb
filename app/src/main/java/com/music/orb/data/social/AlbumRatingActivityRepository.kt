package com.music.orb.data.social

import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
data class AlbumRatingActivity(
    @SerialName("user_id") val userId: String,
    @SerialName("album_id") val albumId: String,
    @SerialName("rating_tenths") val ratingTenths: Int,
    @SerialName("updated_at") val updatedAt: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

object AlbumRatingActivityRepository {
    suspend fun recent(albumIds: List<String>): List<AlbumRatingActivity> = withContext(Dispatchers.IO) {
        if (albumIds.isEmpty()) return@withContext emptyList()
        checkNotNull(SocialRepository.currentUserId())
        val parameters = buildJsonObject {
            putJsonArray("p_album_ids") {
                albumIds.distinct().forEach { add(JsonPrimitive(it)) }
            }
            put("p_limit", 30)
        }
        OrbSupabase.client.postgrest.rpc("orb_recent_album_ratings", parameters)
            .decodeList<AlbumRatingActivity>()
    }
}
