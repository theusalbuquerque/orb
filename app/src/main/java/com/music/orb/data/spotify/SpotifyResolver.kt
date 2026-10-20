package com.music.orb.data.spotify

import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.Song
import com.music.orb.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object SpotifyResolver {
    data class Result(val songs: List<Song>, val unmatched: Int)
    suspend fun resolve(playlistId: String): Result = coroutineScope {
        val tracks = SpotifyLibrary.tracks(playlistId)
        val semaphore = Semaphore(3)
        val songs = tracks.map { track -> async {
            semaphore.withPermit {
                try {
                    val candidates = YtMusicRepository.search("${track.title} ${track.artist}", SearchFilter.SONGS)
                        .getOrThrow().filterIsInstance<SearchResult.Track>().map { it.song }
                    TrackMatcher.best(candidates, TrackMatcher.Target(
                        title = track.title, artist = track.artist,
                        durationSec = track.durationMs.takeIf { it > 0 }?.div(1000), albumName = track.album,
                    ))
                } catch (cancelled: CancellationException) { throw cancelled }
            }
        } }.awaitAll()
        Result(songs.filterNotNull(), songs.count { it == null })
    }
}
