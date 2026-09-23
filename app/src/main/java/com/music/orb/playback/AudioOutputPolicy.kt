package com.music.orb.playback

import com.music.orb.data.settings.OutputPcmMode

/**
 * Keeps PCM float away from unstable Android speaker/vendor paths.
 * Float is enabled only when the user explicitly asks for it and a preferred
 * external USB route advertises PCM_FLOAT support.
 */
internal object AudioOutputPolicy {
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    /** Samsung vendor FLAC decoders are known to misbehave with float output. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }
}
