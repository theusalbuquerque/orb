package com.music.orb.data.sources

import com.music.orb.data.model.Song

/**
 * Native Orb TIDAL Lossless source backed by hifi-api.
 *
 * The Android app holds no TIDAL credential. [TidalService] tries Orb's
 * Cloudflare Worker first, then compatible public mirrors and finally the
 * local hifi-api instance. Playback is accepted only when the returned media
 * is real FLAC; upstream AAC downgrades are rejected.
 */
class TidalSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String
        get() = config.id

    override val kind: SourceKind
        get() = SourceKind.TIDAL

    override val displayName: String
        get() = config.label.ifBlank { SourceKind.TIDAL.label }

    override suspend fun health(): SourceHealth =
        if (TidalService.health()) {
            SourceHealth.Ok("Orb hifi-api available")
        } else {
            SourceHealth.Unreachable("No hifi-api endpoint is reachable")
        }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
    ): List<Song> = TidalService.search(query, limit).map { track ->
        Song(
            videoId = SourceRegistry.trackKey(config.id, track.id.toString()),
            // TIDAL keeps remix/edit descriptors separately from title. Fold
            // them into resolver-only metadata so the matcher distinguishes
            // the correct recording without changing Orb's UI metadata.
            title = track.version
                ?.takeIf { it.isNotBlank() }
                ?.let { "${track.title} ($it)" }
                ?: track.title,
            artist = track.artists.joinToString(", "),
            albumId = track.albumId?.toString(),
            albumName = track.album,
            releaseYear = track.releaseYear,
            durationText = track.durationSec?.let { seconds ->
                "${seconds / 60}:${"%02d".format(seconds % 60)}"
            },
            thumbnailUrl = null,
            sourceQuality = when (track.losslessTier) {
                LosslessTier.HI_RES_LOSSLESS -> "HI_RES_LOSSLESS"
                LosslessTier.LOSSLESS -> "LOSSLESS"
                LosslessTier.NONE -> track.audioQuality ?: "TIDAL"
            },
            isExplicit = track.isExplicit == true,
        )
    }

    /**
     * Fills release metadata omitted by lightweight hifi-api search responses.
     * Kept available for strict/background matching without delaying first note.
     */
    internal suspend fun enrichCandidateMetadata(song: Song): Song {
        if (song.releaseYear != null && !song.albumName.isNullOrBlank()) return song
        val albumId = song.albumId?.toLongOrNull() ?: return song
        val metadata = TidalService.albumMetadata(albumId) ?: return song
        return song.copy(
            albumName = song.albumName ?: metadata.title,
            releaseYear = song.releaseYear ?: metadata.releaseYear,
        )
    }

    override suspend fun stream(
        trackId: String,
        request: StreamRequest,
    ): SourceStream? {
        if (request !is StreamRequest.Lossless) return null

        val id = trackId.toLongOrNull() ?: return null
        val stream = TidalService.bestLosslessStream(id) ?: return null
        if (stream.codec.lowercase() != "flac") return null

        val tier = when {
            stream.audioQuality?.contains("HI_RES", ignoreCase = true) == true ||
                stream.audioQuality?.contains("HIRES", ignoreCase = true) == true ||
                (stream.bitDepth ?: 0) > 16 ||
                (stream.sampleRateHz ?: 0) > 48_000 -> LosslessTier.HI_RES_LOSSLESS
            else -> LosslessTier.LOSSLESS
        }

        return SourceStream(
            url = stream.url,
            format = StreamFormat(
                codec = "flac",
                sampleRateHz = stream.sampleRateHz,
                bitDepth = stream.bitDepth,
                declaredLosslessTier = tier,
            ),
            isDash = stream.isDash,
            losslessVerified = stream.verifiedLossless,
        )
    }
}
