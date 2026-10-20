package com.music.orb.data.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.data.social.OrbSupabase
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.spotify.SpotifySession
import com.music.orb.data.sources.SourceConfig
import com.music.orb.data.sources.SourceRegistry
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Automatic, per-Supabase-account backup of Orb settings. Supabase RLS requires
 * auth.uid() = user_id. This is not a device backup: downloads, cookies, OAuth
 * Google OAuth/browser sessions and transient player state remain device-local.
 * The Spotify session is separately mirrored through an encrypted Vault RPC.
 *
 * A network/SQL error NEVER counts as an empty server snapshot. In particular,
 * a new device cannot replace an existing account backup when offline.
 */
object AccountSettingsSync {
    private const val TAG = "OrbSettingsSync"
    private const val PREFS_NAME = "bitchord_settings"
    private const val META_PREFS = "orb_account_sync_state"
    private const val SCHEMA_VERSION = 2
    private const val UPLOAD_DEBOUNCE_MS = 850L
    private const val POLL_MS = 30_000L
    private const val RETRY_MS = 5_000L

    @Volatile private var accountId: String? = null
    @Volatile private var applyingRemote = false
    @Volatile private var bootstrapped = false
    @Volatile private var pendingChanges = false
    @Volatile private var pendingSecrets = false
    private var prefs: SharedPreferences? = null
    private var meta: SharedPreferences? = null
    private var application: Context? = null
    private var activity = WeakReference<Activity>(null)
    private var scope: CoroutineScope? = null
    private var uploadJob: Job? = null
    private val mutex = Mutex()
    private val localRevision = AtomicLong(0L)
    private val dirtyProviders = ConcurrentHashMap.newKeySet<String>()
    private var remoteUpdatedAt: String? = null
    private var lastAppliedSources: List<SourceConfig>? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (applyingRemote) return@OnSharedPreferenceChangeListener
        when (key) {
            "lastfm_session_key" -> notifyIntegrationCredentialChanged("lastfm")
            "listenbrainz_token" -> notifyIntegrationCredentialChanged("listenbrainz")
            in SYNC_KEYS -> notifyAccountPreferenceChanged()
        }
    }

    @Synchronized
    fun start(context: Context, userId: String) {
        if (!OrbSupabase.configured || userId.isBlank() || SocialRepository.currentUserId() != userId) return
        if (accountId == userId && scope != null) return
        stop()
        val app = context.applicationContext
        application = app
        activity = WeakReference(findActivity(context))
        accountId = userId
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        meta = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        // No uploads until we have conclusively read the remote account row.
        bootstrapped = false
        pendingChanges = meta?.getBoolean("dirty_$userId", false) == true &&
            meta?.getString("owner", null) == userId
        pendingSecrets = false
        dirtyProviders.clear()
        if (meta?.getString("owner", null) == userId) {
            dirtyProviders.addAll(meta?.getStringSet("secrets_dirty_$userId", emptySet()).orEmpty())
        }
        pendingSecrets = dirtyProviders.isNotEmpty()
        remoteUpdatedAt = null
        lastAppliedSources = null
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        AlbumRatingStore.start(app, userId, preferenceAccountKey(userId))
        val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = liveScope
        liveScope.launch {
            SourceRegistry.configs.drop(1).collect { configs ->
                if (bootstrapped && !applyingRemote && configs != lastAppliedSources) notifyAccountPreferenceChanged()
            }
        }
        liveScope.launch {
            while (isActive && isSignedInAs(userId)) {
                val ok = try {
                    mutex.withLock {
                        if (!bootstrapped) bootstrap(userId)
                        else if (pendingChanges) uploadNow(userId)
                        else downloadChanges(userId)
                    }
                    true
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Account settings sync will retry; server snapshot was NOT overwritten", error)
                    false
                }
                delay(if (ok) POLL_MS else RETRY_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        AlbumRatingStore.stop()
        prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        uploadJob?.cancel()
        uploadJob = null
        scope?.cancel()
        scope = null
        prefs = null
        meta = null
        application = null
        activity.clear()
        accountId = null
        applyingRemote = false
        bootstrapped = false
        pendingChanges = false
        pendingSecrets = false
        dirtyProviders.clear()
        remoteUpdatedAt = null
        lastAppliedSources = null
    }

    private fun isSignedInAs(userId: String) = accountId == userId && SocialRepository.currentUserId() == userId

    private fun preferenceAccountKey(userId: String): String =
        OrbGoogleAuth.storedAccount()?.email?.trim()?.lowercase()
            ?.takeIf(String::isNotBlank) ?: userId

    private suspend fun fetch(userId: String): AccountSettingsRow? =
        OrbSupabase.client.from("orb_account_settings")
            .select { filter { eq("user_id", userId) } }
            .decodeList<AccountSettingsRow>().firstOrNull()

    private suspend fun bootstrap(userId: String) {
        // If this fails, it throws: never mistake a timeout or missing SQL table
        // for an account with no backup.
        val remote = fetch(userId)
        if (!isSignedInAs(userId)) return
        val previousOwner = meta?.getString("owner", null)
        // Isolate accounts even if the new account already has a cloud backup.
        if (previousOwner != null && previousOwner != userId) resetAccountPreferences()
        var localeChanged = false
        if (remote != null) {
            if (previousOwner == userId && pendingChanges) {
                // A local edit (including language changes that recreate the
                // Activity) was not uploaded yet. Retry it rather than erasing it.
                uploadNow(userId)
            } else {
                localeChanged = applySnapshot(userId, remote)
                if (remote.schemaVersion < SCHEMA_VERSION) pendingChanges = true
            }
        } else {
            uploadNow(userId)
        }
        if (!isSignedInAs(userId)) return
        meta?.edit()?.putString("owner", userId)?.apply()
        bootstrapped = true
        if (localeChanged) recreateActivity(userId)
        // Credentials are intentionally synced via separate Vault-encrypted RPCs.
        // A missing migration/key must not stop the non-secret settings sync.
        try {
            syncIntegrationCredentials(userId, initial = !pendingSecrets)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { Log.w(TAG, "Encrypted integration sync unavailable (migration/key required)", e) }
        // Spotify has a separate RPC so deployments without the new migration
        // keep their working Last.fm, Discord and ListenBrainz sync.
        try {
            syncSpotifyIntegration(userId)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { Log.w(TAG, "Spotify session sync unavailable (migration required)", e) }
        if (pendingChanges || pendingSecrets) scheduleUpload()
    }

    private suspend fun downloadChanges(userId: String) {
        val remote = fetch(userId) ?: return
        if (!isSignedInAs(userId)) return
        if (remote.updatedAt != null && remote.updatedAt != remoteUpdatedAt) {
            if (applySnapshot(userId, remote)) recreateActivity(userId)
        }
        try {
            if (pendingSecrets) {
                syncIntegrationCredentials(userId, initial = false)
            } else {
                // Another device may have connected or disconnected an integration.
                syncIntegrationCredentials(userId, initial = true)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { Log.w(TAG, "Integration sync unavailable", e) }
        // A failure in the existing integration RPC cannot block Spotify, or
        // vice versa. Do not pull while this device has an unsent Spotify edit.
        if ("spotify" !in dirtyProviders) {
            try { syncSpotifyIntegration(userId) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { Log.w(TAG, "Spotify session sync unavailable", e) }
        }
    }

    private fun applySnapshot(userId: String, remote: AccountSettingsRow): Boolean {
        if (!isSignedInAs(userId)) return false
        applyingRemote = true
        var localeChanged = false
        try {
            applyRemote(remote.appSettings, completeSnapshot = remote.schemaVersion >= SCHEMA_VERSION)
            if (remote.sourceConfigs.isNotEmpty()) {
                SourceRegistry.applyAccountSyncSnapshot(remote.sourceConfigs)
                lastAppliedSources = SourceRegistry.accountSyncSnapshot()
            }
            val key = preferenceAccountKey(userId)
            ArtistPreferenceStore.applyAccountSyncSnapshot(key, remote.artistPreferences)
            AlbumExclusionStore.applyAccountSyncSnapshot(key, remote.albumExclusions, remote.albumRecordings)
            val tag = remote.appLocale
            val ctx = application
            if (ctx != null && tag != null && (remote.schemaVersion >= 2 || tag.isNotBlank()) &&
                OrbAppLanguage.isSupportedChoice(tag) && OrbAppLanguage.choice(ctx) != tag) {
                OrbAppLanguage.setChoice(ctx, tag)
                localeChanged = true
            }
            remoteUpdatedAt = remote.updatedAt
        } finally {
            applyingRemote = false
        }
        return localeChanged
    }

    private fun recreateActivity(userId: String) {
        // Post rather than suspending the bootstrap inside an Activity recreation.
        // The account owner/bootstrapped state must be committed first.
        activity.get()?.runOnUiThread {
            if (isSignedInAs(userId)) {
                activity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.recreate()
            }
        }
    }

    private fun resetAccountPreferences() {
        applyingRemote = true
        try {
            val editor = prefs?.edit() ?: return
            SYNC_KEYS.forEach(editor::remove)
            editor.commit()
            AppSettings.reloadFromPreferences()
            AppSettings.setLastfmSessionKey("")
            AppSettings.setListenBrainzToken("")
            AppSettings.setDiscordToken("")
            SourceRegistry.resetAccountSyncSources()
            application?.let { OrbAppLanguage.setChoice(it, "") }
        } finally { applyingRemote = false }
    }

    private fun capture(): JsonObject = buildJsonObject {
        val values = prefs?.all.orEmpty()
        KEY_TYPES.forEach { (key, type) ->
            val value = values[key] ?: return@forEach
            when (type) {
                PrefType.BOOL -> (value as? Boolean)?.let { put(key, JsonPrimitive(it)) }
                PrefType.INT -> (value as? Int)?.let { put(key, JsonPrimitive(it)) }
                PrefType.LONG -> (value as? Long)?.let { put(key, JsonPrimitive(it)) }
                PrefType.FLOAT -> (value as? Float)?.let { put(key, JsonPrimitive(it)) }
                PrefType.STRING -> (value as? String)?.let { put(key, JsonPrimitive(it)) }
            }
        }
    }

    private fun applyRemote(remote: JsonObject, completeSnapshot: Boolean) {
        val storage = prefs ?: return
        val editor = storage.edit()
        // A full account snapshot is authoritative even for options restored to
        // their defaults. Older schema-v1 backups were intentionally partial.
        if (completeSnapshot) {
            SYNC_KEYS.filterNot(remote::containsKey).forEach(editor::remove)
        }
        remote.forEach { (key, element) ->
            if (key !in SYNC_KEYS) return@forEach
            val primitive = element as? JsonPrimitive ?: return@forEach
            when (KEY_TYPES[key]) {
                PrefType.BOOL -> primitive.booleanOrNull?.let { editor.putBoolean(key, it) }
                PrefType.INT -> primitive.longOrNull?.toInt()?.let { editor.putInt(key, it) }
                PrefType.LONG -> primitive.longOrNull?.let { editor.putLong(key, it) }
                PrefType.FLOAT -> primitive.doubleOrNull?.toFloat()?.let { editor.putFloat(key, it) }
                PrefType.STRING -> primitive.contentOrNull?.let { editor.putString(key, it) }
                null -> Unit
            }
        }
        editor.commit()
        AppSettings.reloadFromPreferences()
    }

    fun notifyAccountPreferenceChanged() {
        if (applyingRemote || accountId == null) return
        localRevision.incrementAndGet()
        pendingChanges = true
        accountId?.let { meta?.edit()?.putBoolean("dirty_$it", true)?.apply() }
        scheduleUpload()
    }

    fun notifyIntegrationCredentialChanged(provider: String) {
        if (applyingRemote || accountId == null || provider !in setOf("lastfm", "listenbrainz", "discord", "spotify")) return
        dirtyProviders.add(provider)
        pendingSecrets = true
        accountId?.let { meta?.edit()?.putStringSet("secrets_dirty_$it", dirtyProviders.toSet())?.apply() }
        scheduleUpload()
    }

    private fun scheduleUpload() {
        val owner = accountId ?: return
        val liveScope = scope ?: return
        if (!bootstrapped) return
        uploadJob?.cancel()
        uploadJob = liveScope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            try {
                mutex.withLock {
                    if (pendingChanges) uploadNow(owner)
                    if (pendingSecrets) syncIntegrationCredentials(owner, initial = false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { Log.w(TAG, "Account settings upload queued for retry", e) }
        }
    }

    private suspend fun uploadNow(userId: String) {
        if (!isSignedInAs(userId)) return
        val revision = localRevision.get()
        OrbSupabase.client.from("orb_account_settings").upsert(
            AccountSettingsUpsert(
                userId = userId,
                schemaVersion = SCHEMA_VERSION,
                appSettings = capture(),
                appLocale = application?.let(OrbAppLanguage::choice).orEmpty(),
                sourceConfigs = SourceRegistry.accountSyncSnapshot(),
                artistPreferences = ArtistPreferenceStore.accountSyncSnapshot(preferenceAccountKey(userId)),
                albumExclusions = AlbumExclusionStore.accountSyncSnapshot(preferenceAccountKey(userId)),
                albumRecordings = AlbumExclusionStore.accountRecordingSyncSnapshot(preferenceAccountKey(userId)),
            ),
        )
        if (revision == localRevision.get()) {
            pendingChanges = false
            meta?.edit()?.putBoolean("dirty_$userId", false)?.apply()
        }
        // Next poll may retrieve the new server timestamp; it won't change values.
        remoteUpdatedAt = null
    }

    private suspend fun syncIntegrationCredentials(userId: String, initial: Boolean) {
        if (!isSignedInAs(userId)) return
        if (!initial && pendingSecrets) {
            // Only write providers edited on this device. Never replace an
            // unrelated integration connected on another device with stale data.
            for (provider in dirtyProviders.toList()) {
                val credential = when (provider) {
                    "lastfm" -> AppSettings.lastfmSessionKey.value
                    "listenbrainz" -> AppSettings.listenBrainzToken.value
                    "discord" -> AppSettings.discordToken.value
                    "spotify" -> {
                        // Never persist a cookie for another Google/Orb identity.
                        val app = application ?: continue
                        SpotifySession.init(app)
                        if (!isSignedInAs(userId) || SpotifySession.ownerKey().isBlank()) continue
                        SpotifySession.cookie()
                    }
                    else -> continue
                }
                // If the Spotify migration has not been applied yet, leave
                // only that provider queued: Last.fm and other integrations
                // must continue syncing independently.
                val stored = try {
                    if (provider == "spotify") storeSpotifyIntegration(userId, credential)
                    else storeIntegration(userId, provider, credential)
                    true
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (provider != "spotify") throw error
                    Log.w(TAG, "Spotify Vault migration pending; keeping session sync queued", error)
                    false
                }
                if (stored) {
                    dirtyProviders.remove(provider)
                    meta?.edit()?.putStringSet("secrets_dirty_$userId", dirtyProviders.toSet())?.apply()
                }
            }
            pendingSecrets = dirtyProviders.isNotEmpty()
            return
        }
        val list = OrbSupabase.client.postgrest.rpc("orb_integrations_read")
            .decodeList<IntegrationSecretsRow>()
        if (!isSignedInAs(userId)) return
        val remote = list.firstOrNull()
        if (remote == null) {
            // First connected device creates the encrypted backup. It is never
            // initialized from a different user's locally saved credentials.
            if (AppSettings.lastfmSessionKey.value.isNotBlank()) storeIntegration(userId, "lastfm", AppSettings.lastfmSessionKey.value)
            if (AppSettings.listenBrainzToken.value.isNotBlank()) storeIntegration(userId, "listenbrainz", AppSettings.listenBrainzToken.value)
            if (AppSettings.discordToken.value.isNotBlank()) storeIntegration(userId, "discord", AppSettings.discordToken.value)
            return
        }
        applyingRemote = true
        try {
            if (AppSettings.lastfmSessionKey.value != remote.lastfmSessionKey) AppSettings.setLastfmSessionKey(remote.lastfmSessionKey)
            if (AppSettings.listenBrainzToken.value != remote.listenbrainzToken) AppSettings.setListenBrainzToken(remote.listenbrainzToken)
            if (AppSettings.discordToken.value != remote.discordToken) AppSettings.setDiscordToken(remote.discordToken)
        } finally { applyingRemote = false }
    }

    /**
     * Restore the Spotify session only for the same authenticated Orb account.
     * A null cloud value means 'not migrated yet', NOT 'disconnected'. This
     * preserves the connection on existing devices during rollout: the first
     * updated device seeds Vault, later devices can restore its session.
     * An explicitly saved empty value, however, propagates disconnection.
     */
    private suspend fun syncSpotifyIntegration(userId: String) {
        if (!isSignedInAs(userId) || "spotify" in dirtyProviders) return
        val app = application ?: return
        SpotifySession.init(app)
        if (!isSignedInAs(userId) || SpotifySession.ownerKey().isBlank()) return
        val remote = OrbSupabase.client.postgrest.rpc("orb_spotify_credential_read")
            .decodeList<SpotifyCredentialRow>().firstOrNull()?.spotifyCookie
        if (!isSignedInAs(userId)) return
        val local = SpotifySession.cookie()
        when (SpotifyCloudSyncPolicy.decide(local, remote)) {
            SpotifyCloudSyncPolicy.Action.UPLOAD_LOCAL -> storeSpotifyIntegration(userId, local)
            SpotifyCloudSyncPolicy.Action.RESTORE_CLOUD -> {
                applyingRemote = true
                try {
                    if (isSignedInAs(userId) && SpotifySession.ownerKey().isNotBlank()) {
                        SpotifySession.save(checkNotNull(remote))
                    }
                } finally { applyingRemote = false }
            }
            SpotifyCloudSyncPolicy.Action.NONE -> Unit
        }
    }

    private suspend fun storeSpotifyIntegration(userId: String, cookie: String) {
        if (!isSignedInAs(userId)) return
        OrbSupabase.client.postgrest.rpc("orb_spotify_credential_write", buildJsonObject {
            put("p_cookie", cookie)
        })
    }

    private suspend fun storeIntegration(userId: String, provider: String, secret: String) {
        if (!isSignedInAs(userId)) return
        OrbSupabase.client.postgrest.rpc("orb_integrations_write", buildJsonObject {
            put("p_provider", provider)
            put("p_secret", secret)
        })
    }

    private fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    private enum class PrefType { BOOL, INT, LONG, FLOAT, STRING }
    private val KEY_TYPES: Map<String, PrefType> = buildMap {
        listOf(
            "audio_quality_wifi", "audio_quality_cellular", "automix_version", "theme_mode",
            "lyrics_sources", "output_pcm_mode", "discord_status", "discord_activity_type",
            "discord_activity_name", "discord_button_1_text", "discord_button_2_text",
            "offline_download_quality", "orb_lockscreen_display_mode",
            "lastfm_username", "lastfm_endpoint", "discord_username", "discord_name", "discord_avatar",
        ).forEach { put(it, PrefType.STRING) }
        listOf(
            "audio_quality_wifi_maximum", "audio_quality_cellular_maximum", "lossless_audio",
            "automix_enabled_v2", "skip_silence", "spatial_audio", "home_aura_enabled",
            "prioritize_album_versions", "autoplay", "autoplay_explicit",
            "use_youtube_music_library", "show_nerd_stats", "haptic_feedback", "touch_transfer_enabled",
            "reduce_animation", "hide_status_bar_now_playing", "keep_screen_on_now_playing",
            "allow_screen_rotation", "continue_playback_after_recents",
            "swipe_to_play_next", "beta_updates_enabled", "stable_auto_updates_enabled",
            "reduce_dynamic_blur", "animated_canvas", "synced_lyrics", "prioritize_syllable_sync",
            "prefer_usb_dac", "discord_rpc_enabled", "discord_use_details", "discord_advanced_mode",
            "discord_button_1_visible", "discord_button_2_visible", "discord_info_dismissed",
            "offline_download_fallback_high_v3", "orb_lockscreen_enabled", "orb_lockscreen_always_on",
            "orb_lockscreen_auto_brightness", "orb_lockscreen_power_saving",
            "orb_lockscreen_hide_status_bar", "lastfm_enabled", "lastfm_scrobble_enabled",
            "lastfm_now_playing", "listenbrainz_enabled",
        ).forEach { put(it, PrefType.BOOL) }
        listOf("crossfade_seconds", "scrobble_min_duration", "scrobble_delay_seconds").forEach { put(it, PrefType.INT) }
        listOf("playback_speed", "orb_lockscreen_brightness", "scrobble_delay_percent").forEach { put(it, PrefType.FLOAT) }
        listOf("audio_cache_limit_bytes").forEach { put(it, PrefType.LONG) }
    }
    private val SYNC_KEYS = KEY_TYPES.keys
}

@Serializable
private data class AccountSettingsRow(
    @SerialName("user_id") val userId: String,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("app_settings") val appSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("app_locale") val appLocale: String? = null,
    @SerialName("source_configs") val sourceConfigs: List<SourceConfig> = emptyList(),
    @SerialName("artist_preferences") val artistPreferences: Map<String, String> = emptyMap(),
    @SerialName("album_exclusions") val albumExclusions: Map<String, List<String>> = emptyMap(),
    @SerialName("album_recordings") val albumRecordings: Map<String, List<String>> = emptyMap(),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class AccountSettingsUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("app_settings") val appSettings: JsonObject,
    @SerialName("app_locale") val appLocale: String,
    @SerialName("source_configs") val sourceConfigs: List<SourceConfig>,
    @SerialName("artist_preferences") val artistPreferences: Map<String, String>,
    @SerialName("album_exclusions") val albumExclusions: Map<String, List<String>>,
    @SerialName("album_recordings") val albumRecordings: Map<String, List<String>>,
)

@Serializable
private data class SpotifyCredentialRow(
    @SerialName("spotify_cookie") val spotifyCookie: String? = null,
)

@Serializable
private data class IntegrationSecretsRow(
    @SerialName("lastfm_session_key") val lastfmSessionKey: String = "",
    @SerialName("listenbrainz_token") val listenbrainzToken: String = "",
    @SerialName("discord_token") val discordToken: String = "",
)
