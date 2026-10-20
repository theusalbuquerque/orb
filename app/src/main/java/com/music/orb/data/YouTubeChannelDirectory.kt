package com.music.orb.data

import com.music.orb.data.model.YouTubeAccountIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Public YouTube Data API, separate from the private YouTube Music Innertube.
 * A Google OAuth grant can list its authorized YouTube channel, but this does
 * not imply permission to read the user's private YouTube Music library.
 */
internal object YouTubeChannelDirectory {
    private const val ENDPOINT =
        "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true&maxResults=50"

    suspend fun mine(accessToken: String): Result<List<YouTubeAccountIdentity>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(accessToken.isNotBlank()) { "Missing YouTube authorization." }
                val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    connection.setRequestProperty("Accept", "application/json")
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        // Do not log error bodies: they may contain account info.
                        error("YouTube channel authorization failed (HTTP $status).")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parse(body)
                } finally {
                    connection.disconnect()
                }
            }
        }

    /** Pure parser so channel discovery can be tested without a Google account. */
    internal fun parse(payload: String): List<YouTubeAccountIdentity> {
        val root = Json.parseToJsonElement(payload).jsonObject
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { raw ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            if (entry.string("id")?.startsWith("UC") != true) return@mapNotNull null
            val snippet = entry["snippet"] as? JsonObject ?: return@mapNotNull null
            val title = snippet.string("title")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val handle = snippet.string("customUrl").orEmpty()
                .takeIf { it.startsWith("@") }.orEmpty()
            val images = snippet["thumbnails"] as? JsonObject
            val thumbnail = listOf("maxres", "high", "medium", "default")
                .firstNotNullOfOrNull { size ->
                    (images?.get(size) as? JsonObject)?.string("url")?.takeIf(String::isNotBlank)
                }
            // Channel IDs and Brand delegation IDs are different. Never write
            // a channels.list ID into Innertube's X-Goog-PageId field.
            YouTubeAccountIdentity(
                name = title,
                handle = handle,
                thumbnailUrl = thumbnail,
                pageId = null,
                isSelected = true,
                fromOfficialApi = true,
            )
        }.distinctBy { it.stableKey }
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.content
}
