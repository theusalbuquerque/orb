package com.music.orb.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import com.music.orb.data.settings.OutputPcmMode
import kotlinx.coroutines.flow.MutableStateFlow

object AudioOutputStatus {
    data class Snapshot(
        val requestedPcmMode: OutputPcmMode = OutputPcmMode.PCM_16,
        val deviceName: String = "System default",
        val actualEncoding: Int? = null,
        val floatFallback: Boolean = false,
    )

    val current = MutableStateFlow(Snapshot())

    fun publish(
        manager: AudioManager,
        requestedPcmMode: OutputPcmMode,
        preferred: AudioDeviceInfo?,
        floatEnabled: Boolean,
    ) {
        val device = preferred ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink }
        current.value = Snapshot(
            requestedPcmMode = requestedPcmMode,
            deviceName = device?.productName?.toString()?.ifBlank { null } ?: "System default",
            actualEncoding = if (floatEnabled) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT,
            floatFallback = requestedPcmMode == OutputPcmMode.FLOAT_32 && !floatEnabled,
        )
    }
}
