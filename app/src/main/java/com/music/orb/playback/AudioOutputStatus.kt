package com.music.orb.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import com.music.orb.data.settings.OutputPcmMode
import kotlinx.coroutines.flow.MutableStateFlow

/** Live description of the route Orb is asking Android to use. */
object AudioOutputStatus {
    data class Snapshot(
        val sink: String = "AudioTrack",
        val requestedPcmMode: OutputPcmMode = OutputPcmMode.PCM_16,
        val deviceName: String = "System default",
        val sampleRatesHz: IntArray = IntArray(0),
        val encodings: IntArray = IntArray(0),
        val isUsb: Boolean = false,
        val actualEncoding: Int? = null,
        val actualSampleRateHz: Int? = null,
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
            sampleRatesHz = device?.sampleRates ?: IntArray(0),
            encodings = device?.encodings ?: IntArray(0),
            isUsb = device?.type in setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
            ),
            actualEncoding = if (floatEnabled) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT,
            actualSampleRateHz = device?.sampleRates?.firstOrNull(),
            floatFallback = requestedPcmMode == OutputPcmMode.FLOAT_32 && !floatEnabled,
        )
    }

    fun encodingLabel(snapshot: Snapshot): String = when (snapshot.actualEncoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        AudioFormat.ENCODING_PCM_16BIT -> if (snapshot.floatFallback) "16-bit fallback" else "16-bit PCM"
        else -> snapshot.requestedPcmMode.label
    }
}
