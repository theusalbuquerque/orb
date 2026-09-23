package com.music.orb.data.jiosaavn

import android.util.Base64
import com.music.orb.data.TrackLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable data class RawArtistMapItem(val id: String = "", val name: String = "")
@Serializable data class RawArtistMap(@SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList())
@Serializable data class RawMoreInfo(
    val album_id: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    @SerialName("320kbps") val has320: String = "",
    val artistMap: RawArtistMap = RawArtistMap(),
) { val supports320: Boolean get() = has320.equals("true", true) }
@Serializable data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo(),
)
@Serializable data class RawSearchResponse(val results: List<RawSongItem> = emptyList())
data class SaavnStream(val url: String, val kbps: Int?)

object JioSaavnService {
    private const val TAG = "BitChord"
    private val baseUrl = String(Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=", Base64.DEFAULT), Charsets.UTF_8)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false }
    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 6_000
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 6_000
            }
            defaultRequest {
                url(baseUrl)
                headers.append(HttpHeaders.Accept, "application/json")
                headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                headers.append("X-Forwarded-For", "49.36.0.1")
                headers.append("X-Real-IP", "49.36.0.1")
                headers.append("Accept-Language", "en-IN,en;q=0.9")
                headers.append(HttpHeaders.Cookie, "explicit_content=1")
            }
            expectSuccess = false
        }
    }

    private fun decryptUrl(value: String): String = runCatching {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), Charsets.UTF_8).trim()
    }.onFailure { TrackLog.e(TAG, "JioSaavn URL decryption failed", it) }.getOrDefault("")

    private fun bestStream(encryptedUrl: String, supports320: Boolean): SaavnStream? {
        val url = decryptUrl(encryptedUrl)
        if (url.isBlank()) return null
        val match = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)$").find(url)
            ?: return SaavnStream(url, if (supports320) 320 else null)
        val offered = match.groupValues[1].toIntOrNull()
        val ext = match.groupValues[2]
        return if (supports320) SaavnStream(url.replaceRange(match.range, "_320.$ext"), 320)
        else SaavnStream(url, offered)
    }

    suspend fun searchSongs(query: String): List<RawSongItem> = runCatching {
        val response = client.get("") {
            parameter("__call", "search.getResults"); parameter("_format", "json")
            parameter("_marker", "0"); parameter("api_version", "4"); parameter("ctx", "android")
            parameter("q", query); parameter("p", "1"); parameter("n", "10")
        }
        if (response.status != HttpStatusCode.OK) return@runCatching emptyList()
        json.decodeFromString<RawSearchResponse>(response.bodyAsText()).results
    }.getOrElse { TrackLog.w(TAG, "Saavn search error: ${it.message}"); emptyList() }

    suspend fun getStreamUrl(songId: String): SaavnStream? = runCatching {
        val response = client.get("") {
            parameter("__call", "song.getDetails"); parameter("_format", "json")
            parameter("_marker", "0"); parameter("api_version", "4"); parameter("ctx", "android")
            parameter("pids", songId)
        }
        if (response.status != HttpStatusCode.OK) return@runCatching null
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return@runCatching null
        val song = (root["songs"] as? JsonArray)?.firstOrNull()
            ?: root.values.firstOrNull { it is JsonObject }
            ?: return@runCatching null
        val raw = json.decodeFromJsonElement(RawSongItem.serializer(), song)
        bestStream(raw.moreInfo.encryptedMediaUrl, raw.moreInfo.supports320)
    }.onFailure { TrackLog.w(TAG, "Saavn getDetails error: ${it.message}") }.getOrNull()
}
