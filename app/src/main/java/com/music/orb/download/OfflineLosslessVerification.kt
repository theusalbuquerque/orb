package com.music.orb.download

import com.music.orb.data.sources.StreamFormat

/**
 * File acceptance is based on the native codec, not optional MediaExtractor fields.
 * A confirmed FLAC with an unknown bit depth is still Lossless, but it is not
 * safe to label it Hi-Res unless the native header proves the resolution.
 */
internal object OfflineLosslessVerification {
    fun accepts(expected: StreamFormat, native: StreamFormat?): Boolean {
        if (expected.isLossless != true || native?.isLossless != true) return false
        // A WAV or AAC in a FLAC-labelled download is not the requested container.
        // Codec equality also prevents treating file-extension guesses as proof.
        return expected.codec?.lowercase() == native.codec?.lowercase()
    }
}
