package com.music.orb.data.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable "update available" state.
 *
 * Background update jobs are intentionally discovery-only: they may check the
 * selected channel, but they never download an APK without an explicit tap on
 * "Download now". The release metadata is persisted here so Compose can show
 * the same update card after process death or on the next app launch.
 */
object UpdateOfferStore {

    private const val PREFS = "orb_update_offer"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_TAG = "tag"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_HERO_IMAGE_URL = "hero_image_url"

    private val _offer = MutableStateFlow<OrbRelease?>(null)
    val offer = _offer.asStateFlow()

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

        if (
            release.tagName.isBlank() ||
            release.releaseUrl.isBlank() ||
            release.apkUrl.isBlank() ||
            release.apkName.isBlank() ||
            !isChannelSelected(appContext, release.channel) ||
            !BetaUpdateChecker.looksNewerThanInstalled(release)
        ) {
            clear(appContext)
            return
        }

        // A downloaded copy of this exact release takes precedence over the
        // offer card; do not ask the user to download it again.
        if (UpdateReadyStore.ready.value?.tagName == release.tagName) {
            clear(appContext)
            return
        }

        _offer.value = release
    }

    fun publish(context: Context, release: OrbRelease, forcePrompt: Boolean = false) {
        val appContext = context.applicationContext
        if (!forcePrompt && !isChannelSelected(appContext, release.channel)) return
        if (!BetaUpdateChecker.looksNewerThanInstalled(release)) return
        if (UpdateReadyStore.ready.value?.tagName == release.tagName) {
            clear(appContext)
            return
        }

        if (forcePrompt && _offer.value == release) {
            // A manual "check now" is an explicit request to surface the
            // result again, even if the same release was previously deferred.
            _offer.value = null
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
        _offer.value = release
    }

    fun clearIfDifferentChannel(context: Context, selected: UpdateChannel) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = UpdateChannel.fromWire(prefs.getString(KEY_CHANNEL, null))
        val inMemory = _offer.value?.channel
        if ((stored != null && stored != selected) || (inMemory != null && inMemory != selected)) {
            clear(appContext)
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _offer.value = null
    }

    private fun isChannelSelected(context: Context, channel: UpdateChannel): Boolean {
        val prefs = context.getSharedPreferences(BetaUpdateWorker.SETTINGS_PREFS, Context.MODE_PRIVATE)
        val betaSelected = prefs.getBoolean(BetaUpdateWorker.KEY_BETA_UPDATES_ENABLED, false)
        return when (channel) {
            UpdateChannel.BETA -> betaSelected
            UpdateChannel.STABLE -> !betaSelected
        }
    }
}
