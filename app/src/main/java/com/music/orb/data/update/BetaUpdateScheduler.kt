package com.music.orb.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BetaUpdateScheduler {

    private const val BETA_PERIODIC = "orb_update_beta_periodic"
    private const val BETA_NOW = "orb_update_beta_now"
    private const val STABLE_PERIODIC = "orb_update_stable_periodic"
    private const val STABLE_NOW = "orb_update_stable_now"

    // Names used by the first Beta watcher implementation.
    private const val LEGACY_PERIODIC = "orb_beta_release_watch"
    private const val LEGACY_SEED = "orb_beta_release_seed"

    /**
     * Rebuilds WorkManager state from the user's persisted channel choice.
     *
     * Unique/periodic work usually survives process death and reboot, but it can
     * disappear after app restore, an updater migration, WorkManager database
     * cleanup or an older build replacing these jobs. The preference is the
     * source of truth, so every process start makes the scheduler agree with it.
     */
    fun restoreSelectedChannel(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(
            BetaUpdateWorker.SETTINGS_PREFS,
            Context.MODE_PRIVATE,
        )

        when {
            prefs.getBoolean(BetaUpdateWorker.KEY_BETA_UPDATES_ENABLED, false) ->
                followBeta(appContext, forceImmediate = false)

            prefs.getBoolean(BetaUpdateWorker.KEY_STABLE_AUTO_UPDATES_ENABLED, true) ->
                followStable(appContext, forceImmediate = false)

            else -> stopAutomaticUpdates(appContext)
        }
    }

    fun followBeta(context: Context) = followBeta(context, forceImmediate = true)

    private fun followBeta(context: Context, forceImmediate: Boolean) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        UpdateReadyStore.clearIfDifferentChannel(appContext, UpdateChannel.BETA)
        UpdateAvailableStore.clearIfDifferentChannel(appContext, UpdateChannel.BETA)
        cancelLegacy(wm)
        wm.cancelUniqueWork(STABLE_NOW)
        wm.cancelUniqueWork(STABLE_PERIODIC)
        enqueueChannel(wm, UpdateChannel.BETA, BETA_NOW, BETA_PERIODIC, forceImmediate)
    }

    /**
     * Called only after the user confirms leaving Beta. The stable worker runs
     * immediately and then periodically, so if today's stable is still behind
     * the installed Beta it waits until a compatible stable release appears.
     */
    fun followStable(context: Context) = followStable(context, forceImmediate = true)

    private fun followStable(context: Context, forceImmediate: Boolean) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        UpdateReadyStore.clearIfDifferentChannel(appContext, UpdateChannel.STABLE)
        UpdateAvailableStore.clearIfDifferentChannel(appContext, UpdateChannel.STABLE)
        cancelLegacy(wm)
        wm.cancelUniqueWork(BETA_NOW)
        wm.cancelUniqueWork(BETA_PERIODIC)
        enqueueChannel(wm, UpdateChannel.STABLE, STABLE_NOW, STABLE_PERIODIC, forceImmediate)
    }

    fun stopAutomaticUpdates(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        cancelLegacy(wm)
        wm.cancelUniqueWork(BETA_NOW)
        wm.cancelUniqueWork(BETA_PERIODIC)
        wm.cancelUniqueWork(STABLE_NOW)
        wm.cancelUniqueWork(STABLE_PERIODIC)
        BetaUpdateNotifier.cancel(context.applicationContext)
        UpdateReadyStore.clear(context.applicationContext, deleteApk = true)
        UpdateAvailableStore.clear(context.applicationContext)
        BetaUpdateDownloader.clear(context.applicationContext)
    }

    private fun enqueueChannel(
        wm: WorkManager,
        channel: UpdateChannel,
        immediateName: String,
        periodicName: String,
        forceImmediate: Boolean,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
            .putString(BetaUpdateWorker.INPUT_CHANNEL, channel.wireName)
            .build()

        wm.enqueueUniqueWork(
            immediateName,
            if (forceImmediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BetaUpdateWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )

        wm.enqueueUniquePeriodicWork(
            periodicName,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BetaUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(input)
                .build(),
        )
    }

    private fun cancelLegacy(wm: WorkManager) {
        wm.cancelUniqueWork(LEGACY_PERIODIC)
        wm.cancelUniqueWork(LEGACY_SEED)
    }
}
