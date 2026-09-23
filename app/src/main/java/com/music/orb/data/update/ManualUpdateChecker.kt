package com.music.orb.data.update

import android.content.Context
import java.io.IOException

sealed interface ManualUpdateResult {
    data object UpToDate : ManualUpdateResult
    data object NoRelease : ManualUpdateResult
    data object Cancelled : ManualUpdateResult
    data class Available(val release: OrbRelease) : ManualUpdateResult
    data class Ready(val release: OrbRelease) : ManualUpdateResult
    data class Failed(val reason: String? = null) : ManualUpdateResult
}

/**
 * Foreground update check started explicitly from Settings.
 *
 * A manual check only discovers the release. It never starts a download by
 * itself. Downloading is a separate explicit action from the update dialog.
 */
object ManualUpdateChecker {
    suspend fun check(context: Context, channel: UpdateChannel): ManualUpdateResult {
        val release = try {
            BetaUpdateChecker.latest(channel)
        } catch (error: IOException) {
            return ManualUpdateResult.Failed(error.message)
        } catch (error: Exception) {
            return ManualUpdateResult.Failed(error.message)
        } ?: run {
            UpdateAvailableStore.clear(context.applicationContext)
            return ManualUpdateResult.NoRelease
        }

        if (!BetaUpdateChecker.looksNewerThanInstalled(release)) {
            UpdateAvailableStore.clear(context.applicationContext)
            BetaUpdateNotifier.cancel(context.applicationContext)
            return ManualUpdateResult.UpToDate
        }
        UpdateAvailableStore.publish(context.applicationContext, release)
        BetaUpdateNotifier.showAvailable(context.applicationContext, release)
        return ManualUpdateResult.Available(release)
    }

    suspend fun download(context: Context, release: OrbRelease): ManualUpdateResult {
        val appContext = context.applicationContext
        if (!BetaUpdateChecker.looksNewerThanInstalled(release)) {
            UpdateAvailableStore.clear(appContext)
            BetaUpdateNotifier.cancel(appContext)
            return ManualUpdateResult.UpToDate
        }

        val apk = try {
            BetaUpdateDownloader.existing(appContext, release)
                ?: BetaUpdateDownloader.download(appContext, release)
        } catch (_: UpdateDownloadCancelledException) {
            UpdateDownloadStore.finish(release.tagName)
            return ManualUpdateResult.Cancelled
        } catch (error: Exception) {
            UpdateDownloadStore.finish(release.tagName)
            return ManualUpdateResult.Failed(error.message)
        }

        val compatibility = BetaUpdateInstaller.inspect(appContext, apk)
        if (!compatibility.compatible) {
            UpdateDownloadStore.finish(release.tagName)
            if (compatibility.archiveVersionCode != null &&
                compatibility.reason.contains("not above installed", ignoreCase = true)
            ) {
                apk.delete()
                return ManualUpdateResult.UpToDate
            }
            apk.delete()
            return ManualUpdateResult.Failed(compatibility.reason)
        }

        UpdateReadyStore.publish(appContext, release, apk)
        UpdateAvailableStore.clear(appContext)
        UpdateDownloadStore.finish(release.tagName)
        BetaUpdateNotifier.showReady(appContext, release, apk)
        return ManualUpdateResult.Ready(release)
    }
}
