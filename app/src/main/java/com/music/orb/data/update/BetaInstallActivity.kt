package com.music.orb.data.update

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Opens Android's own installer for an APK already downloaded by Orb. */
class BetaInstallActivity : ComponentActivity() {

    private var apkName: String? = null
    private var launched = false

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BetaUpdateInstaller.canRequestInstall(this)) launchInstaller() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkName = intent.getStringExtra(BetaUpdateInstaller.EXTRA_APK_NAME)

        val apk = apkName?.let { BetaUpdateDownloader.byName(this, it) }
        if (apk == null || !BetaUpdateInstaller.inspect(this, apk).compatible) {
            finish()
            return
        }

        if (BetaUpdateInstaller.canRequestInstall(this)) {
            launchInstaller()
        } else {
            unknownSourcesLauncher.launch(BetaUpdateInstaller.unknownSourcesIntent(this))
        }
    }

    private fun launchInstaller() {
        if (launched) return
        launched = true

        val apk = apkName?.let { BetaUpdateDownloader.byName(this, it) }
        if (apk == null || !BetaUpdateInstaller.inspect(this, apk).compatible) {
            finish()
            return
        }

        runCatching { startActivity(BetaUpdateInstaller.installIntent(this, apk)) }
        finish()
    }
}
