package com.music.orb.data.scrobbling

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persistent, account-scoped FIFO. Saved before trying the network. */
object LastfmScrobbleQueue {
    private const val STORE = "orb_lastfm_scrobble_queue"
    private const val KEY = "pending"
    private const val WORK = "orb_lastfm_scrobble_delivery"
    private val mutex = Mutex()
    private val fileLock = Any()

    private fun load(context: Context): JSONArray = synchronized(fileLock) {
        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun save(context: Context, rows: JSONArray) = synchronized(fileLock) {
        // commit: ensure the item survives process death before triggering network work.
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, rows.toString()).commit()
    }

    fun enqueue(context: Context, scrobble: LastFM.Scrobble) {
        val user = AppSettings.lastfmUsername.value.trim()
        if (user.isBlank() || !AppSettings.lastfmEnabled.value) return
        synchronized(fileLock) {
            val rows = load(context)
            rows.put(JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("user", user)
                .put("artist", scrobble.artist)
                .put("track", scrobble.track)
                .put("album", scrobble.album ?: "")
                .put("duration", scrobble.duration)
                .put("timestamp", scrobble.timestamp))
            // Cap persistence to avoid unbounded growth on permanently disconnected devices.
            val trimmed = JSONArray()
            for (i in (rows.length() - 999).coerceAtLeast(0) until rows.length()) {
                trimmed.put(rows.getJSONObject(i))
            }
            save(context, trimmed)
        }
        scheduleIfPending(context)
    }

    fun scheduleIfPending(context: Context) {
        if (load(context).length() == 0 || !AppSettings.lastfmEnabled.value ||
            !AppSettings.lastfmScrobbleEnabled.value || AppSettings.lastfmSessionKey.value.isBlank()) return
        val request = OneTimeWorkRequestBuilder<LastfmScrobbleWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** true = sent or no work; false = transient failure, WorkManager should retry. */
    suspend fun flush(context: Context): Boolean = mutex.withLock {
        if (!AppSettings.lastfmEnabled.value || !AppSettings.lastfmScrobbleEnabled.value ||
            AppSettings.lastfmSessionKey.value.isBlank()) return@withLock true
        var iterations = 0
        while (iterations++ < 12) {
            val rows = load(context)
            if (rows.length() == 0) return@withLock true
            val now = System.currentTimeMillis() / 1000L
            val user = AppSettings.lastfmUsername.value.trim()
            val sessionKey = AppSettings.lastfmSessionKey.value
            val pending = mutableListOf<Pair<String, LastFM.Scrobble>>()
            val kept = JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val timestamp = row.optLong("timestamp")
                if (timestamp <= 0 || timestamp < now - 14 * 86400 || timestamp > now + 60) continue
                kept.put(row)
                if (row.optString("user") == user && pending.size < 50) {
                    pending.add(row.getString("id") to LastFM.Scrobble(
                        artist = row.getString("artist"),
                        track = row.getString("track"),
                        album = row.optString("album").takeIf(String::isNotBlank),
                        duration = row.optInt("duration"),
                        timestamp = timestamp,
                    ))
                }
            }
            if (kept.length() != rows.length()) save(context, kept)
            if (pending.isEmpty()) return@withLock true // Account changed: leave other account's listens alone.
            val codes = try {
                LastFM.submitScrobbles(pending.map { it.second }, sessionKey)
            } catch (error: LastFM.ApiException) {
                if (error.code == 401 || error.code == 400 || error.code == 422) {
                    Log.w("OrbLastfm", "Scrobble blocked by authorization or invalid request (${error.code})")
                    return@withLock true // Wait for account/config correction, do not loop endlessly.
                }
                Log.w("OrbLastfm", "Temporary scrobble failure (${error.code})")
                return@withLock false
            } catch (error: Exception) {
                Log.w("OrbLastfm", "Network failure sending scrobbles", error)
                return@withLock false
            }
            val completedIds = pending.map { it.first }.toSet()
            // Remove accepted and filtered entries; filtered listens should not be retried.
            val latest = load(context)
            val remaining = JSONArray()
            for (i in 0 until latest.length()) {
                val row = latest.getJSONObject(i)
                if (row.optString("id") !in completedIds) remaining.put(row)
            }
            save(context, remaining)
            if (codes.any { it != 0 }) Log.i("OrbLastfm", "Last.fm filtered ${codes.count { it != 0 }} listens")
        }
        true
    }
}

class LastfmScrobbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (LastfmScrobbleQueue.flush(applicationContext)) Result.success() else Result.retry()
}
