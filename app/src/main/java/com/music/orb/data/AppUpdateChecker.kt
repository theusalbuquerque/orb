package com.music.orb.data

import com.music.orb.data.update.BetaUpdateChecker
import com.music.orb.data.update.OrbRelease
import com.music.orb.data.update.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Stable foreground update notice used when automatic update channels are not
 * enabled. The actual release parsing is shared with the automatic updater so
 * the popup has the same APK, changelog and hero metadata whichever route
 * discovered the release.
 */
object AppUpdateChecker {

    data class UpdateInfo(val release: OrbRelease) {
        val version: String get() = release.tagName.removePrefix("v")
        val releaseUrl: String get() = release.releaseUrl
    }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    suspend fun check() = withContext(Dispatchers.IO) {
        runCatching {
            val release = BetaUpdateChecker.latest(UpdateChannel.STABLE)
            _available.value = release
                ?.takeIf { BetaUpdateChecker.looksNewerThanInstalled(it) }
                ?.let(::UpdateInfo)
        }.onFailure {
            // A failed refresh must never keep an obsolete in-memory prompt.
            _available.value = null
        }
    }
}
