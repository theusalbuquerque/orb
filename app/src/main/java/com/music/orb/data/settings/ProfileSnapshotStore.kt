package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.orb.data.social.OrbProfile
import com.music.orb.data.social.ProfileSocialCounts
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedProfileTaste(
    val name: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val plays: Int = 0,
    /** Non-null only for track entries; absent in older caches by design. */
    val videoId: String? = null,
)

@Serializable
data class CachedProfileSnapshot(
    val savedAtMs: Long = System.currentTimeMillis(),
    val profile: OrbProfile? = null,
    val counts: ProfileSocialCounts? = null,
    val playCount: Long = 0L,
    val tastes: List<CachedProfileTaste> = emptyList(),
    val following: Boolean? = null,
    val compatibilityPercent: Int? = null,
    val compatibilityGenres: List<String> = emptyList(),
)

/**
 * Small display cache for profile pages. It intentionally stores only what the
 * first screen paints; full follower lists and listening history remain live
 * reads and are fetched in the background.
 */
object ProfileSnapshotStore {
    private const val PREFS_NAME = "orb_profile_snapshots"
    private const val KEY_PREFIX = "profile_v2_"
    private const val MAX_ENTRIES = 24
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(userId: String): CachedProfileSnapshot? {
        if (!::prefs.isInitialized || userId.isBlank()) return null
        val raw = prefs.getString(key(userId), null) ?: return null
        return runCatching { json.decodeFromString<CachedProfileSnapshot>(raw) }.getOrNull()
    }

    @Synchronized
    fun save(userId: String, snapshot: CachedProfileSnapshot) {
        if (!::prefs.isInitialized || userId.isBlank()) return
        val edit = prefs.edit().putString(
            key(userId),
            json.encodeToString(snapshot.copy(savedAtMs = System.currentTimeMillis())),
        )
        // Keep the profile cache bounded even after browsing many people.
        val profileKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        if (profileKeys.size >= MAX_ENTRIES && key(userId) !in profileKeys) {
            profileKeys
                .mapNotNull { prefKey ->
                    val savedAt = prefs.getString(prefKey, null)?.let { raw ->
                        runCatching { json.decodeFromString<CachedProfileSnapshot>(raw).savedAtMs }.getOrNull()
                    }
                    savedAt?.let { prefKey to it }
                }
                .minByOrNull { it.second }
                ?.first
                ?.let(edit::remove)
        }
        edit.apply()
    }

    private fun key(userId: String): String = KEY_PREFIX + userId
}
