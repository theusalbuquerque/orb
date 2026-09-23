package com.music.orb.data.sources.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class AddonManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("resources") val resources: List<String> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { id }
    val isPlayable: Boolean
        get() = resources.isEmpty() || resources.any { it.equals("search", true) }
}

@Serializable
data class AddonSearchResponse(
    @SerialName("tracks") val tracks: List<AddonTrack> = emptyList(),
)

@Serializable
data class AddonTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("duration") val duration: Double? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("albumArtworkURL") val albumArtworkURL: String? = null,
    @SerialName("format") val format: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("streamURL") val streamURL: String? = null,
) {
    val durationSec: Int? get() = duration?.takeIf { it > 0 }?.toInt()
    val artwork: String? get() = artworkURL?.ifBlank { null } ?: albumArtworkURL?.ifBlank { null }
}

@Serializable
data class AddonStream(
    @SerialName("url") val url: String = "",
    @SerialName("format") val format: String = "",
    @SerialName("quality") val quality: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("codec") val codec: String? = null,
    @SerialName("container") val container: String? = null,
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("encrypted") val encrypted: JsonElement? = null,
    @SerialName("sampleRate") val sampleRate: Double? = null,
    @SerialName("bitDepth") val bitDepth: Double? = null,
    @SerialName("bitrate") val bitrate: Double? = null,
    @SerialName("error") val error: String? = null,
) {
    val statedCodec: String?
        get() = codec?.ifBlank { null }?.lowercase()
            ?: container?.ifBlank { null }?.lowercase()
            ?: mimeType?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase()

    val qualityText: String get() = "$quality $audioQuality $format"

    val isEncrypted: Boolean
        get() {
            val primitive = encrypted as? JsonPrimitive ?: return false
            primitive.booleanOrNull?.let { return it }
            return primitive.content.isNotBlank() &&
                !primitive.content.equals("false", true) &&
                !primitive.content.equals("none", true)
        }

    val sampleRateHz: Int?
        get() = sampleRate?.takeIf { it > 0 }?.let {
            if (it < 1000) (it * 1000).toInt() else it.toInt()
        }

    val bits: Int? get() = bitDepth?.takeIf { it > 0 }?.toInt()

    val kbps: Int?
        get() = bitrate?.takeIf { it > 0 }?.let {
            if (it > 3_000) (it / 1000).toInt() else it.toInt()
        }
}

open class AddonException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AddonUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
class AddonNotFound : Exception("Not found")
