package com.music.orb.data.scrobbling

import com.music.orb.data.settings.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orb's Last.fm API client. Last.fm's shared secret never leaves the Render backend.
 * The user authorizes access in their browser; Orb only stores the resulting session key.
 */
object LastFM {
    private const val ORB_API = "https://orb-4mrh.onrender.com/api/lastfm"

    data class Authorization(val token: String, val url: String)
    data class Session(val username: String, val sessionKey: String)
    data class Scrobble(
        val artist: String,
        val track: String,
        val timestamp: Long,
        val album: String?,
        val duration: Int,
    )

    class ApiException(val code: Int, message: String) : Exception(message)

    private val client by lazy { HttpClient(OkHttp) { expectSuccess = false } }

    private suspend fun request(path: String, body: JSONObject = JSONObject()): JSONObject {
        val response = client.post("$ORB_API/$path") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val payload = try {
            JSONObject(response.bodyAsText())
        } catch (e: Exception) {
            throw ApiException(response.status.value, "Resposta inválida do serviço Last.fm")
        }
        if (!response.status.isSuccess()) {
            throw ApiException(response.status.value, payload.optString("detail", "Erro no Last.fm"))
        }
        return payload
    }

    suspend fun startAuthorization(): Authorization {
        val data = request("auth/start")
        return Authorization(
            token = data.getString("token"),
            url = data.getString("authorize_url"),
        )
    }

    suspend fun completeAuthorization(token: String): Session {
        val data = request("auth/complete", JSONObject().put("token", token))
        return Session(
            username = data.getString("username"),
            sessionKey = data.getString("session_key"),
        )
    }

    suspend fun updateNowPlaying(artist: String, track: String, album: String?, duration: Int) {
        val key = AppSettings.lastfmSessionKey.value.takeIf { it.isNotBlank() } ?: return
        val data = JSONObject()
            .put("session_key", key)
            .put("artist", artist)
            .put("track", track)
            .put("duration", duration)
        if (!album.isNullOrBlank()) data.put("album", album)
        request("now-playing", data)
    }

    /** Returns an ignored-code per scrobble (0 means accepted). Maximum 50 per request. */
    suspend fun submitScrobbles(listens: List<Scrobble>, sessionKey: String): List<Int> {
        require(listens.isNotEmpty() && listens.size <= 50)
        val key = sessionKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(401, "Conta Last.fm não conectada")
        val rows = JSONArray()
        listens.forEach { s ->
            rows.put(JSONObject()
                .put("artist", s.artist)
                .put("track", s.track)
                .put("timestamp", s.timestamp)
                .put("duration", s.duration)
                .apply { if (!s.album.isNullOrBlank()) put("album", s.album) })
        }
        val response = request("scrobble", JSONObject().put("session_key", key).put("listens", rows))
        val codes = response.getJSONArray("ignored_codes")
        if (codes.length() != listens.size) throw ApiException(502, "Resposta incompleta do Last.fm")
        return (0 until codes.length()).map(codes::getInt)
    }
}
