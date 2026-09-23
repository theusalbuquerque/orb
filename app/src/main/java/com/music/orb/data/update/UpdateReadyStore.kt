package com.music.orb.data.update

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Durable bridge between the background update worker and the Compose UI.
 *
 * WorkManager may discover/download an update while Orb is in the foreground,
 * in the background, or while no Activity exists at all. A StateFlow alone
 * would lose the event when the process dies, so the ready-to-install metadata
 * is persisted and restored on the next process start.
 */
data class ReadyOrbUpdate(
    val channel: UpdateChannel,
    val tagName: String,
    val displayName: String,
    val releaseUrl: String,
    val apkName: String,
    val releaseNotes: String = "",
    val heroImageUrl: String? = null,
)

object UpdateReadyStore {

    private const val PREFS = "orb_update_ready"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_TAG = "tag"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_HERO_IMAGE_URL = "hero_image_url"

    private val _ready = MutableStateFlow<ReadyOrbUpdate?>(null)
    val ready = _ready.asStateFlow()

    /** Restore a downloaded update after process death/app relaunch. */
    fun restore(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val channel = UpdateChannel.fromWire(prefs.getString(KEY_CHANNEL, null))
        if (channel == null) {
            clear(appContext, deleteApk = false)
            return
        }
        val candidate = ReadyOrbUpdate(
            channel = channel,
            tagName = prefs.getString(KEY_TAG, "").orEmpty(),
            displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
            releaseUrl = prefs.getString(KEY_RELEASE_URL, "").orEmpty(),
            apkName = prefs.getString(KEY_APK_NAME, "").orEmpty(),
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            heroImageUrl = prefs.getString(KEY_HERO_IMAGE_URL, null),
        )

        if (candidate.tagName.isBlank() || candidate.apkName.isBlank()) {
            clear(appContext, deleteApk = false)
            return
        }

        // A downloaded APK from a previous app version must not resurrect an
        // update prompt after the user has already installed that version (or a
        // newer one).
        if (!BetaUpdateChecker.looksNewerThanInstalled(candidate.tagName)) {
            clear(appContext, deleteApk = true)
            BetaUpdateNotifier.cancel(appContext)
            return
        }

        // Never surface an update from a channel the user no longer follows.
        if (!isChannelSelected(appContext, candidate.channel)) {
            clear(appContext, deleteApk = true)
            return
        }

        val apk = BetaUpdateDownloader.byName(appContext, candidate.apkName)
        if (apk == null || !BetaUpdateInstaller.inspect(appContext, apk).compatible) {
            clear(appContext, deleteApk = true)
            return
        }

        _ready.value = candidate
    }

    /** Called by the worker after the APK has downloaded and passed validation. */
    fun publish(context: Context, release: OrbRelease, apk: File) {
        val appContext = context.applicationContext
        if (!apk.isFile || !isChannelSelected(appContext, release.channel) ||
            !BetaUpdateChecker.looksNewerThanInstalled(release)
        ) {
            if (apk.isFile && !BetaUpdateChecker.looksNewerThanInstalled(release)) apk.delete()
            clear(appContext, deleteApk = false)
            BetaUpdateNotifier.cancel(appContext)
            return
        }

        val update = ReadyOrbUpdate(
            channel = release.channel,
            tagName = release.tagName,
            displayName = release.displayName,
            releaseUrl = release.releaseUrl,
            apkName = apk.name,
            releaseNotes = release.releaseNotes,
            heroImageUrl = release.heroImageUrl,
        )
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHANNEL, update.channel.wireName)
            .putString(KEY_TAG, update.tagName)
            .putString(KEY_DISPLAY_NAME, update.displayName)
            .putString(KEY_RELEASE_URL, update.releaseUrl)
            .putString(KEY_APK_NAME, update.apkName)
            .putString(KEY_RELEASE_NOTES, update.releaseNotes)
            .putString(KEY_HERO_IMAGE_URL, update.heroImageUrl)
            .apply()
        _ready.value = update
    }

    /**
     * Channel changes must not leave a downloaded Beta prompt visible while
     * following Stable (or vice versa). Same-channel restores are preserved.
     */
    fun clearIfDifferentChannel(context: Context, selected: UpdateChannel) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = UpdateChannel.fromWire(prefs.getString(KEY_CHANNEL, null))
        val inMemory = _ready.value?.channel
        if ((stored != null && stored != selected) || (inMemory != null && inMemory != selected)) {
            clear(appContext, deleteApk = true)
            BetaUpdateNotifier.cancel(appContext)
        }
    }

    fun clear(context: Context, deleteApk: Boolean = false) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apkName = prefs.getString(KEY_APK_NAME, null)
        prefs.edit().clear().apply()
        _ready.value = null
        if (deleteApk && !apkName.isNullOrBlank()) {
            BetaUpdateDownloader.byName(appContext, apkName)?.delete()
        }
    }

    /** Intent used by both the in-app prompt and the Android notification. */
    fun installIntent(context: Context, update: ReadyOrbUpdate): Intent =
        Intent(context, BetaInstallActivity::class.java).apply {
            putExtra(BetaUpdateInstaller.EXTRA_APK_NAME, update.apkName)
            putExtra(BetaUpdateInstaller.EXTRA_CHANNEL, update.channel.wireName)
        }

    fun onInstallRequested(context: Context) {
        // Avoid leaving a stale system notification behind after the user chose
        // Install from inside Orb. The persisted ready state intentionally stays
        // until the new APK is actually installed, so cancelling the installer
        // still lets the user reopen the prompt from the top-bar update icon.
        BetaUpdateNotifier.cancel(context.applicationContext)
    }

    private fun isChannelSelected(context: Context, channel: UpdateChannel): Boolean {
        val prefs = context.getSharedPreferences(BetaUpdateWorker.SETTINGS_PREFS, Context.MODE_PRIVATE)
        return when (channel) {
            UpdateChannel.BETA -> prefs.getBoolean(BetaUpdateWorker.KEY_BETA_UPDATES_ENABLED, false)
            UpdateChannel.STABLE -> prefs.getBoolean(BetaUpdateWorker.KEY_STABLE_AUTO_UPDATES_ENABLED, true)
        }
    }
}
