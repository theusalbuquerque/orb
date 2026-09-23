package com.music.orb.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Visual mode used by the in-app software-update card. */
enum class SoftwareUpdateDialogMode {
    AVAILABLE,
    DOWNLOADING,
    READY,
}

data class ActiveUpdateDownload(
    val release: OrbRelease,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
) {
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * Process-local bridge from WorkManager / foreground downloads to Compose.
 *
 * A download only needs a live popup while the app process is visible; the
 * completed state is persisted separately by [UpdateReadyStore].
 */
object UpdateDownloadStore {
    private val _active = MutableStateFlow<ActiveUpdateDownload?>(null)
    val active = _active.asStateFlow()

    @Volatile
    private var cancelledTag: String? = null

    fun begin(release: OrbRelease, totalBytes: Long? = null) {
        cancelledTag = null
        _active.value = ActiveUpdateDownload(release = release, totalBytes = totalBytes)
    }

    fun progress(release: OrbRelease, downloadedBytes: Long, totalBytes: Long?) {
        val current = _active.value
        if (current?.release?.tagName != release.tagName) {
            begin(release, totalBytes)
        }
        _active.value = ActiveUpdateDownload(
            release = release,
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.takeIf { it > 0L },
        )
    }

    fun requestCancel(tagName: String) {
        cancelledTag = tagName
    }

    fun isCancelRequested(tagName: String): Boolean = cancelledTag == tagName

    fun finish(tagName: String) {
        if (_active.value?.release?.tagName == tagName) _active.value = null
        if (cancelledTag == tagName) cancelledTag = null
    }
}

internal class UpdateDownloadCancelledException : Exception()
