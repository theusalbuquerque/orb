package com.music.orb.data.sources

import com.music.orb.data.TrackLog
import com.music.orb.data.model.Song
import com.music.orb.data.sources.addon.AddonClient
import com.music.orb.data.sources.addon.AddonException
import com.music.orb.data.sources.addon.AddonNotFound
import com.music.orb.data.sources.addon.AddonStream
import com.music.orb.data.sources.addon.AddonTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

/** Progressive HTTP addon source used by Orb Navidrome Addon. */
class AddonSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {
    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.ADDON
    override val displayName: String get() = config.displayName

    private val client = AddonClient(config.baseUrl)
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > MAX_ROWS
        },
    )

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext SourceHealth.Rejected("An addon URL is required")
        client.manifest().fold(
            onSuccess = { manifest ->
                SourceHealth.Ok(
                    listOfNotNull(
                        manifest.displayName.ifBlank { null },
                        manifest.version.ifBlank { null }?.let { "v$it" },
                    ).joinToString(" ").ifBlank { null },
                )
            },
            onFailure = { failure ->
                if (failure is AddonException || failure is AddonNotFound) {
                    SourceHealth.Rejected(failure.message ?: "Invalid addon")
                } else {
                    SourceHealth.Unreachable(failure.message ?: "Could not reach the addon")
                }
            },
        )
    }

    suspend fun manifestName(): String? =
        client.manifest().getOrNull()?.displayName?.ifBlank { null }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val tracks = client.search(query).getOrElse { failure ->
                TrackLog.w(TAG, "${config.displayName}: addon search failed — ${failure.message}")
                return@withContext emptyList()
            }
            tracks.asSequence()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .take(limit)
                .map { track ->
                    rows[track.id] = track
                    Song(
                        videoId = SourceRegistry.trackKey(config.id, track.id),
                        title = track.title,
                        artist = track.artist,
                        albumName = track.album.ifBlank { null },
                        thumbnailUrl = track.artwork,
                        durationText = track.durationSec?.let {
                            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
                        },
                        sourceQuality = ModuleSource.qualityTier("${track.audioQuality} ${track.format}"),
                    )
                }
                .toList()
        }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            // Orb Navidrome serves the original file. Never let a raw FLAC bypass
            // the user's lossy ceiling on normal High/Medium/Low playback.
            if (request !is StreamRequest.Lossless) return@withContext null

            val result = client.stream(trackId)
            val answer = result.getOrNull()
            if (answer == null) {
                val failure = result.exceptionOrNull()
                if (failure !is AddonNotFound) {
                    TrackLog.w(TAG, "${config.displayName}: addon stream failed for $trackId — ${failure?.message}")
                }
                return@withContext null
            }
            val url = answer.url.ifBlank { null } ?: return@withContext null
            if (ModuleSource.malformed(url) || answer.isEncrypted) return@withContext null

            val format = formatOf(answer, url)
            if (format.isLossless != true) {
                // A Navidrome library may contain MP3/AAC files. Those are valid
                // library tracks, but they are not a Lossless upgrade candidate.
                return@withContext null
            }

            SourceStream(
                url = url,
                format = format,
                belowRequest = false,
                durationSec = rows[trackId]?.durationSec,
            )
        }

    private fun formatOf(answer: AddonStream, url: String): StreamFormat {
        val qualityTier = ModuleSource.qualityTier(answer.qualityText)
        val codec = answer.statedCodec?.takeIf { it in AUDIO_CODECS }
            ?: url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)
                .takeIf { it in AUDIO_CODECS }
            ?: if (qualityTier == ModuleSource.LOSSLESS) "flac" else null

        val declaredTier = if (codec in LOSSLESS_CODECS || qualityTier == ModuleSource.LOSSLESS) {
            if ((answer.bits ?: 0) > 16 || (answer.sampleRateHz ?: 0) > 48_000 ||
                HI_RES_HINT.containsMatchIn(answer.qualityText)
            ) {
                LosslessTier.HI_RES_LOSSLESS
            } else {
                LosslessTier.LOSSLESS
            }
        } else null

        return StreamFormat(
            codec = codec,
            kbps = answer.kbps,
            sampleRateHz = answer.sampleRateHz,
            bitDepth = answer.bits,
            declaredLosslessTier = declaredTier,
        )
    }

    fun release() {
        rows.clear()
        client.close()
    }

    private companion object {
        const val TAG = "OrbAddon"
        const val MAX_ROWS = 256
        val AUDIO_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "mp3", "aac", "m4a", "mp4",
            "ogg", "opus", "vorbis", "webm",
        )
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff")
        val HI_RES_HINT = Regex("hi[-_ ]?res|24[- ]?bit|96\\s*kHz|192\\s*kHz", RegexOption.IGNORE_CASE)
    }
}
