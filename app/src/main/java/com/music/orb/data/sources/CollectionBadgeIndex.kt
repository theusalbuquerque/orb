package com.music.orb.data.sources

import android.content.Context
import com.music.orb.data.LocalMediaRepository
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.Song
import com.music.orb.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.orb.data.spotify.SpotifyResolver
import com.music.orb.download.OfflinePlaylistStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-playing, metadata-only collection index for badges outside Detail.
 * The shared resolver checks *every* discovered track for previously validated
 * playback / offline Lossless. This code never assumes the first N tracks are
 * representative and never triggers an audio download or source upgrade.
 */
object CollectionBadgeIndex {
    private val gate = Semaphore(2)
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    private val indexedAt = ConcurrentHashMap<String, Long>()
    private const val INDEX_VALID_FOR_MS = 15 * 60_000L
    private const val MAX_BROWSE_PAGES = 100

    suspend fun ensureIndexed(context: Context, browseId: String?, type: BrowseType) {
        if (type != BrowseType.ALBUM && type != BrowseType.PLAYLIST) return
        val id = browseId?.trim()?.takeIf(String::isNotBlank) ?: return
        if (!SourceResolver.hasVerifiedLosslessTracks()) return
        val key = id.removePrefix("VL")
        if (System.currentTimeMillis() - (indexedAt[key] ?: 0L) < INDEX_VALID_FOR_MS) return

        inFlight.getOrPut(key) { Mutex() }.withLock {
            if (System.currentTimeMillis() - (indexedAt[key] ?: 0L) < INDEX_VALID_FOR_MS) return@withLock
            gate.withPermit {
                // This scan is lower priority than opening the actual page.
                // A bounded time budget prevents one massive playlist from
                // monopolizing the catalogue connection indefinitely.
                val ids = withTimeoutOrNull(90_000L) {
                    when {
                        id == "local:downloads" -> LocalMediaRepository.getDownloadedSongs(context)
                            .map(Song::videoId)
                        id == "local:all" -> LocalMediaRepository.getLocalMusic(context)
                            .map(Song::videoId)
                        id.startsWith("local:") -> OfflinePlaylistStore.playlists.value
                            .firstOrNull { it.browseId == id }?.trackIds
                        id.startsWith(SPOTIFY_PAGE_PREFIX) -> SpotifyResolver
                            .resolve(id.removePrefix(SPOTIFY_PAGE_PREFIX)).songs.map(Song::videoId)
                        else -> {
                            val first = YtMusicRepository.browseSongsV151(id).getOrNull()
                                ?: return@withTimeoutOrNull null
                            val found = LinkedHashSet<String>()
                            first.songs.mapTo(found) { it.videoId }
                            SourceResolver.cacheCollectionTrackIds(id, found.toList())
                            var token = first.continuation
                            val seenTokens = HashSet<String>()
                            var pages = 1
                            while (token != null && seenTokens.add(token) && pages < MAX_BROWSE_PAGES) {
                                val next = YtMusicRepository.moreSongsV151(token).getOrNull() ?: break
                                val previousCount = found.size
                                next.songs.mapTo(found) { it.videoId }
                                // Publish in intervals, not after the entire
                                // pagination: verified tracks can be near the
                                // end of a long playlist and should light up
                                // as soon as the threshold is reached.
                                if (found.size > previousCount && pages % 3 == 0) {
                                    SourceResolver.cacheCollectionTrackIds(id, found.toList())
                                }
                                token = next.continuation
                                pages++
                            }
                            found.toList()
                        }
                    }
                }
                if (ids != null) {
                    SourceResolver.cacheCollectionTrackIds(id, ids)
                    indexedAt[key] = System.currentTimeMillis()
                }
            }
        }
    }
}
