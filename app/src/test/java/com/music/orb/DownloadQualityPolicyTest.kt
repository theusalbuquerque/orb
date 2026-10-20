package com.music.orb

import com.music.orb.data.sources.LosslessTier
import com.music.orb.download.DownloadQualityPolicy
import com.music.orb.download.OfflineDownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQualityPolicyTest {
    @Test fun `maximum attempts HiRes then Lossless without implicit AAC`() {
        val attempts = DownloadQualityPolicy.attempts(OfflineDownloadQuality.MAXIMUM, false, false)
        assertEquals(listOf(LosslessTier.HI_RES_LOSSLESS, LosslessTier.LOSSLESS),
            attempts.map { it.losslessPreference })
        assertTrue(attempts.all { it.quality == OfflineDownloadQuality.MAXIMUM })
    }

    @Test fun `explicit fallback permits AAC only after both lossless tiers`() {
        val attempts = DownloadQualityPolicy.attempts(OfflineDownloadQuality.MAXIMUM, true, false)
        assertEquals(listOf(OfflineDownloadQuality.MAXIMUM, OfflineDownloadQuality.MAXIMUM,
            OfflineDownloadQuality.HIGH, OfflineDownloadQuality.MEDIUM), attempts.map { it.quality })
    }

    @Test fun `high never drops to medium without permission`() {
        assertEquals(listOf(OfflineDownloadQuality.HIGH),
            DownloadQualityPolicy.attempts(OfflineDownloadQuality.HIGH, false, false).map { it.quality })
        assertEquals(listOf(OfflineDownloadQuality.HIGH, OfflineDownloadQuality.MEDIUM),
            DownloadQualityPolicy.attempts(OfflineDownloadQuality.HIGH, true, false).map { it.quality })
    }

    @Test fun `medium never drops further`() {
        assertEquals(listOf(OfflineDownloadQuality.MEDIUM),
            DownloadQualityPolicy.attempts(OfflineDownloadQuality.MEDIUM, true, false).map { it.quality })
    }

    @Test fun `verified playback is only a HiRes shortcut not a downgrade`() {
        val attempts = DownloadQualityPolicy.attempts(OfflineDownloadQuality.MAXIMUM, false, true)
        assertTrue(attempts.first().usePlaybackStream)
        assertFalse(attempts.drop(1).any { it.usePlaybackStream })
        assertTrue(attempts.all { it.quality == OfflineDownloadQuality.MAXIMUM })
    }
}
