package com.music.orb

import com.music.orb.download.OfflineLosslessVerification
import com.music.orb.data.sources.StreamFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLosslessVerificationTest {
    private val promised = StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)

    @Test fun `accepts verifiable FLAC even without native bit depth`() {
        assertTrue(OfflineLosslessVerification.accepts(promised,
            StreamFormat(codec = "flac", sampleRateHz = 96_000)))
    }

    @Test fun `accepts actual CD quality FLAC without mislabeling it HiRes`() {
        val cd = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
        assertTrue(OfflineLosslessVerification.accepts(promised, cd))
        assertFalse(cd.losslessTier.isHiRes)
    }

    @Test fun `maximum accepts both sixteen and twenty four bit native FLAC`() {
        val cd = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16)
        val studio = StreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24)
        assertTrue(OfflineLosslessVerification.accepts(promised, cd))
        assertTrue(OfflineLosslessVerification.accepts(promised, studio))
        assertFalse(cd.losslessTier.isHiRes)
        assertTrue(studio.losslessTier.isHiRes)
    }

    @Test fun `rejects unknown or lossy codecs even if they claim HiRes numbers`() {
        assertFalse(OfflineLosslessVerification.accepts(promised, null))
        assertFalse(OfflineLosslessVerification.accepts(promised,
            StreamFormat(codec = "aac", sampleRateHz = 96_000, bitDepth = 24)))
    }

    @Test fun `rejects a container codec different from the requested one`() {
        assertFalse(OfflineLosslessVerification.accepts(promised, StreamFormat(codec = "wav")))
    }
}
