package com.music.orb.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.music.orb.MainActivity
import com.music.orb.R
import java.io.File

internal object BetaUpdateNotifier {

    private const val CHANNEL_ID = "orb_app_updates"
    private const val NOTIFICATION_ID = 0x0B37A

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED


    fun showAvailable(context: Context, release: OrbRelease): Boolean {
        if (!BetaUpdateChecker.looksNewerThanInstalled(release)) {
            cancel(context)
            return false
        }
        if (!canNotify(context)) return false
        createChannel(context)

        val openOrb = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            ("available:" + release.channel.wireName + release.tagName).hashCode(),
            openOrb,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (release.channel) {
            UpdateChannel.BETA -> context.getString(R.string.beta_updates_notification_available_title_beta)
            UpdateChannel.STABLE -> context.getString(R.string.beta_updates_notification_available_title_stable)
        }
        val body = context.getString(R.string.beta_updates_notification_available_body, release.displayName)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    fun showReady(context: Context, release: OrbRelease, apk: File): Boolean {
        if (!BetaUpdateChecker.looksNewerThanInstalled(release)) {
            cancel(context)
            return false
        }
        if (!canNotify(context) || !apk.isFile) return false
        createChannel(context)

        val install = Intent(context, BetaInstallActivity::class.java).apply {
            putExtra(BetaUpdateInstaller.EXTRA_APK_NAME, apk.name)
            putExtra(BetaUpdateInstaller.EXTRA_CHANNEL, release.channel.wireName)
        }
        val pending = PendingIntent.getActivity(
            context,
            (release.channel.wireName + release.tagName).hashCode(),
            install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (release.channel) {
            UpdateChannel.BETA -> context.getString(R.string.beta_updates_notification_title_beta)
            UpdateChannel.STABLE -> context.getString(R.string.beta_updates_notification_title_stable)
        }
        val body = context.getString(R.string.beta_updates_notification_body, release.displayName)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.beta_updates_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.beta_updates_channel_description)
            },
        )
    }
}
