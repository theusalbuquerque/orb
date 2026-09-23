package com.music.orb.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal data class ApkCompatibility(
    val compatible: Boolean,
    val reason: String,
    val archiveVersionCode: Long? = null,
)

internal object BetaUpdateInstaller {

    const val EXTRA_APK_NAME = "orb_update_apk_name"
    const val EXTRA_CHANNEL = "orb_update_channel"
    const val PACKAGE_NAME = "com.music.orb"

    fun inspect(context: Context, apk: File): ApkCompatibility {
        val pm = context.packageManager
        val installed = packageInfo(pm, context.packageName) ?: return ApkCompatibility(false, "installed package unavailable")
        val archive = archiveInfo(pm, apk) ?: return ApkCompatibility(false, "APK metadata unavailable")

        if (archive.packageName != PACKAGE_NAME || archive.packageName != context.packageName) {
            return ApkCompatibility(false, "wrong package: ${archive.packageName}")
        }

        val currentSignatures = signatureDigests(installed)
        val archiveSignatures = signatureDigests(archive)
        if (currentSignatures.isEmpty() || archiveSignatures.isEmpty() || currentSignatures != archiveSignatures) {
            return ApkCompatibility(false, "signing certificate does not match")
        }

        val currentCode = longVersionCode(installed)
        val archiveCode = longVersionCode(archive)
        if (archiveCode <= currentCode) {
            return ApkCompatibility(
                compatible = false,
                reason = "versionCode $archiveCode is not above installed $currentCode",
                archiveVersionCode = archiveCode,
            )
        }

        return ApkCompatibility(true, "ok", archiveVersionCode = archiveCode)
    }

    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.orb-updates.files",
            apk,
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        pm.getPackageArchiveInfo(apk.absolutePath, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
}
