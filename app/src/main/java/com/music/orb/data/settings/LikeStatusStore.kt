package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.orb.data.model.LikeStatus
import org.json.JSONObject

/**
 * Account-scoped rating cache owned by Orb.
 *
 * Supabase is the account-level source of truth, while this store gives the UI
 * immediate/offline persistence. Explicit local actions carry timestamps so a
 * later cloud reconciliation cannot resurrect an older heart state. YouTube
 * Music synchronization is tracked separately as a durable outbox: when the
 * integration is active every tap is sent immediately, and a failed request is
 * retried without rolling the Orb state back.
 */
object LikeStatusStore {
    private const val PREFS_NAME = "orb_like_statuses"
    private const val LEGACY_KEY_PREFIX = "ratings_v1_"
    private const val KEY_PREFIX = "ratings_v2_"
    private const val YOUTUBE_PENDING_PREFIX = "youtube_pending_v1_"

    data class StoredRating(
        val status: LikeStatus,
        val updatedAtMs: Long,
    )

    data class PendingYoutubeRating(
        val status: LikeStatus,
        val forgetLibraryOnSuccess: Boolean,
    )

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(accountKey: String?): Map<String, LikeStatus> =
        loadRecords(accountKey).mapValues { it.value.status }

    /** Returns timestamped ratings, migrating the old flat map on first read. */
    @Synchronized
    fun loadRecords(accountKey: String?): Map<String, StoredRating> {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return emptyMap()
        val storageKey = key(KEY_PREFIX, accountKey)
        val raw = prefs.getString(storageKey, null)
        if (raw != null) return parseRecords(raw)

        val legacyRaw = prefs.getString(key(LEGACY_KEY_PREFIX, accountKey), null)
            ?: return emptyMap()
        val migrated = parseLegacy(legacyRaw)
        persistRecords(accountKey, migrated)
        return migrated
    }

    @Synchronized
    fun set(
        accountKey: String?,
        ratingKey: String,
        status: LikeStatus,
        updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        setMany(accountKey, mapOf(ratingKey to status), updatedAtMs)
    }

    /** Writes multiple aliases of the same user action with one timestamp. */
    @Synchronized
    fun setMany(
        accountKey: String?,
        values: Map<String, LikeStatus>,
        updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank() || values.isEmpty()) return
        val current = loadRecords(accountKey).toMutableMap()
        values.forEach { (ratingKey, status) ->
            if (ratingKey.isNotBlank()) {
                current[ratingKey] = StoredRating(status = status, updatedAtMs = updatedAtMs)
            }
        }
        persistRecords(accountKey, current)
    }

    /**
     * Merges server state by modification time. Local explicit actions win ties
     * so an equal timestamp can never make a just-tapped heart flicker back.
     */
    @Synchronized
    fun mergeRemote(
        accountKey: String?,
        remote: Map<String, StoredRating>,
    ): Map<String, StoredRating> {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return emptyMap()
        if (remote.isEmpty()) return loadRecords(accountKey)

        val merged = loadRecords(accountKey).toMutableMap()
        remote.forEach { (ratingKey, value) ->
            val local = merged[ratingKey]
            if (local == null || value.updatedAtMs > local.updatedAtMs) {
                merged[ratingKey] = value
            }
        }
        persistRecords(accountKey, merged)
        return merged
    }

    /** Latest desired YouTube rating for each video id. */
    @Synchronized
    fun pendingYoutube(accountKey: String?): Map<String, PendingYoutubeRating> {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank()) return emptyMap()
        val raw = prefs.getString(key(YOUTUBE_PENDING_PREFIX, accountKey), null)
            ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val ids = json.keys()
                while (ids.hasNext()) {
                    val videoId = ids.next()
                    val value = json.opt(videoId)
                    val pending = when (value) {
                        is JSONObject -> {
                            val status = runCatching {
                                LikeStatus.valueOf(value.optString("status"))
                            }.getOrNull() ?: continue
                            PendingYoutubeRating(
                                status = status,
                                forgetLibraryOnSuccess = value.optBoolean("forgetLibrary", false),
                            )
                        }
                        is String -> {
                            // Forward-compatible with any early flat outbox build.
                            val status = runCatching { LikeStatus.valueOf(value) }.getOrNull()
                                ?: continue
                            PendingYoutubeRating(status, false)
                        }
                        else -> continue
                    }
                    if (videoId.isNotBlank()) put(videoId, pending)
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun queueYoutube(
        accountKey: String?,
        videoId: String,
        status: LikeStatus,
        forgetLibraryOnSuccess: Boolean,
    ) {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank() || videoId.isBlank()) return
        val pending = pendingYoutube(accountKey).toMutableMap()
        pending[videoId] = PendingYoutubeRating(status, forgetLibraryOnSuccess)
        persistYoutubePending(accountKey, pending)
    }

    /** Removes an outbox entry only if no newer action replaced it mid-request. */
    @Synchronized
    fun clearYoutubeIfMatches(
        accountKey: String?,
        videoId: String,
        expected: PendingYoutubeRating,
    ) {
        if (!::prefs.isInitialized || accountKey.isNullOrBlank() || videoId.isBlank()) return
        val pending = pendingYoutube(accountKey).toMutableMap()
        if (pending[videoId] != expected) return
        pending.remove(videoId)
        persistYoutubePending(accountKey, pending)
    }

    private fun parseRecords(raw: String): Map<String, StoredRating> = runCatching {
        val json = JSONObject(raw)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val ratingKey = keys.next()
                val value = json.optJSONObject(ratingKey) ?: continue
                val status = runCatching {
                    LikeStatus.valueOf(value.optString("status"))
                }.getOrNull() ?: continue
                val updatedAtMs = value.optLong("updatedAtMs", 0L).coerceAtLeast(0L)
                if (ratingKey.isNotBlank()) put(ratingKey, StoredRating(status, updatedAtMs))
            }
        }
    }.getOrDefault(emptyMap())

    private fun parseLegacy(raw: String): Map<String, StoredRating> = runCatching {
        val json = JSONObject(raw)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val videoId = keys.next()
                val status = runCatching { LikeStatus.valueOf(json.optString(videoId)) }.getOrNull()
                if (videoId.isNotBlank() && status != null) {
                    // Unknown age: epoch lets any real cloud timestamp win, while
                    // still allowing this old local heart to seed an empty cloud.
                    put(videoId, StoredRating(status, 0L))
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun persistRecords(accountKey: String, values: Map<String, StoredRating>) {
        val json = JSONObject()
        values.forEach { (ratingKey, value) ->
            json.put(
                ratingKey,
                JSONObject()
                    .put("status", value.status.name)
                    .put("updatedAtMs", value.updatedAtMs),
            )
        }
        prefs.edit().putString(key(KEY_PREFIX, accountKey), json.toString()).apply()
    }

    private fun persistYoutubePending(
        accountKey: String,
        values: Map<String, PendingYoutubeRating>,
    ) {
        val json = JSONObject()
        values.forEach { (videoId, value) ->
            json.put(
                videoId,
                JSONObject()
                    .put("status", value.status.name)
                    .put("forgetLibrary", value.forgetLibraryOnSuccess),
            )
        }
        prefs.edit().putString(key(YOUTUBE_PENDING_PREFIX, accountKey), json.toString()).apply()
    }

    private fun key(prefix: String, accountKey: String): String =
        prefix + Integer.toHexString(accountKey.lowercase().hashCode())
}
