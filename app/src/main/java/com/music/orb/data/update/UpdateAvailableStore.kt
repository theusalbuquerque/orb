package com.music.orb.data.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable metadata for an update that exists remotely but has not been downloaded.
 *
 * Background workers only publish metadata here. Downloading is always an explicit
 * foreground user action from the update dialog.
 */
object UpdateAvailableStore {

    private const val PREFS = "orb_update_available"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_TAG = "tag"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_HERO_IMAGE_URL = "hero_image_url"

    private val _available = MutableStateFlow<OrbRelease?>(null)
    val available = _available.asStateFlow()

    fun restore(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val channel = UpdateChannel.fromWire(prefs.getString(KEY_CHANNEL, null))
        if (channel == null) {
            clear(appContext)
            return
        }

        val release = OrbRelease(
            channel = channel,
            tagName = prefs.getString(KEY_TAG, "").orEmpty(),
            displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
            releaseUrl = prefs.getString(KEY_RELEASE_URL, "").orEmpty(),
            apkUrl = prefs.getString(KEY_APK_URL, "").orEmpty(),
            apkName = prefs.getString(KEY_APK_NAME, "").orEmpty(),
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            heroImageUrl = prefs.getString(KEY_HERO_IMAGE_URL, null),
        )

        if (release.tagName.isBlank() || release.releaseUrl.isBlank() ||
            release.apkUrl.isBlank() || release.apkName.isBlank() ||
            !isChannelSelected(appContext, release.channel) ||
            !BetaUpdateChecker.looksNewerThanInstalled(release)
        ) {
            clear(appContext)
            return
        }
        _available.value = release
    }

    fun publish(context: Context, release: OrbRelease) {
        val appContext = context.applicationContext
        if (!isChannelSelected(appContext, release.channel) ||
            !BetaUpdateChecker.looksNewerThanInstalled(release)
        ) {
            clear(appContext)
            return
        }

        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHANNEL, release.channel.wireName)
            .putString(KEY_TAG, release.tagName)
            .putString(KEY_DISPLAY_NAME, release.displayName)
            .putString(KEY_RELEASE_URL, release.releaseUrl)
            .putString(KEY_APK_URL, release.apkUrl)
            .putString(KEY_APK_NAME, release.apkName)
            .putString(KEY_RELEASE_NOTES, release.releaseNotes)
            .putString(KEY_HERO_IMAGE_URL, release.heroImageUrl)
            .apply()
        _available.value = release
    }

    fun clearIfDifferentChannel(context: Context, selected: UpdateChannel) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = UpdateChannel.fromWire(prefs.getString(KEY_CHANNEL, null))
        val inMemory = _available.value?.channel
        if ((stored != null && stored != selected) || (inMemory != null && inMemory != selected)) {
            clear(appContext)
            BetaUpdateNotifier.cancel(appContext)
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _available.value = null
        // The system notification and the in-app banner represent the same
        // availability state. Clearing one must clear the other so a user who
        // already installed the latest version never sees a stale alert.
        BetaUpdateNotifier.cancel(appContext)
    }

    private fun isChannelSelected(context: Context, channel: UpdateChannel): Boolean {
        val prefs = context.getSharedPreferences(BetaUpdateWorker.SETTINGS_PREFS, Context.MODE_PRIVATE)
        return when (channel) {
            UpdateChannel.BETA -> prefs.getBoolean(BetaUpdateWorker.KEY_BETA_UPDATES_ENABLED, false)
            UpdateChannel.STABLE -> prefs.getBoolean(BetaUpdateWorker.KEY_STABLE_AUTO_UPDATES_ENABLED, true)
        }
    }
}
