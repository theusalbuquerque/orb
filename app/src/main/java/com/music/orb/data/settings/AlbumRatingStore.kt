package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.orb.data.social.OrbSupabase
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.social.CommunityStatsRepository
import com.music.orb.data.YtMusicRepository
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject
import kotlin.math.roundToInt

/** Account-owned server ratings with a durable, per-album local outbox. */
object AlbumRatingStore {
    private var scope: CoroutineScope? = null
    private var catalogueBackfillJob: Job? = null
    private val catalogueAttempted = mutableSetOf<String>()
    private var wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var owner: String? = null
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("orb_album_ratings", Context.MODE_PRIVATE)
    private fun key(account: String, albumId: String) = "${account.length}:$account:$albumId"
    private fun pendingKey(account: String) = "pending:${account.length}:$account"
    private fun pending(prefs: SharedPreferences, account: String) =
        runCatching { JSONObject(prefs.getString(pendingKey(account), "{}") ?: "{}") }.getOrDefault(JSONObject())
    @Synchronized fun load(context: Context, account: String, albumId: String): Float =
        preferences(context).getInt(key(account, albumId), 0).coerceIn(0, 50) / 10f
    @Synchronized fun hasRating(context: Context, account: String, albumId: String): Boolean =
        preferences(context).contains(key(account, albumId))
    @Synchronized fun hasPending(context: Context, account: String, albumId: String): Boolean =
        pending(preferences(context), account).has(albumId)
    suspend fun save(
        context: Context, account: String, albumId: String, value: Float,
        albumTitle: String? = null, artistName: String? = null, artworkUrl: String? = null,
    ) = withContext(Dispatchers.IO) {
        persistRating(context, account, albumId, value, albumTitle, artistName, artworkUrl)
    }
    @Synchronized private fun persistRating(
        context: Context, account: String, albumId: String, value: Float,
        albumTitle: String?, artistName: String?, artworkUrl: String?,
    ) {
        if (albumId.isBlank() || !value.isFinite()) return
        val prefs = preferences(context)
        val queue = pending(prefs, account)
        val previous = queue.optJSONObject(albumId)?.optLong("version", 0L) ?: 0L
        val version = maxOf(System.currentTimeMillis(), previous + 1L)
        val tenths = (value.coerceIn(0f, 5f) * 10).roundToInt()
        val payload = JSONObject().put("rating", tenths).put("version", version)
        albumTitle?.takeIf(String::isNotBlank)?.let { payload.put("title", it) }
        artistName?.takeIf(String::isNotBlank)?.let { payload.put("artist", it) }
        artworkUrl?.takeIf(String::isNotBlank)?.let { payload.put("artwork", it) }
        queue.put(albumId, payload)
        check(prefs.edit().putInt(key(account, albumId), tenths)
            .putString(pendingKey(account), queue.toString()).commit()) {
            "Could not persist album rating on this device"
        }
        _revision.value += 1
        wake.trySend(Unit)
    }
    @Synchronized fun start(context: Context, userId: String, account: String) {
        if (SocialRepository.currentUserId() != userId) return
        if (owner == userId && scope != null) return
        stop()
        owner = userId
        catalogueAttempted.clear()
        _revision.value += 1
        wake = Channel(Channel.CONFLATED)
        val signals = wake
        val app = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch {
            while (isActive && active(userId)) {
                try {
                    synchronize(app, userId, account)
                    _failed.value = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { _failed.value = true; Log.w("OrbAlbumRatings", "Sync will retry", error) }
                withTimeoutOrNull(15_000L) { signals.receive() }
                delay(700L) // Coalesce queued saves before retrying.
            }
        }
    }
    @Synchronized fun stop() {
        owner = null
        catalogueBackfillJob?.cancel()
        catalogueBackfillJob = null
        scope?.cancel()
        scope = null
        _failed.value = false
    }
    private fun active(userId: String) = owner == userId && SocialRepository.currentUserId() == userId

    private suspend fun synchronize(context: Context, userId: String, account: String) {
        if (!active(userId)) return
        val prefs = preferences(context)
        // Read first. Pending edits always win locally; a complete successful read
        // also imports old local-only ratings only for albums absent from the server.
        val rows = mutableListOf<AlbumRatingRow>()
        var offset = 0L
        while (true) {
            val batch = OrbSupabase.client.from("album_ratings").select {
                filter { eq("user_id", userId) }
                order("album_id", Order.ASCENDING)
                range(offset..(offset + 499L))
            }.decodeList<AlbumRatingRow>()
            if (!active(userId)) return
            rows += batch
            if (batch.size < 500) break
            offset += 500L
        }
        applyRemote(context, account, rows)
        val queue = synchronized(this) { pending(prefs, account) }
        val ids = queue.keys().asSequence().toList()
        for (id in ids) {
            if (!active(userId)) return
            val entry = queue.optJSONObject(id) ?: continue
            val sentVersion = entry.getLong("version")
            OrbSupabase.client.from("album_ratings").upsert(
                AlbumRatingUpsert(
                    userId = userId,
                    albumId = id,
                    ratingTenths = entry.getInt("rating").coerceIn(0, 50),
                    isPublic = ProfilePrivacyStore.recentRatingsAudience.value != ProfileAudience.NOBODY,
                ),
            ) { onConflict = "user_id,album_id" }
            if (!active(userId)) return
            // Metadata only: the public Stats aggregation reads the existing rating,
            // never changes it. Older Supabase deployments may lack the new RPC.
            val albumTitle = entry.optString("title")
            val artistName = entry.optString("artist")
            if (albumTitle.isNotBlank() && artistName.isNotBlank()) {
                runCatching {
                    CommunityStatsRepository.registerRatedAlbum(
                        id, albumTitle, artistName, entry.optString("artwork").ifBlank { null },
                    )
                }.onFailure { Log.d("OrbAlbumRatings", "Stats catalogue metadata deferred") }
            }
            synchronized(this) {
                val latest = pending(prefs, account)
                // A response for an older gesture must never acknowledge a newer edit.
                if (latest.optJSONObject(id)?.optLong("version") == sentVersion) {
                    latest.remove(id)
                    prefs.edit().putString(pendingKey(account), latest.toString()).apply()
                    _revision.value += 1
                }
            }
        }
        // Metadata in the older rating rows has only album_id and rating_tenths.
        // Resolve missing catalogue identities automatically, off the sync path,
        // so the artist leaderboard doesn't depend on opening artist pages.
        if (catalogueBackfillJob?.isActive != true) {
            catalogueBackfillJob = scope?.launch { backfillLegacyRatedAlbums(userId) }
        }
    }

    private suspend fun backfillLegacyRatedAlbums(userId: String) {
        val missing = runCatching { CommunityStatsRepository.missingRatedAlbumIds() }
            .getOrElse { return }
            .filter { it !in catalogueAttempted }
            .take(12)
        var registered = 0
        for (albumId in missing) {
            if (!active(userId)) return
            catalogueAttempted.add(albumId)
            val page = withTimeoutOrNull(8_000L) {
                YtMusicRepository.browseSongs(albumId).getOrNull()
            } ?: continue
            val title = page.title?.takeIf(String::isNotBlank) ?: continue
            val creditedArtist = page.headerArtists.firstOrNull()?.name?.takeIf(String::isNotBlank)
                ?: page.songs.firstOrNull()?.artist?.takeIf { name ->
                    name.isNotBlank() && page.songs.all { it.artist.equals(name, ignoreCase = true) }
                }
                ?: continue
            runCatching {
                CommunityStatsRepository.registerRatedAlbum(
                    albumId, title, creditedArtist, page.thumbnailUrl,
                    announceChange = false,
                )
            }.onSuccess { registered += 1 }.onFailure { Log.d("OrbAlbumRatings", "Legacy catalogue resolution deferred for $albumId") }
        }
        if (registered > 0 && active(userId)) CommunityStatsRepository.announceCatalogueChanged()
    }

    @Synchronized private fun applyRemote(context: Context, account: String, rows: List<AlbumRatingRow>) {
        val prefs = preferences(context)
        val queue = pending(prefs, account)
        val editor = prefs.edit()
        rows.forEach { row ->
            if (!queue.has(row.albumId)) editor.putInt(key(account, row.albumId), row.ratingTenths.coerceIn(0, 50))
        }
        val migrationKey = "migrated:${account.length}:$account"
        if (!prefs.getBoolean(migrationKey, false)) {
            val remoteIds = rows.mapTo(HashSet()) { it.albumId }
            val prefix = "${account.length}:$account:"
            prefs.all.forEach { (cacheKey, value) ->
                val id = cacheKey.removePrefix(prefix)
                if (cacheKey.startsWith(prefix) && id.isNotBlank() && value is Int && id !in remoteIds && !queue.has(id)) {
                    queue.put(id, JSONObject().put("rating", value.coerceIn(0, 50)).put("version", System.currentTimeMillis()))
                }
            }
            editor.putBoolean(migrationKey, true).putString(pendingKey(account), queue.toString())
        }
        editor.apply()
        _revision.value += 1
    }
}

@Serializable
private data class AlbumRatingRow(
    @SerialName("album_id") val albumId: String,
    @SerialName("rating_tenths") val ratingTenths: Int,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
)
@Serializable
private data class AlbumRatingUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("album_id") val albumId: String,
    @SerialName("rating_tenths") val ratingTenths: Int,
    @SerialName("is_public") val isPublic: Boolean,
)
