package com.music.orb.data.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

class BetaUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val channel = UpdateChannel.fromWire(inputData.getString(INPUT_CHANNEL)) ?: when {
            prefs.getBoolean(KEY_BETA_UPDATES_ENABLED, false) -> UpdateChannel.BETA
            prefs.getBoolean(KEY_STABLE_AUTO_UPDATES_ENABLED, true) -> UpdateChannel.STABLE
            else -> return Result.success()
        }

        Log.i(TAG, "Background update check started for ${channel.wireName}")

        // Work can survive a channel change in WorkManager's database. A stale
        // job must not cross the user's selected boundary.
        if (channel == UpdateChannel.BETA && !prefs.getBoolean(KEY_BETA_UPDATES_ENABLED, false)) {
            return Result.success()
        }
        if (channel == UpdateChannel.STABLE && !prefs.getBoolean(KEY_STABLE_AUTO_UPDATES_ENABLED, true)) {
            return Result.success()
        }

        val release = try {
            BetaUpdateChecker.latest(channel)
        } catch (error: IOException) {
            Log.w(TAG, "GitHub check failed for ${channel.wireName}; retrying", error)
            return Result.retry()
        } catch (error: Exception) {
            Log.e(TAG, "GitHub check failed for ${channel.wireName}", error)
            return Result.success()
        } ?: run {
            UpdateAvailableStore.clear(applicationContext)
            return Result.success()
        }

        if (!BetaUpdateChecker.looksNewerThanInstalled(release)) {
            Log.i(TAG, "Ignoring ${release.tagName}; installed ${com.music.orb.BuildConfig.VERSION_NAME} is not older")
            UpdateAvailableStore.clear(applicationContext)
            return Result.success()
        }

        // Critical invariant: background update work is discovery-only. Never
        // download an APK here. The download begins only after an explicit tap
        // on Download in the foreground update dialog.
        UpdateAvailableStore.publish(applicationContext, release)
        val notified = BetaUpdateNotifier.showAvailable(applicationContext, release)
        if (notified) {
            Log.i(TAG, "${release.tagName} available; notification posted without downloading")
        } else {
            Log.i(TAG, "${release.tagName} available; notification permission unavailable")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "OrbUpdateWorker"
        const val INPUT_CHANNEL = "orb_update_channel"
        const val SETTINGS_PREFS = "bitchord_settings"
        const val KEY_BETA_UPDATES_ENABLED = "beta_updates_enabled"
        const val KEY_STABLE_AUTO_UPDATES_ENABLED = "stable_auto_updates_enabled"
        // Kept for compatibility with preferences written by older updater builds.
        const val KEY_LAST_READY_PREFIX = "last_update_ready_"
        const val KEY_REJECTED_PREFIX = "last_update_rejected_"
    }
}
