package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.orb.data.social.OrbSupabase
import com.music.orb.data.social.SocialRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.collect
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
internal data class HistoryOperation(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: String,
    val payload: JsonObject,
    val sequence: Long = 0,
)

@Serializable
private data class HistoryWrite(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: String,
    val payload: JsonObject,
)

/** Private account history. Durable outbox retries identical IDs; no service-role key. */
object AccountHistorySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private lateinit var prefs: SharedPreferences
    private var account: String? = null
    private var initialized = false
    private var bound = false
    private val stateLock = Any()

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("orb_account_history_sync", Context.MODE_PRIVATE)
        initialized = true
        bindCurrentAccount()
        if (OrbSupabase.configured) scope.launch {
            OrbSupabase.client.auth.sessionStatus.collect {
                bindCurrentAccount()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            while (true) {
                try { synchronize() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { Log.w("OrbHistorySync", "History sync will retry", error) }
                withTimeoutOrNull(15_000L) { wake.receive() }
            }
        }
    }

    fun bindCurrentAccount() {
        if (!initialized) return
        synchronized(stateLock) {
            val user = SocialRepository.currentUserId()
            if (bound && user == account) return
            // Only the first signed-in account claims the old device-local history.
            // Other accounts always use separate cache and outbox keys.
            val migrate = user != null && !prefs.contains("legacy_owner")
            val searches = if (migrate) SearchHistory.recent.value else emptyList()
            val plays = if (migrate) RecentPlaybackStore.snapshotEntries().map { RecentPlaybackStore.withLegacyDevice(it) } else emptyList()
            account = user
            bound = true
            SearchHistory.bindAccount(user)
            RecentPlaybackStore.bindAccount(user)
            if (migrate && user != null) {
                val imported = plays.sortedBy { it.playedAtMs }.map { entry ->
                    HistoryOperation(entry.eventId, user, "playback", RecentPlaybackStore.cloudPayload(entry))
                } + searches.asReversed().map { entry ->
                    HistoryOperation(UUID.randomUUID().toString(), user, "search_record", buildJsonObject {
                        put("query", entry.query)
                        entry.thumbnailUrl?.let { put("thumbnailUrl", it) }
                    })
                }
                // Commit the claim and all imported operations atomically before changing UI.
                prefs.edit().putString("legacy_owner", user)
                    .putString(outboxKey(user), json.encodeToString(pending(user) + imported)).commit()
                SearchHistory.applyCloud(imported)
                val localPlays = plays.map { entry ->
                    HistoryOperation(entry.eventId, user, "playback", RecentPlaybackStore.localPayload(entry))
                }
                RecentPlaybackStore.applyCloud(localPlays)
            }
            wake.trySend(Unit)
        }
    }

    internal fun withAccount(block: () -> Unit) {
        synchronized(stateLock) {
            bindCurrentAccount()
            block()
        }
    }

    internal fun enqueue(kind: String, payload: JsonObject, id: String = UUID.randomUUID().toString()) {
        if (!initialized) return
        synchronized(stateLock) {
            val user = account ?: return
            if (user != SocialRepository.currentUserId()) return
            val operation = HistoryOperation(id, user, kind, payload)
            val pending = pending(user)
            prefs.edit().putString(outboxKey(user), json.encodeToString(pending.filterNot { it.id == id } + operation)).commit()
        }
        wake.trySend(Unit)
    }

    private fun outboxKey(user: String) = "pending_$user"
    private fun pending(user: String): List<HistoryOperation> =
        json.decodeFromString(prefs.getString(outboxKey(user), "[]") ?: "[]")

    private suspend fun synchronize() {
        bindCurrentAccount()
        val user = synchronized(stateLock) { account } ?: return
        val batch = synchronized(stateLock) { pending(user).take(100) }
        if (batch.isNotEmpty()) {
            // Preserve order of searches/removes/clear even when the app was offline.
            // Retrying a committed event is ignored, rather than moving its server sequence.
            for (op in batch) {
                if (SocialRepository.currentUserId() != user) return
                OrbSupabase.client.from("account_history_events").upsert(HistoryWrite(op.id, op.userId, op.kind, op.payload)) {
                    onConflict = "user_id,id"
                    ignoreDuplicates = false
                }
            }
            synchronized(stateLock) {
                val sent = batch.associateBy { it.id }
                prefs.edit().putString(outboxKey(user), json.encodeToString(pending(user).filterNot { sent[it.id]?.payload == it.payload })).commit()
            }
        }
        if (SocialRepository.currentUserId() != user) return
        // The invoker view reduces the server event log to the current six searches
        // and latest 200 qualified plays; no arbitrary history-page cutoff on clears.
        val remote = OrbSupabase.client.from("account_history_snapshot").select {
            filter { eq("user_id", user) }
            order("sequence", Order.ASCENDING)
            range(0L..205L)
        }.decodeList<HistoryOperation>()
        synchronized(stateLock) {
            if (account != user || SocialRepository.currentUserId() != user) return
            val projected = (remote + pending(user)).groupBy { it.id }.map { (_, versions) ->
                versions.maxByOrNull { it.payload["listenedMs"]?.toString()?.toLongOrNull() ?: 0L } ?: versions.last()
            }
            SearchHistory.applyCloud(projected)
            RecentPlaybackStore.applyCloud(projected)
            if (pending(user).isNotEmpty()) wake.trySend(Unit)
        }
    }
}
