package com.music.orb.data.sources

import com.music.orb.data.TrackLog
import com.music.orb.data.jiosaavn.JioSaavnService
import com.music.orb.data.model.Song

class JioSaavnSource(override val config: SourceConfig) : MusicSource, SourceRegistry.ConfigBacked {
    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.JIOSAAVN
    override val displayName: String get() = config.label.ifBlank { SourceKind.JIOSAAVN.label }
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> =
        JioSaavnService.searchSongs(query).take(limit).map { raw ->
            val artist = raw.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }
            Song(
                videoId = SourceRegistry.trackKey(config.id, raw.id),
                title = raw.title,
                artist = artist,
                albumName = raw.moreInfo.album.ifBlank { null },
                thumbnailUrl = raw.image.replace(Regex("150x150|50x50"), "500x500").replace(Regex("^http://"), "https://"),
                durationText = raw.moreInfo.duration.toIntOrNull()?.let { "%d:%02d".format(it / 60, it % 60) },
                sourceQuality = "HIGH",
            )
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val stream = JioSaavnService.getStreamUrl(trackId) ?: return null
        if (stream.url.isBlank()) return null
        if (stream.kbps != null && stream.kbps <= 96) {
            TrackLog.d("BitChord", "JioSaavn ${stream.kbps} kbps refused; YouTube is the better fallback")
            return null
        }
        return SourceStream(stream.url, StreamFormat(codec = "mp4", kbps = stream.kbps))
    }
}
