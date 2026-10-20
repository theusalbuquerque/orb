package com.music.orb.download

import com.music.orb.data.sources.LosslessTier

/** One ordered attempt per permitted quality tier. No hidden automatic downgrade. */
internal object DownloadQualityPolicy {
    data class Step(
        val quality: OfflineDownloadQuality,
        val losslessPreference: LosslessTier = LosslessTier.HI_RES_LOSSLESS,
        val usePlaybackStream: Boolean = false,
    )

    fun attempts(
        requestedLevel: OfflineDownloadQuality,
        allowLowerQuality: Boolean,
        hasVerifiedHiResPlayback: Boolean,
    ): List<Step> = buildList {
        when (requestedLevel) {
            OfflineDownloadQuality.MAXIMUM -> {
                if (hasVerifiedHiResPlayback) {
                    add(Step(OfflineDownloadQuality.MAXIMUM, usePlaybackStream = true))
                }
                add(Step(OfflineDownloadQuality.MAXIMUM, LosslessTier.HI_RES_LOSSLESS))
                // Lower lossless resolution is still part of Maximum; AAC is not.
                add(Step(OfflineDownloadQuality.MAXIMUM, LosslessTier.LOSSLESS))
                if (allowLowerQuality) {
                    add(Step(OfflineDownloadQuality.HIGH))
                    add(Step(OfflineDownloadQuality.MEDIUM))
                }
            }
            OfflineDownloadQuality.HIGH -> {
                add(Step(OfflineDownloadQuality.HIGH))
                if (allowLowerQuality) add(Step(OfflineDownloadQuality.MEDIUM))
            }
            OfflineDownloadQuality.MEDIUM -> add(Step(OfflineDownloadQuality.MEDIUM))
        }
    }
}
