package com.music.orb.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.Song
import com.music.orb.data.settings.LikeStatusStore
import com.music.orb.data.social.OrbSongRatingMutation
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.text.Normalizer
import java.util.Locale

/** Account cache + durable desired state. Credentials never leave SpotifySession. */
object LinkedLikesSync {
    private var prefs: SharedPreferences? = null
    private val mutex = Mutex()
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("orb_linked_likes", Context.MODE_PRIVATE)
    }
    fun currentAccount(): String? = OrbGoogleAuth.storedAccount()?.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: SocialRepository.currentUserId()
    private fun key(account: String, suffix: String) = "$account:$suffix"
    private fun json(account: String, suffix: String): JSONObject =
        runCatching { JSONObject(prefs?.getString(key(account, suffix), "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun write(account: String, suffix: String, value: JSONObject) {
        prefs?.edit()?.putString(key(account, suffix), value.toString())?.apply()
    }
    @Synchronized fun remember(account: String, song: Song) = rememberMany(account, listOf(song))
    @Synchronized fun rememberMany(account: String, incoming: List<Song>) {
        val songs = json(account, "songs")
        incoming.filter { it.title.isNotBlank() && it.artist.isNotBlank() }.forEach { song ->
            songs.put(song.videoId, JSONObject().put("title", song.title).put("artist", song.artist)
                .put("image", song.thumbnailUrl ?: "").put("duration", song.durationText ?: "")
                .put("album", song.albumName ?: "").put("explicit", song.isExplicit)
                .put("artistId", song.artistId ?: "").put("albumId", song.albumId ?: ""))
        }
        write(account, "songs", songs)
    }
    private fun decode(id: String, value: JSONObject): Song =
        Song(id, value.optString("title"), value.optString("artist"), value.optString("image").ifBlank { null },
            durationText = value.optString("duration").ifBlank { null },
            albumName = value.optString("album").ifBlank { null }, isExplicit = value.optBoolean("explicit"),
            artistId = value.optString("artistId").ifBlank { null }, albumId = value.optString("albumId").ifBlank { null })
    fun song(account: String, id: String): Song? = json(account, "songs").optJSONObject(id)?.let { decode(id, it) }
    fun songs(account: String): Map<String, Song> {
        val metadata = json(account, "songs")
        return buildMap {
            val ids = metadata.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                metadata.optJSONObject(id)?.let { put(id, decode(id, it)) }
            }
        }
    }
    fun likedSongs(account: String?): List<Song> {
        if (account == null) return emptyList()
        val metadata = json(account, "songs")
        return LikeStatusStore.loadRecords(account).entries.filter { !it.key.startsWith("rec:") && it.value.status == LikeStatus.LIKE }
            .sortedByDescending { it.value.updatedAtMs }.mapNotNull { entry -> metadata.optJSONObject(entry.key)?.let { decode(entry.key, it) } }
    }
    private fun recordingKey(song: Song): String? {
        fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val seconds = song.durationText?.split(':')?.map { it.toIntOrNull() }?.let { parts ->
            if (parts.size in 2..3 && parts.all { it != null }) parts.fold(0) { total, part -> total * 60 + requireNotNull(part) } else null
        }
        return "rec:v1:${normalized(song.title)}|${normalized(song.artist)}|${seconds?.let { ((it + 2) / 5) * 5 } ?: "?"}"
    }
    fun mutation(account: String, id: String, rating: LikeStatusStore.StoredRating): OrbSongRatingMutation {
        val song = song(account, id)
        return OrbSongRatingMutation(videoId = id, recordingKey = song?.let(::recordingKey), status = rating.status.name, title = song?.title,
            artist = song?.artist, album = song?.albumName, durationText = song?.durationText,
            updatedAt = Instant.ofEpochMilli(rating.updatedAtMs).toString())
    }
    /** First import cannot resurrect a deliberate Orb unlike. Subsequent additions have real local observation times. */
    suspend fun importLikes(account: String, source: String, songs: List<Song>, relikes: Set<String>? = null) {
        if (currentAccount() != account) return
        val snapshot = json(account, "seen:$source")
        val initial = !snapshot.optBoolean("initialized")
        // Provider snapshots detect additions only. Their absence never overrides a like from another provider.
        val next = JSONObject().put("initialized", true)
        rememberMany(account, songs)
        val before = LikeStatusStore.loadRecords(account)
        val imports = songs.distinctBy { it.videoId }.filter { song ->
            next.put(song.videoId, true)
            val local = before[song.videoId]
            val explicitUnlikeProtected = local != null && local.status != LikeStatus.LIKE &&
                (if (relikes != null) song.videoId !in relikes else initial)
            !snapshot.optBoolean(song.videoId) && local?.status != LikeStatus.LIKE && !explicitUnlikeProtected
        }
        val timestamp = System.currentTimeMillis()
        val rating = LikeStatusStore.StoredRating(LikeStatus.LIKE, timestamp)
        // Bulk upload avoids a network round trip per imported heart.
        val mutations = imports.map { song -> OrbSongRatingMutation(
            videoId = song.videoId, recordingKey = recordingKey(song), status = LikeStatus.LIKE.name,
            title = song.title, artist = song.artist, album = song.albumName, durationText = song.durationText,
            updatedAt = Instant.ofEpochMilli(timestamp).toString(),
        ) }
        if (mutations.isNotEmpty()) SocialRepository.setMySongRatings(mutations)
        if (currentAccount() != account) return
        val aliases = buildMap {
            imports.forEach { song -> put(song.videoId, rating); recordingKey(song)?.let { put(it, rating) } }
        }
        LikeStatusStore.mergeRemote(account, aliases)
        val desired = LikeStatusStore.loadRecords(account)
        imports.forEach { song ->
            desired[song.videoId]?.let { LikeStatusStore.queueYoutube(account, song.videoId, it.status, false) }
        }
        write(account, "seen:$source", next)
    }
    private fun fingerprint(cookie: String) = MessageDigest.getInstance("SHA-256").digest(cookie.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** Read-only import. Missing Spotify likes never generate an unlike or a write request. */
    private suspend fun syncSpotify(account: String) = mutex.withLock {
        val cookie = SpotifySession.cookie().takeIf { it.isNotBlank() } ?: return@withLock
        val owner = fingerprint(cookie)
        fun active() = currentAccount() == account && SpotifySession.cookie() == cookie
        if (!active()) return@withLock
        val tracks = SpotifyLibrary.tracks(SpotifyLibrary.LIKED_ID)
        if (!active()) return@withLock
        val mapped = mutableListOf<Song>()
        val relikes = mutableSetOf<String>()
        val observed = json(account, "observed:$owner")
        val firstRead = !observed.optBoolean("initialized")
        val observation = JSONObject().put("initialized", true)
        val mapping = json(account, "spotify:$owner")
        tracks.forEach { track ->
            observation.put(track.id, true)
            if (!firstRead && !observed.optBoolean(track.id)) mapping.put("relike:${track.id}", true)
        }
        write(account, "spotify:$owner", mapping)
        var lookups = 0
        val cachedSongs = songs(account)
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock
            val id = mapping.optString("in:${track.id}").ifBlank { null }
            val cached = id?.let { cachedSongs[it] }
            if (cached == null && (lookups >= 25 ||
                System.currentTimeMillis() - mapping.optLong("miss:${track.id}", 0L) < 86_400_000L)) continue
            val resolved = cached ?: run {
                lookups++
                val candidates = YtMusicRepository.search("${track.title} ${track.artist}", SearchFilter.SONGS)
                    .getOrThrow().filterIsInstance<SearchResult.Track>().map { it.song }
                TrackMatcher.best(candidates, TrackMatcher.Target(title = track.title, artist = track.artist,
                    durationSec = track.durationMs.takeIf { it > 0 }?.div(1000), albumName = track.album))
            }
            if (resolved != null) {
                mapping.put("in:${track.id}", resolved.videoId)
                if (mapping.optBoolean("relike:${track.id}")) relikes += resolved.videoId
                mapped += resolved
            } else mapping.put("miss:${track.id}", System.currentTimeMillis())
        }
        if (!active()) return@withLock
        write(account, "spotify:$owner", mapping)
        importLikes(account, "spotify:$owner", mapped, relikes)
        val mappedIds = mapped.mapTo(HashSet()) { it.videoId }
        tracks.forEach { track ->
            if (mapping.optString("in:${track.id}") in mappedIds) mapping.remove("relike:${track.id}")
        }
        write(account, "spotify:$owner", mapping)
        write(account, "observed:$owner", observation)
    }
    /** Network failure is retryable; coroutine cancellation is never swallowed. */
    suspend fun importSpotifyLikes(account: String) = withContext(Dispatchers.IO) {
        try { syncSpotify(account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A failed read never clears existing likes; the next import retries. */ }
    }
}
