package com.music.orb.data.canvas

import com.music.orb.data.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Exact-name artist portraits from Spotify.
 *
 * Spotify's documented catalog search requires OAuth. The public web player
 * obtains a short-lived anonymous transport token for signed-out visitors;
 * using that token here avoids embedding a client secret in the APK. Every
 * result is still name-validated and any failure falls through to the next
 * provider instead of delaying the Stats page.
 */
object SpotifyArtistArtwork {
    private const val TOKEN_URL =
        "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val RETRY_DELAY_MS = 30L * 60 * 1000

    private var cachedToken: String? = null
    private var tokenExpiresAtMs = 0L
    private var retryTokenAfterMs = 0L
    private val client = Http.client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    fun search(artistName: String): String? {
        if (artistName.isBlank()) return null
        val bearer = token() ?: return null
        val wanted = artistName.normalizeForMatch()
        if (wanted.isBlank()) return null

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", "artist:\"$artistName\"")
            .addQueryParameter("type", "artist")
            .addQueryParameter("limit", "10")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", CANVAS_UA)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) invalidate(bearer)
                if (!response.isSuccessful) return@use null
                val items = JSONObject(response.body?.string().orEmpty())
                    .optJSONObject("artists")
                    ?.optJSONArray("items")
                    ?: return@use null

                val exact = (0 until items.length())
                    .mapNotNull { items.optJSONObject(it) }
                    .firstOrNull { it.optString("name").normalizeForMatch() == wanted }
                    ?: return@use null
                val images = exact.optJSONArray("images") ?: return@use null
                (0 until images.length())
                    .mapNotNull { images.optJSONObject(it) }
                    .maxByOrNull { it.optInt("width", 0) }
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Exact-name Spotify genre labels, used as a resilient Stats fallback. */
    fun searchGenres(artistName: String): List<String> {
        if (artistName.isBlank()) return emptyList()
        val bearer = token() ?: return emptyList()
        val wanted = artistName.normalizeForMatch()
        if (wanted.isBlank()) return emptyList()

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", "artist:\"$artistName\"")
            .addQueryParameter("type", "artist")
            .addQueryParameter("limit", "10")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", CANVAS_UA)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) invalidate(bearer)
                if (!response.isSuccessful) return@use emptyList<String>()
                val items = JSONObject(response.body?.string().orEmpty())
                    .optJSONObject("artists")
                    ?.optJSONArray("items")
                    ?: return@use emptyList<String>()
                val exact = (0 until items.length())
                    .mapNotNull { items.optJSONObject(it) }
                    .firstOrNull { it.optString("name").normalizeForMatch() == wanted }
                    ?: return@use emptyList<String>()
                val genres = exact.optJSONArray("genres") ?: return@use emptyList<String>()
                (0 until genres.length())
                    .mapNotNull { genres.optString(it).trim().takeIf(String::isNotBlank) }
                    .distinct()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun token(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAtMs - 60_000L) return it }
        if (now < retryTokenAfterMs) return null

        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Accept", "application/json")
            .header("User-Agent", CANVAS_UA)
            .build()
        val tokenResponse = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()

        val responseJson = tokenResponse
        val bearer = responseJson?.optString("accessToken")?.takeIf { it.isNotBlank() }
        if (responseJson == null || bearer == null) {
            retryTokenAfterMs = now + RETRY_DELAY_MS
            return null
        }
        cachedToken = bearer
        tokenExpiresAtMs = responseJson.optLong(
            "accessTokenExpirationTimestampMs",
            responseJson.optLong("expirationTimestampMs", now + 30L * 60 * 1000),
        )
        retryTokenAfterMs = 0L
        return bearer
    }

    @Synchronized
    private fun invalidate(rejectedToken: String) {
        if (cachedToken == rejectedToken) {
            cachedToken = null
            tokenExpiresAtMs = 0L
        }
    }
}
