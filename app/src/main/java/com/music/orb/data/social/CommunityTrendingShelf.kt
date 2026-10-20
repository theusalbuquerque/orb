package com.music.orb.data.social

import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stats/Global uses the community's actual 7-day song ranking rather than
 * YouTube's country-dependent trending order. Rankings contain names and art,
 * not playable video IDs, so resolve each candidate by recording identity.
 * A failed rematch is omitted, never replaced by an unrelated search hit.
 */
object CommunityTrendingShelf {
    private const val TITLE = "Global Top 10"
    private const val MAX_ENTRIES = 10
    private val simultaneousLookups = Semaphore(2)

    suspend fun loadTopTen(): HomeShelf? {
        val ranking = try {
            withTimeoutOrNull(5_000L) { CommunityStatsRepository.rankings(7) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val songs = ranking.asSequence()
            .filter { it.category == "song" && it.title.isNotBlank() && !it.subtitle.isNullOrBlank() }
            .sortedByDescending { it.plays }
            .distinctBy { "${it.title.trim().lowercase()}|${it.subtitle.orEmpty().trim().lowercase()}" }
            .take(MAX_ENTRIES)
            .toList()
        if (songs.isEmpty()) return null

        // The independent lookups must never delay the first rendering of the
        // Home/Trending page, and are capped to protect mobile data and playback.
        val items = supervisorScope {
                songs.mapIndexed { rank, row ->
                    async {
                        simultaneousLookups.withPermit {
                            val artist = row.subtitle.orEmpty()
                            val candidates = try {
                                withTimeoutOrNull(4_000L) {
                                    YtMusicRepository.search("${row.title} $artist", SearchFilter.SONGS)
                                        .getOrNull().orEmpty()
                                        .filterIsInstance<SearchResult.Track>()
                                        .map { it.song }
                                }.orEmpty()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val match = TrackMatcher.best(
                                candidates,
                                TrackMatcher.Target(title = row.title, artist = artist),
                            ) ?: return@withPermit null
                            ShelfItem(
                                title = row.title,
                                subtitle = artist,
                                thumbnailUrl = row.artworkUrl ?: match.thumbnailUrl,
                                videoId = match.videoId,
                                browseId = null,
                                isExplicit = match.isExplicit,
                                rank = rank + 1,
                            )
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.videoId }
        }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(TITLE, it) }
    }
}
