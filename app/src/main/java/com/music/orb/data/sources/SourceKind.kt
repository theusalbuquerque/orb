package com.music.orb.data.sources

/**
 * Audio source protocols used by the BitChord v1.5 playback policy.
 * Declaration order is playback priority.
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    val labels: List<String>,
    val needsServer: Boolean,
    val canServeLossless: Boolean,
    val worthPrefetching: Boolean = false,
) {
    TIDAL(
        label = "TIDAL HiFi API",
        detail = "Native Orb Lossless/Hi-Res source backed by hifi-api, with automatic endpoint fallback.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Built-in"),
        needsServer = false,
        canServeLossless = true,
    ),
    ADDON(
        label = "HTTP addon",
        detail = "Private HTTP audio source such as Orb Navidrome Addon.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Addon"),
        needsServer = true,
        canServeLossless = true,
    ),
    CUSTOM_MODULE(
        label = "Custom module",
        detail = "Your own compatible module index. Tried before the built-in one.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
    ),
    MODULE(
        label = "Module source",
        detail = "A compatible module index for services such as Tidal, Qobuz and Apple Music.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
    ),
    JIOSAAVN(
        label = "JioSaavn",
        detail = "High-quality AAC/MP4 streams up to 320 kbps, tried before YouTube Music.",
        labels = listOf("High Quality", "320kbps"),
        needsServer = false,
        canServeLossless = false,
        worthPrefetching = true,
    ),
    YOUTUBE(
        label = "YouTube Music",
        detail = "Full catalogue, Opus up to about 171 kbps; final playback fallback.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
    ),
}
