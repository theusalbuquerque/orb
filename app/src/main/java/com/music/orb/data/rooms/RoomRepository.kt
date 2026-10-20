package com.music.orb.data.rooms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.music.orb.data.Http
import com.music.orb.data.model.Song
import com.music.orb.data.social.OrbSupabase
import com.music.orb.data.social.SocialRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

internal data class RoomMember(val id: String, val name: String, val avatar: String?, val host: Boolean, val online: Boolean)
internal data class RoomAddition(val id: String, val userId: String, val atMs: Long)
internal data class ListeningRoom(
    val id: String, val title: String, val code: String, val maxGuests: Int,
    val coverPath: String?, val coverUrl: String?, val canAdd: Boolean, val membersCanAdd: Boolean, val host: Boolean, val hostOnline: Boolean,
    val queue: List<Song>, val queueVersion: Long, val index: Int, val positionMs: Long,
    val playing: Boolean, val serverMs: Long, val playbackAtMs: Long,
    val receivedAtMs: Long, val halfRoundTripMs: Long, val createdAtMs: Long, val members: List<RoomMember>,
    val playbackElsewhere: Boolean = false,
    val expiresAtMs: Long = -1L,
    val durationCode: String = "24h",
    val trackAuthors: Map<String,String> = emptyMap(),
    val additions: List<RoomAddition> = emptyList(),
) {
    fun expectedPosition(): Long = (positionMs + if (playing) {
        (serverMs - playbackAtMs).coerceAtLeast(0) + halfRoundTripMs +
            (SystemClock.elapsedRealtime() - receivedAtMs).coerceAtLeast(0)
    } else 0L).coerceAtLeast(0)
}
internal data class RoomCard(val id: String, val title: String, val coverUrl: String?, val count: Int, val maxGuests: Int)
internal class RoomException(val reason: String) : Exception(reason)

/** The server derives identity, capacity, membership and host permissions from Auth. */
internal object RoomRepository {
    private val _room = MutableStateFlow<ListeningRoom?>(null)
    val room = _room.asStateFlow()
    private val _playbackRoom = MutableStateFlow<ListeningRoom?>(null)
    val playbackRoom = _playbackRoom.asStateFlow()
    val rooms = MutableStateFlow<List<RoomCard>>(emptyList())
    val issue = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    private val queueEditMutex = Mutex()
    private var queueEditCount = 0
    val queueEditing = MutableStateFlow(false)
    val shuffledRoomIds = MutableStateFlow<Set<String>>(emptySet())
    private suspend fun <T> editingQueue(action: suspend () -> T): T {
        queueEditCount++;queueEditing.value=true
        try { return queueEditMutex.withLock {action()} }
        finally {queueEditCount--;queueEditing.value=queueEditCount>0}
    }
    suspend fun editPlaybackQueue(roomId: String, songs: List<Song>) = editingQueue {
        val active=requireNotNull(_playbackRoom.value)
        if(!active.host || active.id!=roomId) throw RoomException("ROOM_HOST_ONLY")
        val tracks=JSONArray().apply { songs.forEach {song -> put(JSONObject().put("videoId",song.videoId)
            .put("title",song.title).put("artist",song.artist).put("thumbnailUrl",song.thumbnailUrl)
            .put("durationText",song.durationText).put("isExplicit",song.isExplicit))} }
        try {
            requireNotNull(rpc("queue",JSONObject().put("roomId",roomId).put("tracks",tracks)))
            prefs.edit().remove(shuffleKey(roomId)).apply()
            shuffledRoomIds.value=shuffledRoomIds.value-roomId
        } catch(exception: Exception) {
            refresh()
            throw exception
        }
    }
    suspend fun shufflePlaybackRoom(enabled: Boolean) = editingQueue {
        val active=requireNotNull(_playbackRoom.value)
        if(!active.host) throw RoomException("ROOM_HOST_ONLY")
        val viewed=_room.value
        _room.value=active
        try {setShuffled(enabled)} finally {
            if(viewed!=null && viewed.id!=active.id) _room.value=viewed
        }
    }
    private var appContext: Context? = null
    private var signedCoverPath: String? = null
    private var signedCoverUrl: String? = null
    private var coverExpiresMs = 0L
    private val prefs get() = requireNotNull(appContext).getSharedPreferences("orb_rooms", Context.MODE_PRIVATE)
    private val accountId get() = SocialRepository.currentUserId()
    val deviceId: String get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device", it).apply()
    }
    fun init(context: Context) { appContext = context.applicationContext }
    fun viewPlayback() { _room.value = _playbackRoom.value }
    fun isGuest(): Boolean = _playbackRoom.value?.host == false
    private fun personalPlaybackKey(user: String) = "personal_playback_$user"
    val hasPersonalPlayback: Boolean
        get() = accountId?.let { prefs.contains(personalPlaybackKey(it)) } == true

    /** Hosts pause the shared timeline; guests only stop following on this device. */
    suspend fun pauseForPersonalPlayback(index: Int, position: Long) = editingQueue {
        val active = _playbackRoom.value ?: return@editingQueue
        val user = accountId ?: throw RoomException("ROOM_SIGN_IN")
        if (active.host) {
            requireNotNull(publish(active.id, index, position, false, active.queueVersion))
        }
        prefs.edit().putString(personalPlaybackKey(user), active.id).apply()
        _playbackRoom.value = null
        RoomSession.onPrivatePlayback()
    }


    fun releaseToOtherDevice(roomId: String) {
        val user = accountId ?: return
        prefs.edit().putString(personalPlaybackKey(user), roomId).apply()
        if (_playbackRoom.value?.id == roomId) _playbackRoom.value = null
        RoomSession.onPrivatePlayback()
        issue.value = "ROOM_OTHER_DEVICE"
    }
    suspend fun acceptTransfer(roomId: String, handoffId: String, touchDeviceKey: String): ListeningRoom =
        requireNotNull(rpc("handoff", JSONObject().put("roomId", roomId)
            .put("handoffId", handoffId).put("touchDeviceKey", touchDeviceKey)))

    suspend fun restore(force: Boolean = false): ListeningRoom? {
        val id = accountId ?: return null
        if (hasPersonalPlayback) return null
        if (!force && prefs.getString("active_$id", null) == null) return _playbackRoom.value
        return rpc("mine", updateView = false)
    }
    suspend fun create(title: String, guests: Int, membersCanAdd: Boolean, duration: String = "24h"): ListeningRoom = requireNotNull(
        rpc("create", JSONObject().put("title", title).put("maxGuests", guests)
            .put("membersCanAdd", membersCanAdd).put("duration", duration)),
    )
    suspend fun edit(title: String, membersCanAdd: Boolean, duration: String = "24h"): ListeningRoom = requireNotNull(
        rpc("edit", roomBody().put("title", title).put("membersCanAdd", membersCanAdd).put("duration", duration)),
    )
    suspend fun removeMember(userId: String): ListeningRoom = requireNotNull(rpc("removeMember",roomBody().put("userId",userId)))
    suspend fun removeCover(): ListeningRoom = requireNotNull(rpc("cover",roomBody().put("path",JSONObject.NULL)))
    suspend fun join(code: String): ListeningRoom = requireNotNull(rpc("join", JSONObject().put("code", code)))
    suspend fun heartbeat() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (accountId != null) execute("/rest/v1/rpc/orb_room_action", JSONObject().put("p_action", "heartbeat")
                .put("p_payload", JSONObject().put("device", deviceId)))
            Unit
        }
    }
    suspend fun open(id: String): ListeningRoom = requireNotNull(rpc("state", JSONObject().put("roomId", id))).also {
        shuffledRoomIds.value=if(isShuffled(id)) shuffledRoomIds.value+id else shuffledRoomIds.value-id
    }
    suspend fun activate(): ListeningRoom = requireNotNull(rpc("activate", roomBody()))
    suspend fun refresh(): ListeningRoom? = _playbackRoom.value?.let { rpc("state", JSONObject().put("roomId", it.id), updateView = false) }
    suspend fun loadRooms() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val user = accountId ?: run { rooms.value = emptyList(); return@withContext }
            val raw = execute("/rest/v1/rpc/orb_room_action", JSONObject().put("p_action", "list").put("p_payload", JSONObject().put("device", deviceId)))
            val list = JSONArray(raw)
            val cards = (0 until list.length()).map { i -> list.getJSONObject(i).let {
                RoomCard(it.getString("id"), it.getString("title"), it.nullableString("coverPath")?.let { path -> signedCover(path,it.optLong("coverVersion")) }, it.getInt("count"), it.getInt("maxGuests"))
            } }
            if (accountId == user) rooms.value = cards
        }
    }
    suspend fun appendSongs(songs: List<Song>): ListeningRoom = requireNotNull(rpc("append", roomBody().put("tracks", JSONArray().apply {
        songs.forEach { song -> put(JSONObject().put("videoId",song.videoId).put("title",song.title)
            .put("artist",song.artist).put("thumbnailUrl",song.thumbnailUrl).put("durationText",song.durationText).put("isExplicit",song.isExplicit)) }
    })))
    suspend fun setQueue(songs: List<Song>): ListeningRoom = requireNotNull(rpc("queue", roomBody().put("tracks", JSONArray().apply {
        songs.forEach { song -> put(JSONObject().put("videoId", song.videoId).put("title", song.title)
            .put("artist", song.artist).put("thumbnailUrl", song.thumbnailUrl).put("durationText", song.durationText)
            .put("isExplicit", song.isExplicit)) }
    })))
    private fun shuffleKey(roomId: String) = "shuffle_${accountId}_$roomId"
    fun isShuffled(roomId: String): Boolean = prefs.contains(shuffleKey(roomId))

    suspend fun moveSong(videoId: String, direction: Int): ListeningRoom {
        val current = open(requireNotNull(_room.value).id)
        if (!current.host) throw RoomException("ROOM_HOST_ONLY")
        val songs = current.queue.toMutableList()
        val from = songs.indexOfFirst { it.videoId == videoId }
        val to = from + direction
        if (from < 0 || to !in songs.indices) return current
        songs.add(to, songs.removeAt(from))
        return setQueue(songs).also { prefs.edit().remove(shuffleKey(current.id)).apply() }
    }

    suspend fun setShuffled(enabled: Boolean): ListeningRoom {
        val current = open(requireNotNull(_room.value).id)
        if (!current.host) throw RoomException("ROOM_HOST_ONLY")
        val key = shuffleKey(current.id)
        val original = prefs.getString(key, null)
        val songs = if (enabled) {
            // Keep already played tracks and the current song in place during listening.
            val start = if (_playbackRoom.value?.id == current.id) (current.index+1).coerceAtMost(current.queue.size) else 0
            val tail = current.queue.drop(start)
            val mixed = tail.shuffled().let { if (it == tail && tail.size > 1) it.reversed() else it }
            current.queue.take(start) + mixed
        } else {
            val ids = original?.let { JSONArray(it) }
            val ranks = if (ids == null) emptyMap() else (0 until ids.length()).associate { ids.getString(it) to it }
            // Newly added songs remain at the end; removed songs are never resurrected.
            current.queue.sortedBy { ranks[it.videoId] ?: Int.MAX_VALUE }
        }
        return setQueue(songs).also {
            val editor = prefs.edit()
            if (enabled) editor.putString(key, original ?: JSONArray(current.queue.map { it.videoId }).toString())
            else editor.remove(key)
            editor.apply()
            shuffledRoomIds.value=if(enabled) shuffledRoomIds.value+current.id else shuffledRoomIds.value-current.id
        }
    }
    suspend fun publish(roomId: String, index: Int, position: Long, playing: Boolean, queueVersion: Long): ListeningRoom? = rpc("publish", JSONObject().put("roomId", roomId).put("queueVersion", queueVersion)
        .put("index", index).put("positionMs", position).put("playing", playing), updateView = false)

    /** Versioned, host-only Automix plan carried separately from the shared playback clock. */
    suspend fun publishMix(room: ListeningRoom, state: RoomMixState): JSONObject = withContext(Dispatchers.IO) {
        require(room.host && state.roomId == room.id && state.queueVersion == room.queueVersion)
        JSONObject(execute("/rest/v1/rpc/orb_room_mix_state", JSONObject()
            .put("p_room_id", room.id)
            .put("p_device", deviceId)
            .put("p_snapshot", RoomMixCodec.encode(state))))
    }

    suspend fun fetchMix(roomId: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(execute("/rest/v1/rpc/orb_room_mix_state", JSONObject()
            .put("p_room_id", roomId)))
    }
    suspend fun leave() {
        val old = _room.value ?: return
        rpc("leave", JSONObject().put("roomId", old.id))
        // The host's cover no longer needs a signed read URL after the room closes.
        if (old.host && old.coverPath != null) runCatching { deleteCover(old.coverPath) }
    }
    fun clearLocal() {
        accountId?.let { prefs.edit().remove("active_$it").remove(personalPlaybackKey(it)).apply() }
        _room.value = null
        _playbackRoom.value = null
        rooms.value = emptyList()
        shuffledRoomIds.value=emptySet()
        signedCoverPath = null
        signedCoverUrl = null
        issue.value = null
    }
    private fun roomBody(): JSONObject = JSONObject().put("roomId", requireNotNull(_room.value).id)
        .put("queueVersion", requireNotNull(_room.value).queueVersion)

    private suspend fun rpc(action: String, payload: JSONObject = JSONObject(), updateView: Boolean = true): ListeningRoom? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val user = accountId ?: throw RoomException("ROOM_SIGN_IN")
            payload.put("device", deviceId)
            val started = SystemClock.elapsedRealtime()
            val raw = execute(if(action in setOf("create", "edit")) "/functions/v1/orb-room-create" else "/rest/v1/rpc/orb_room_action", JSONObject().put("p_action", action).put("p_payload", payload))
            val received = SystemClock.elapsedRealtime()
            if (accountId != user) throw RoomException("ROOM_SIGN_IN")
            if (raw == "null" || raw.isBlank()) {
                if (action == "mine" || _playbackRoom.value?.id == payload.optString("roomId")) {
                    prefs.edit().remove("active_$user").apply()
                    _playbackRoom.value = null
                }
                if (updateView) _room.value = null
                return@withContext null
            }
            val data = JSONObject(raw)
            data.optString("error").takeIf { it.isNotBlank() }?.let { throw RoomException(it) }
            val path = data.nullableString("coverPath")
            val cover = path?.let { signedCover(it,data.optLong("coverVersion")) }
            val queue = data.getJSONArray("queue")
            val members = data.getJSONArray("members")
            val next = ListeningRoom(
                id = data.getString("id"), title = data.getString("title"), code = data.getString("code"),
                maxGuests = data.getInt("maxGuests"), coverPath = path, coverUrl = cover,
                canAdd = data.optBoolean("canAdd"), membersCanAdd = data.optBoolean("membersCanAdd"),
                host = data.getBoolean("host"), hostOnline = data.optBoolean("hostOnline"),
                queue = (0 until queue.length()).map { i -> queue.getJSONObject(i).let { song ->
                    Song(song.getString("videoId"), song.getString("title"), song.optString("artist"),
                        song.nullableString("thumbnailUrl"), durationText = song.nullableString("durationText"),
                        isExplicit = song.optBoolean("isExplicit"))
                } },
                queueVersion = data.getLong("queueVersion"), index = data.getInt("index"),
                positionMs = data.getLong("positionMs"), playing = data.getBoolean("playing"),
                serverMs = data.getLong("serverMs"), playbackAtMs = data.getLong("playbackAtMs"),
                receivedAtMs = received, halfRoundTripMs = ((received - started) / 2).coerceAtMost(1500),
                createdAtMs = data.optLong("createdAtMs"),
                expiresAtMs = data.optLong("expiresAtMs", -1L),
                durationCode = data.optString("durationCode", "24h"),
                playbackElsewhere = data.optBoolean("playbackElsewhere", false),
                trackAuthors = (0 until queue.length()).mapNotNull { i ->
                    val track=queue.getJSONObject(i)
                    track.nullableString("addedByName")?.let { track.getString("videoId") to it }
                }.toMap(),
                additions = data.optJSONArray("additionEvents")?.let { events ->
                    (0 until events.length()).map { i -> events.getJSONObject(i).let {
                        RoomAddition(it.getString("id"),it.getString("userId"),it.getLong("atMs"))
                    } }
                }.orEmpty(),
                members = (0 until members.length()).map { i -> members.getJSONObject(i).let {
                    RoomMember(it.getString("id"), it.getString("name"), it.nullableString("avatar"), it.getBoolean("host"), it.getBoolean("online"))
                } },
            )
            if (action in setOf("activate", "handoff")) prefs.edit().remove(personalPlaybackKey(user)).apply()
            if (action in setOf("activate", "handoff") || (action == "mine" && !hasPersonalPlayback) || _playbackRoom.value?.id == next.id) {
                prefs.edit().putString("active_$user", next.id).apply()
                _playbackRoom.value = next
            }
            shuffledRoomIds.value=if(isShuffled(next.id)) shuffledRoomIds.value+next.id else shuffledRoomIds.value-next.id
            if (updateView || _room.value?.id == next.id) _room.value = next
            issue.value = if (next.playbackElsewhere) "ROOM_OTHER_DEVICE" else null
            next
        }
    }

    suspend fun uploadCover(context: Context, uri: Uri): ListeningRoom = withContext(Dispatchers.IO) {
        val active = requireNotNull(_room.value)
        check(active.host)
        val bytes = squareCover(context, uri)
        val path = "${requireNotNull(accountId)}/${active.id}.jpg"
        execute("/storage/v1/object/room-covers/$path", bytes = bytes, contentType = "image/jpeg", upsert = true)
        signedCoverPath=null; signedCoverUrl=null; coverExpiresMs=0L
        requireNotNull(rpc("cover", JSONObject().put("roomId", active.id).put("path", path)))
    }
    private fun signedCover(path: String, version: Long = 0): String? {
        val cacheKey="$path:$version"
        if (signedCoverPath == cacheKey && SystemClock.elapsedRealtime() < coverExpiresMs) return signedCoverUrl
        return runCatching {
            val data = JSONObject(execute("/storage/v1/object/sign/room-covers/$path", JSONObject().put("expiresIn", 3600)))
            val relative = data.optString("signedURL").ifBlank { data.getString("signedUrl") }
            (if (relative.startsWith("https://")) relative else "${OrbSupabase.baseUrl}/storage/v1$relative").let { it + (if(it.contains("?"))"&" else "?") + "orbv=$version" }.also {
                signedCoverPath = cacheKey; signedCoverUrl = it; coverExpiresMs = SystemClock.elapsedRealtime() + 3_300_000L
            }
        }.getOrNull()
    }
    private fun deleteCover(path: String) {
        execute("/storage/v1/object/room-covers", JSONObject().put("prefixes", JSONArray().put(path)), method = "DELETE")
    }
    private fun execute(path: String, body: JSONObject? = null, bytes: ByteArray? = null,
                        contentType: String = "application/json", method: String = "POST", upsert: Boolean = false): String {
        val token = if (OrbSupabase.configured) OrbSupabase.client.auth.currentSessionOrNull()?.accessToken else null
        if (token == null) throw RoomException("ROOM_SIGN_IN")
        val request = Request.Builder().url(OrbSupabase.baseUrl + path)
            .header("Authorization", "Bearer $token").header("apikey", OrbSupabase.publishableKey)
            .apply { if (upsert) header("x-upsert", "true") }
            .method(method, (bytes ?: (body?.toString() ?: "{}").toByteArray()).toRequestBody(contentType.toMediaType())).build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) throw RoomException("ROOM_SIGN_IN")
                val reason = Regex("ROOM_[A-Z_]+").find(text)?.value ?: "ROOM_NETWORK"
                throw RoomException(reason)
            }
            return text
        }
    }
    private fun squareCover(context: Context, uri: Uri): ByteArray {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > 2048) decoder.setTargetSampleSize((largest / 1024).coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw RoomException("ROOM_COVER")
        }
        val size = minOf(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, (bitmap.width-size)/2, (bitmap.height-size)/2, size, size)
        val scaled = Bitmap.createScaledBitmap(cropped, minOf(size,1024), minOf(size,1024), true)
        return ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()
            if (scaled !== cropped && scaled !== bitmap) scaled.recycle()
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            if (bytes.size > 2_097_152) throw RoomException("ROOM_COVER")
            bytes
        }
    }
    private fun JSONObject.nullableString(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
}
