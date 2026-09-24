package com.music.orb

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.auth.YtMusicLoginScreen
import com.music.orb.data.LocalMediaRepository
import com.music.orb.data.NerdStats
import com.music.orb.data.TrackLog
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.DetailPage
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.HEADER_ART_PX
import com.music.orb.data.model.artworkAt
import com.music.orb.data.model.LibraryPage
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.UiState
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.Song
import com.music.orb.data.model.UserPlaylist
import com.music.orb.data.model.YouTubeAccountIdentity
import com.music.orb.data.scrobbling.LastFM
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.HomeSuggestionStore
import com.music.orb.data.settings.RecentPlaybackStore
import com.music.orb.data.settings.ArtistPreference
import com.music.orb.data.settings.ArtistPreferenceStore
import com.music.orb.data.settings.FirstAccountOnboardingStore
import com.music.orb.data.settings.ThemeMode
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.SourceResolver
import com.music.orb.data.sources.TrackMatcher
import com.music.orb.data.update.BetaUpdateChecker
import com.music.orb.data.update.ManualUpdateChecker
import com.music.orb.data.update.ManualUpdateResult
import com.music.orb.data.update.SoftwareUpdateDialogMode
import com.music.orb.data.update.UpdateChannel
import com.music.orb.data.update.UpdateAvailableStore
import com.music.orb.data.update.UpdateDownloadStore
import com.music.orb.data.update.UpdateReadyStore
import com.music.orb.ui.screens.AccountIntegrationsScreen
import com.music.orb.ui.screens.SettingsScreen
import com.music.orb.ui.screens.SourcesScreen
import com.music.orb.playback.QueueBuilder
import com.music.orb.playback.QueueShuffle
import com.music.orb.playback.autoplaySectionStart
import com.music.orb.playback.albumSequential
import com.music.orb.playback.clearQueueKeepingCurrent
import com.music.orb.playback.clearPlaybackQueue
import com.music.orb.playback.dropAutoplayTracks
import com.music.orb.playback.playSongs
import com.music.orb.playback.moveMediaItemByUser
import com.music.orb.playback.toMediaItem
import com.music.orb.playback.toSong
import com.music.orb.download.DownloadStore
import com.music.orb.download.Downloads
import com.music.orb.ui.components.PlaylistActionsSheet
import com.music.orb.ui.components.PlaylistPickerSheet
import com.music.orb.ui.components.SongActionsSheet
import com.music.orb.ui.components.bestForegroundForArtworkSurface
import com.music.orb.ui.components.rememberArtworkBottomEdgeColor
import com.music.orb.playback.rememberMediaController
import com.music.orb.playback.rememberPlayerState
import com.music.orb.ui.MainViewModel
import com.music.orb.ui.FriendsViewModel
import com.music.orb.ui.StatsLeader
import com.music.orb.ui.StatsPeriod
import com.music.orb.ui.components.BottomFadeBlur
import com.music.orb.ui.components.YouTubeMusicFirstAccountDialog
import com.music.orb.ui.components.UsernameFirstAccountDialog
import com.music.orb.ui.components.BottomTab
import com.music.orb.ui.components.FloatingBottomBar
import com.music.orb.ui.components.FrostedTopBar
import com.music.orb.ui.components.HomeFrostedHeader
import com.music.orb.ui.components.ExploreFrostedHeader
import com.music.orb.ui.components.LibraryFrostedHeader
import com.music.orb.ui.components.StatsFrostedHeader
import com.music.orb.ui.components.LastfmLoginAlert
import com.music.orb.ui.components.ListenBrainzTokenAlert
import com.music.orb.ui.components.MiniPlayer
import com.music.orb.ui.components.TopBarAccountButton
import com.music.orb.ui.components.TopFadeBlur
import com.music.orb.ui.components.LyricsSourcesDialog
import com.music.orb.ui.components.UpdateAvailableDialog
import com.music.orb.ui.components.OrbNoticeHost
import com.music.orb.ui.components.OrbHaptics
import com.music.orb.ui.SongShareStoryRenderer
import com.music.orb.ui.notifications.OrbNotice
import com.music.orb.ui.notifications.OrbNoticeCenter
import com.music.orb.ui.notifications.OrbNoticeIcon
import com.music.orb.ui.icons.BitChordIcons
import androidx.media3.common.Player
import com.music.orb.BuildConfig
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.social.SocialRepository
import com.music.orb.ui.player.NowPlayingScreen
import com.music.orb.ui.player.NowPlayingLaunchOriginRegistry
import com.music.orb.ui.player.NowPlayingLaunchOriginKind
import com.music.orb.ui.screens.DetailScreen
import com.music.orb.ui.screens.LocalMusicScreen
import com.music.orb.ui.screens.HomeScreen
import com.music.orb.ui.screens.HOME_RECOMMENDATIONS_SHELF_TITLE
import com.music.orb.ui.screens.HOME_TOP_ARTISTS_SHELF_TITLE
import com.music.orb.ui.screens.HOME_FAVORITE_ALBUM_RELEASES_SHELF_TITLE
import com.music.orb.ui.screens.LibraryScreen
import com.music.orb.ui.screens.SearchScreen
import com.music.orb.ui.screens.FriendsScreen
import com.music.orb.ui.screens.WelcomeLoginScreen
import com.music.orb.ui.screens.OrbProfileScreen
import com.music.orb.ui.screens.PublicProfileScreen
import com.music.orb.ui.screens.ProfileTopBarInfo
import com.music.orb.ui.theme.BitChordTheme
import com.music.orb.ui.theme.rememberArtworkPalette
import com.music.orb.ui.theme.SystemBarIcons
import com.music.orb.ui.flavor.OrbFlavorUi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import coil3.compose.AsyncImage

private enum class AppContentKind {
    TAB,
    DETAIL,
    SETTINGS,
    SOURCES,
    ACCOUNT,
    ORB_PROFILE,
    PUBLIC_PROFILE,
}

/** Immutable navigation snapshot so AnimatedContent can compose the page leaving the screen. */
private data class AppContentTarget(
    val kind: AppContentKind,
    val tab: Int,
    val homeSection: Int,
    val detailDepth: Int,
    val detail: DetailPage? = null,
    val profileId: String? = null,
) {
    val key: String
        get() = when (kind) {
            AppContentKind.TAB -> "tab:$tab:home:$homeSection"
            AppContentKind.DETAIL -> "detail:$detailDepth:${detail?.browseId}"
            AppContentKind.PUBLIC_PROFILE -> "public-profile:$profileId"
            else -> kind.name
        }
}

private fun filteredHomeState(
    source: UiState<List<HomeShelf>>,
    predicate: (HomeShelf) -> Boolean,
): UiState<List<HomeShelf>> = when (source) {
    UiState.Loading -> UiState.Loading
    is UiState.Error -> source
    is UiState.Success -> UiState.Success(source.data.filter(predicate))
}

private fun normalizedShelfTitle(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace('&', ' ')
    .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
    .trim()

private fun distinctHomeItems(items: Iterable<ShelfItem>): List<ShelfItem> {
    val seen = HashSet<String>()
    return items.filter { item ->
        val key = item.videoId ?: item.browseId ?:
            "${item.title.lowercase(Locale.ROOT)}|${item.subtitle.lowercase(Locale.ROOT)}"
        seen.add(key)
    }
}


private const val HOME_SUGGESTION_LIMIT = 20

private fun isJukeboxShelfTitle(value: String): Boolean {
    val title = normalizedShelfTitle(value)
    return title.contains("jukebox") ||
        title.contains("speed dial") ||
        title.contains("accesos directos") ||
        title.contains("acessos diretos") ||
        title.contains("atalhos")
}

private fun isSupermixTitle(value: String): Boolean {
    val title = normalizedShelfTitle(value)
    return title.contains("supermix") || title.contains("super mix")
}

/**
 * Build Suggestions by rotating through the three sources the user asked for:
 * recent listening -> YouTube Music Digital Jukebox/Speed Dial -> My Supermix.
 *
 * The rotation is deliberate: one source can never monopolise the hero just
 * because it returned more rows. If one source is sparse, the others fill the
 * remaining slots, followed only then by the durable local fallback pool.
 */
private fun mixedSuggestionItems(
    recent: List<ShelfItem>,
    jukebox: List<ShelfItem>,
    supermix: List<ShelfItem>,
    fallback: List<ShelfItem> = emptyList(),
    limit: Int = HOME_SUGGESTION_LIMIT,
    fillRemainder: Boolean = true,
): List<ShelfItem> {
    fun clean(source: List<ShelfItem>): List<ShelfItem> {
        val seenVideoIds = HashSet<String>()
        val seenIdentity = HashSet<String>()
        return source.filter { item ->
            val videoId = item.videoId ?: return@filter false
            if (item.isVideo || item.thumbnailUrl.isYouTubeVideoThumbnail()) return@filter false
            val identity = "${normalizedShelfTitle(item.title)}|${normalizedShelfTitle(item.subtitle)}"
            seenVideoIds.add(videoId) && seenIdentity.add(identity)
        }
    }

    // Stable within a refresh generation, different across explicit refreshes.
    // Prefer candidates absent from the previous carousel before reusing tracks.
    val sources = listOf(recent, jukebox, supermix).mapIndexed { index, source ->
        clean(source)
            .shuffled(kotlin.random.Random(HomeRecommendationSession.sessionToken + index))
            .sortedBy { it.videoId in HomeRecommendationSession.previousVideoIds }
    }
    val positions = IntArray(sources.size)
    val addedFromSource = IntArray(sources.size)
    // 20 => 7 recent + 7 Jukebox + 6 Supermix in the balanced pass.
    // This is important during first-run discovery: if only Recents are ready
    // we expose at most seven of them rather than publishing ten recent songs
    // and making the hero look like a duplicate of "Tocadas recentemente".
    val quotas = intArrayOf(
        (limit + 2) / 3,
        (limit + 1) / 3,
        limit / 3,
    )
    val output = ArrayList<ShelfItem>(limit)
    val seenVideoIds = HashSet<String>()
    val seenIdentity = HashSet<String>()

    fun tryAdd(item: ShelfItem): Boolean {
        val videoId = item.videoId ?: return false
        val identity = "${normalizedShelfTitle(item.title)}|${normalizedShelfTitle(item.subtitle)}"
        if (!seenVideoIds.add(videoId) || !seenIdentity.add(identity)) return false
        output += item
        return true
    }

    // First pass is deliberately balanced and progressive. As Jukebox and
    // Supermix arrive, their cards are inserted between recent tracks instead
    // of waiting for all three sources to finish loading.
    while (output.size < limit) {
        var progressed = false
        sources.indices.forEach { sourceIndex ->
            if (addedFromSource[sourceIndex] >= quotas[sourceIndex]) return@forEach
            val source = sources[sourceIndex]
            while (positions[sourceIndex] < source.size && output.size < limit) {
                val item = source[positions[sourceIndex]++]
                if (tryAdd(item)) {
                    addedFromSource[sourceIndex] += 1
                    progressed = true
                    break
                }
            }
        }
        if (!progressed) break
    }

    if (!fillRemainder) return output

    // Once source discovery has settled, sparse sources may be back-filled by
    // the other requested sources so the carousel can still reach 20.
    while (output.size < limit) {
        var progressed = false
        sources.indices.forEach { sourceIndex ->
            val source = sources[sourceIndex]
            while (positions[sourceIndex] < source.size && output.size < limit) {
                val item = source[positions[sourceIndex]++]
                if (tryAdd(item)) {
                    progressed = true
                    break
                }
            }
        }
        if (!progressed) break
    }

    if (output.size < limit) {
        clean(fallback)
            .shuffled(kotlin.random.Random(HomeRecommendationSession.sessionToken))
            .sortedBy { it.videoId in HomeRecommendationSession.previousVideoIds }
            .forEach { item ->
            if (output.size >= limit) return@forEach
            tryAdd(item)
        }
    }
    return output
}

/**
 * YouTube video thumbnails (hqdefault/maxresdefault) are 16:9 frame grabs, not
 * catalogue artwork. They must never be used by the Suggestions carousel,
 * whose cards intentionally represent the music release/album cover.
 */
private fun String?.isYouTubeVideoThumbnail(): Boolean {
    val url = this?.lowercase(Locale.ROOT)?.substringBefore('?') ?: return false
    // A /vi/ URL by itself is not proof of a video poster: YouTube Music also
    // serves square Art Track/catalogue covers through ytimg. The parser already
    // rejects non-square renderers and marks them isVideo. Keep only the file
    // names that are unambiguously YouTube frame-grab presets as a defensive
    // fallback, otherwise valid catalogue tracks would be filtered out and the
    // Suggestions carousel could collapse to just a handful of items.
    return url.endsWith("/hqdefault.jpg") ||
        url.endsWith("/mqdefault.jpg") ||
        url.endsWith("/sddefault.jpg") ||
        url.endsWith("/maxresdefault.jpg") ||
        url.endsWith("/0.jpg") ||
        url.endsWith("/1.jpg") ||
        url.endsWith("/2.jpg") ||
        url.endsWith("/3.jpg")
}

/**
 * Normalise a station/watch-next row to the canonical YouTube Music audio
 * recording before it enters the Suggestions carousel. Radio endpoints can
 * occasionally return a video renderer (or a music row carrying a video frame
 * thumbnail); searching with the Songs filter gives us the catalogue audio row
 * and therefore the square release artwork used elsewhere in Orb.
 */
private suspend fun canonicalSuggestionSong(song: Song): Song? {
    if (!song.isVideo && !song.thumbnailUrl.isYouTubeVideoThumbnail()) return song

    val target = TrackMatcher.targetOf(song)
    for (query in TrackMatcher.queries(target).take(3)) {
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS)
            .getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        TrackMatcher.best(candidates, target)?.let { matched ->
            if (!matched.isVideo && !matched.thumbnailUrl.isYouTubeVideoThumbnail()) return matched
        }
    }

    // Suggestions must never surface a frame-grab from a YouTube upload as
    // artwork. If Music cannot resolve a canonical audio row with catalogue
    // artwork, drop this candidate and let another related song take its slot.
    val resolved = YtMusicRepository.resolvePreferredPlaybackVersion(song)
    return resolved.takeIf {
        !it.isVideo && !it.thumbnailUrl.isYouTubeVideoThumbnail()
    }
}

private fun Song.asHomeItem() = ShelfItem(
    title = title,
    subtitle = artist,
    thumbnailUrl = thumbnailUrl,
    videoId = videoId,
    browseId = null,
    isExplicit = isExplicit,
    localUri = localUri,
    localPath = localPath,
    sourceQuality = sourceQuality,
    durationText = durationText,
    artistId = artistId,
    albumId = albumId,
    albumName = albumName,
    isVideo = isVideo,
    setVideoId = setVideoId,
    fromAutoplay = fromAutoplay,
    queuePinned = queuePinned,
    releaseYear = releaseYear,
    sourceExplicitKnown = sourceExplicitKnown,
    sourcePlaylistId = sourcePlaylistId,
    sourcePlaylistTitle = sourcePlaylistTitle,
    sourcePlaylistArtworkUrl = sourcePlaylistArtworkUrl,
)

private fun ShelfItem.asSong(): Song? = videoId?.takeIf { it.isNotBlank() }?.let { id ->
    Song(
        videoId = id,
        title = title,
        artist = subtitle,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        setVideoId = setVideoId,
        fromAutoplay = fromAutoplay,
        localUri = localUri,
        localPath = localPath,
        sourceQuality = sourceQuality,
        queuePinned = queuePinned,
        isExplicit = isExplicit,
        releaseYear = releaseYear,
        sourceExplicitKnown = sourceExplicitKnown,
        sourcePlaylistId = sourcePlaylistId,
        sourcePlaylistTitle = sourcePlaylistTitle,
        sourcePlaylistArtworkUrl = sourcePlaylistArtworkUrl,
    )
}

private fun matchingItems(
    shelves: List<HomeShelf>,
    predicate: (String) -> Boolean,
): List<ShelfItem> = distinctHomeItems(
    shelves
        .filter { predicate(normalizedShelfTitle(it.title)) }
        .flatMap { it.items },
)

private object HomeRecommendationSession {
    private var items: List<ShelfItem> = emptyList()
    private var generation by mutableStateOf(0L)
    private var refreshRequested = false
    var previousVideoIds: Set<String> = emptySet()
        private set
    private var loaded = false
    private var lastCandidateFingerprint: Int? = null

    val sessionToken: Long get() = generation

    private fun sourceSnapshot(): List<ShelfItem> {
        val recent = RecentPlaybackStore.snapshot().map { it.asHomeItem() }
        val jukebox = HomeSuggestionStore.loadJukebox()
        val supermix = HomeSuggestionStore.loadSupermix()
        return mixedSuggestionItems(
            recent = recent,
            jukebox = jukebox,
            supermix = supermix,
            fallback = HomeSuggestionStore.loadPool(),
            fillRemainder = true,
        )
    }

    private fun cleanProgressive(items: List<ShelfItem>): List<ShelfItem> {
        val seenVideoIds = HashSet<String>()
        val seenIdentity = HashSet<String>()
        return items.asSequence()
            .filter { !it.videoId.isNullOrBlank() }
            .filter { !it.isVideo && !it.thumbnailUrl.isYouTubeVideoThumbnail() }
            .filter { item ->
                val videoId = item.videoId.orEmpty()
                val identity =
                    "${normalizedShelfTitle(item.title)}|${normalizedShelfTitle(item.subtitle)}"
                seenVideoIds.add(videoId) && seenIdentity.add(identity)
            }
            .take(HOME_SUGGESTION_LIMIT)
            .toList()
    }

    private fun ensureLoaded() {
        if (loaded) return
        val persisted = HomeSuggestionStore.loadCurrent()
        val localBlend = sourceSnapshot()
        items = when {
            persisted.size >= HOME_SUGGESTION_LIMIT -> persisted
            localBlend.isNotEmpty() -> localBlend
            else -> persisted
        }
        if (items.size >= HOME_SUGGESTION_LIMIT) {
            HomeSuggestionStore.replaceCurrent(items)
        }
        loaded = true
    }

    /**
     * First install is intentionally progressive. If only Recent listening is
     * available, the balanced mixer exposes at most seven recent songs. As the
     * Jukebox and Supermix sources arrive, the visible list is recomposed and
     * grows immediately instead of waiting for all 20 cards. Once a complete
     * 20-card set exists it becomes the durable current buffer.
     */
    fun visible(candidates: List<ShelfItem>): List<ShelfItem> {
        ensureLoaded()
        if (candidates.isNotEmpty()) {
            val clean = cleanProgressive(candidates)
            val fingerprint = clean.fold(1) { acc, item ->
                31 * acc + (item.videoId?.hashCode() ?: 0)
            }
            if (lastCandidateFingerprint != fingerprint) {
                lastCandidateFingerprint = fingerprint
                HomeSuggestionStore.mergePool(clean)

                when {
                    refreshRequested && clean.isNotEmpty() -> {
                        items = clean
                        HomeSuggestionStore.replaceCurrent(clean)
                        // Keep accepting enriched results from this refresh;
                        // a cached first result must not freeze out fresh Supermix.
                    }
                    items.size < HOME_SUGGESTION_LIMIT -> {
                        // Do not freeze a first-run partial set (for example ten
                        // recent songs). Replace it with every better mixed
                        // snapshot as each source arrives.
                        items = clean
                        if (clean.size >= HOME_SUGGESTION_LIMIT) {
                            HomeSuggestionStore.replaceCurrent(clean)
                        }
                    }
                    clean.size >= HOME_SUGGESTION_LIMIT -> {
                        // Normal in-session source updates prepare only the next
                        // app entry; a full current session remains stable.
                        HomeSuggestionStore.stageNext(clean)
                    }
                }
            }
        }
        return items.take(HOME_SUGGESTION_LIMIT)
    }

    /** A real root-Activity entry promotes/rebuilds the next 20 locally. */
    fun beginAppEntry() {
        ensureLoaded()
        previousVideoIds = items.mapNotNull { it.videoId }.toSet()
        generation += 1L
        refreshRequested = false
        lastCandidateFingerprint = null

        val promoted = HomeSuggestionStore.promoteNext()
        val localBlend = sourceSnapshot()
        items = when {
            localBlend.size >= HOME_SUGGESTION_LIMIT -> localBlend
            promoted.size >= HOME_SUGGESTION_LIMIT -> promoted
            localBlend.isNotEmpty() -> localBlend
            else -> promoted
        }
        if (items.size >= HOME_SUGGESTION_LIMIT) {
            HomeSuggestionStore.replaceCurrent(items)
        }
        loaded = true
    }

    /** Explicit pull-to-refresh starts the only visible in-process refresh. */
    fun requestRefresh() {
        ensureLoaded()
        previousVideoIds = items.mapNotNull { it.videoId }.toSet()
        generation += 1L
        refreshRequested = true
        lastCandidateFingerprint = null
        // Keep the cards visible during the fetch; accept any nonempty result.
        // Twenty is a maximum, never a condition for publishing a refresh.
    }
}

private fun buildForYouState(
    home: UiState<List<HomeShelf>>,
    library: UiState<LibraryPage>,
    topArtists: List<StatsLeader>,
    youtubeSections: List<HomeShelf>,
    relatedSuggestions: List<ShelfItem>,
): UiState<List<HomeShelf>> {
    // Suggestions is deliberately independent from Home's network state. A
    // persisted 20-card snapshot paints immediately at process start; the rest
    // of the page can arrive progressively underneath it.
    val recommendations = HomeRecommendationSession.visible(relatedSuggestions)
    // Every curated bucket is independent. Do not hold Artists/Library/Recaps
    // behind the main Home request just because Listen Again/Recent has not
    // returned yet; whichever source finishes first should paint first.
    val shelves = (home as? UiState.Success)?.data.orEmpty()
    val libraryPage = (library as? UiState.Success)?.data

    fun addShelf(
        destination: MutableList<HomeShelf>,
        title: String,
        items: List<ShelfItem>,
        limit: Int = 30,
    ) {
        if (items.isNotEmpty()) destination += HomeShelf(title, items.take(limit))
    }

    val recentItems = matchingItems(shelves) { it.contains("recently played") }

    val catalogueArtists = shelves
        .flatMap { it.items }
        .filter { it.type == BrowseType.ARTIST }
    val topArtistItems = topArtists.take(10).map { leader ->
        val leaderKey = normalizedShelfTitle(leader.title)
        val matched = catalogueArtists.firstOrNull {
            normalizedShelfTitle(it.title) == leaderKey
        }
        ShelfItem(
            title = leader.title,
            subtitle = "",
            thumbnailUrl = leader.artworkUrl ?: matched?.thumbnailUrl,
            videoId = null,
            browseId = matched?.browseId,
            type = BrowseType.ARTIST,
        )
    }

    val librarySongs = libraryPage?.let { page ->
        distinctHomeItems((page.likedSongs + page.librarySongs).map { it.asHomeItem() })
    }.orEmpty()
    val homeLibrarySongs = matchingItems(shelves) {
        it.contains("from your library") || it.contains("your library")
    }.filter { !it.videoId.isNullOrBlank() }
    val youtubeLibrarySongs = matchingItems(youtubeSections) {
        it.contains("da sua biblioteca") ||
            it.contains("from your library") ||
            it.contains("your library") ||
            it.contains("de tu biblioteca")
    }.filter { !it.videoId.isNullOrBlank() }
    val youtubeRecaps = matchingItems(youtubeSections) {
        it.contains("recap") || it.contains("retrospectiva") || it.contains("resumen")
    }

    val curated = buildList {
        // Keep Suggestions as the first, dedicated section even while its
        // one-shot discovery request is resolving. HomeScreen renders its own
        // carousel loading state instead of letting Recently played take over.
        add(HomeShelf(HOME_RECOMMENDATIONS_SHELF_TITLE, recommendations))
        addShelf(
            this,
            "Ouça novamente",
            matchingItems(shelves) { it.contains("listen again") },
        )
        addShelf(this, HOME_TOP_ARTISTS_SHELF_TITLE, topArtistItems, limit = 10)
        addShelf(
            this,
            "Da sua biblioteca",
            distinctHomeItems(youtubeLibrarySongs + librarySongs + homeLibrarySongs),
        )
        addShelf(this, "Tocadas recentemente", recentItems)
        addShelf(
            this,
            "Recaps",
            distinctHomeItems(
                youtubeRecaps + matchingItems(shelves) {
                    it.contains("recap") || it.contains("retrospectiva") || it.contains("resumen")
                },
            ),
        )
    }

    // For You is deliberately curated. Raw YouTube shelves must never leak
    // below Recaps when one of the requested buckets happens to be empty. A
    // failed Home request is only fatal when none of the independent curated
    // sources has anything useful to render.
    return if (home is UiState.Error && curated.all { it.items.isEmpty() }) {
        home
    } else {
        UiState.Success(curated)
    }
}

private fun buildAlbumsState(
    hub: UiState<List<HomeShelf>>,
    topAlbums: List<StatsLeader>,
    favoritesLoading: Boolean,
): UiState<List<HomeShelf>> {
    // Albums is intentionally atomic on first open: do not publish the locally
    // available favorites shelf several seconds before the network-backed New
    // albums / You may like shelves. Waiting for the hub keeps the page from
    // visibly reflowing underneath the listener.
    if (hub is UiState.Loading || favoritesLoading) return UiState.Loading
    if (hub is UiState.Error && topAlbums.isEmpty()) return hub

    val shelves = (hub as? UiState.Success)?.data.orEmpty()
    val catalogueAlbums = shelves.flatMap { it.items }.filter { it.type == BrowseType.ALBUM }
    val favoriteAlbumItems = topAlbums.take(10).map { leader ->
        val titleKey = normalizedShelfTitle(leader.title)
        val artistKey = leader.subtitle?.let(::normalizedShelfTitle).orEmpty()
        val matched = catalogueAlbums.firstOrNull { candidate ->
            normalizedShelfTitle(candidate.title) == titleKey &&
                (artistKey.isBlank() || normalizedShelfTitle(candidate.subtitle).contains(artistKey))
        }
        ShelfItem(
            title = leader.title,
            subtitle = leader.subtitle.orEmpty(),
            thumbnailUrl = leader.artworkUrl ?: matched?.thumbnailUrl,
            videoId = null,
            browseId = matched?.browseId,
            isExplicit = matched?.isExplicit == true,
            type = BrowseType.ALBUM,
        )
    }

    val favoriteReleaseShelf = shelves.firstOrNull {
        it.title == HOME_FAVORITE_ALBUM_RELEASES_SHELF_TITLE
    }
    val newAlbumsShelf = shelves.firstOrNull {
        normalizedShelfTitle(it.title) in setOf("new albums", "novos álbuns", "nuevos álbumes")
    }
    val suggestionsShelf = shelves.firstOrNull {
        normalizedShelfTitle(it.title) in setOf(
            "albums you may like",
            "álbuns que você pode gostar",
            "álbumes que te pueden gustar",
        )
    }

    return UiState.Success(
        buildList {
            favoriteReleaseShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)
            newAlbumsShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)
            if (favoriteAlbumItems.isNotEmpty()) {
                add(HomeShelf(title = "Your favorite albums", items = favoriteAlbumItems))
            }
            suggestionsShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)
        },
    )
}

private fun UserPlaylist.asHomePlaylistItem(): ShelfItem = ShelfItem(
    title = title,
    subtitle = subtitle,
    thumbnailUrl = thumbnailUrl,
    videoId = null,
    browseId = browseId,
    type = BrowseType.PLAYLIST,
)

private fun recentPlaylistHomeItems(): List<ShelfItem> {
    val seen = HashSet<String>()
    return RecentPlaybackStore.snapshotEntries().mapNotNull { entry ->
        val song = entry.song
        val rawId = song.sourcePlaylistId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = song.sourcePlaylistTitle?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val browseId = if (rawId.startsWith("VL")) rawId else "VL$rawId"
        if (!seen.add(browseId)) return@mapNotNull null
        ShelfItem(
            title = title,
            subtitle = "",
            thumbnailUrl = song.sourcePlaylistArtworkUrl ?: song.thumbnailUrl,
            videoId = null,
            browseId = browseId,
            type = BrowseType.PLAYLIST,
        )
    }.take(12)
}

private fun buildPlaylistsState(
    community: UiState<List<HomeShelf>>,
    ownPlaylists: List<UserPlaylist>,
    ownPlaylistsLoading: Boolean,
    ownPlaylistsLoaded: Boolean,
    signedIn: Boolean,
    yoursTitle: String,
    recentTitle: String,
    communityTitle: String,
): UiState<List<HomeShelf>> {
    // Just like Albums, publish this page as one coherent snapshot instead of
    // letting Community flash first while the account playlists are still on
    // the wire. Recent playlist attribution is device-local and instant.
    if (community is UiState.Loading || (signedIn && (ownPlaylistsLoading || !ownPlaylistsLoaded))) {
        return UiState.Loading
    }

    val own = ownPlaylists.map { it.asHomePlaylistItem() }
    val recent = recentPlaylistHomeItems()
    val communityItems = (community as? UiState.Success)
        ?.data
        .orEmpty()
        .flatMap { it.items }
        .distinctBy { it.browseId ?: it.title }

    val shelves = buildList {
        if (own.isNotEmpty()) add(HomeShelf(yoursTitle, own))
        if (recent.isNotEmpty()) add(HomeShelf(recentTitle, recent))
        if (communityItems.isNotEmpty()) add(HomeShelf(communityTitle, communityItems))
    }

    return if (shelves.isEmpty() && community is UiState.Error) community else UiState.Success(shelves)
}

@Composable
private fun PlayerActionSheetSystemBars(
    hideStatusBar: Boolean,
) {
    val view = LocalView.current

    // SongActions is another ModalBottomSheet, therefore another dialog window.
    // If that top-most window is allowed to restore status bars while Now Playing
    // has them hidden, Android briefly changes the usable height of the player
    // underneath and the identity row visibly drops/rises. Mirror the player's
    // status-bar state on this overlay window and leave restoration to the
    // underlying Now Playing window when the sheet is dismissed.
    DisposableEffect(view, hideStatusBar) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (hideStatusBar) {
            controller?.hide(WindowInsetsCompat.Type.statusBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            // Intentionally do not call show(statusBars) here. When this overlay
            // closes, Now Playing is still the owner of the hidden-bar state.
        }
    }
}

@Composable
private fun DetailContentTheme(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        BitChordTheme(darkTheme = true) { content() }
    } else {
        content()
    }
}

class MainActivity : ComponentActivity() {
    private var pendingYoutubeConnection: ((Result<Unit>) -> Unit)? = null
    private var openNowPlayingRequest by mutableStateOf(false)

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_NOW_PLAYING) {
            openNowPlayingRequest = true
        }
    }

    private val youtubeAuthorizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val callback = pendingYoutubeConnection ?: return@registerForActivityResult
            pendingYoutubeConnection = null
            lifecycleScope.launch {
                val resultData = result.data
                if (result.resultCode != Activity.RESULT_OK || resultData == null) {
                    callback(
                        Result.failure(
                            IllegalStateException("YouTube Music authorization was cancelled."),
                        ),
                    )
                    return@launch
                }
                val connection = runCatching {
                    val authorization = OrbGoogleAuth.finishYoutubeAuthorization(
                        this@MainActivity,
                        resultData,
                    )
                    OrbGoogleAuth.acceptYoutubeAuthorization(authorization)
                    checkNotNull(OrbGoogleAuth.refreshYoutubeAccount()) {
                        "YouTube Music account could not be resolved."
                    }
                }
                if (connection.isFailure) {
                    // Native OAuth is preferred. If Music's private account
                    // surface refuses it, the compatibility browser remains
                    // available as the explicit fallback.
                    OrbGoogleAuth.requestYoutubeCompatibility()
                }
                callback(connection.map { Unit })
            }
        }

    /**
     * Orb identity is established first and independently through Google +
     * Supabase. YouTube Music is deliberately not requested here anymore:
     * first-time users receive a clear recommendation immediately after login
     * and can choose whether to connect it.
     */
    fun signInWithOrbGoogle(onResult: (Result<Unit>) -> Unit) {
        lifecycleScope.launch {
            val identity = runCatching {
                OrbGoogleAuth.signInIdentity(this@MainActivity)
            }
            if (identity.isFailure) {
                OrbGoogleAuth.signOut(clearLegacyYouTubeSession = false)
                onResult(Result.failure(requireNotNull(identity.exceptionOrNull())))
            } else {
                onResult(Result.success(Unit))
            }
        }
    }

    /**
     * Explicit YouTube Music connection used by first-account onboarding.
     * Native Google authorization is attempted first; if Music's private
     * account surface rejects it, Orb exposes the existing browser
     * compatibility path without affecting the already-valid Orb login.
     */
    fun connectYoutubeMusic(onResult: (Result<Unit>) -> Unit) {
        if (pendingYoutubeConnection != null) return
        lifecycleScope.launch {
            val authorization = runCatching {
                OrbGoogleAuth.beginYoutubeAuthorization(this@MainActivity)
            }.getOrElse { error ->
                OrbGoogleAuth.requestYoutubeCompatibility()
                onResult(Result.failure(error))
                return@launch
            }

            if (authorization.hasResolution()) {
                val pendingIntent = authorization.pendingIntent
                if (pendingIntent == null) {
                    onResult(
                        Result.failure(
                            IllegalStateException("YouTube Music authorization is unavailable."),
                        ),
                    )
                } else {
                    pendingYoutubeConnection = onResult
                    youtubeAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                    )
                }
            } else {
                val connection = runCatching {
                    OrbGoogleAuth.acceptYoutubeAuthorization(authorization)
                    checkNotNull(OrbGoogleAuth.refreshYoutubeAccount()) {
                        "YouTube Music account could not be resolved."
                    }
                }
                if (connection.isFailure) {
                    OrbGoogleAuth.requestYoutubeCompatibility()
                }
                onResult(connection.map { Unit })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // Pressing Back/closing the root Activity and opening Orb again is a
            // new Suggestions session. Configuration recreation is not.
            HomeRecommendationSession.beginAppEntry()
        }
        handleLaunchIntent(intent)
        enableEdgeToEdge()

        // Phones launch in portrait regardless of the rotation preference. Rotation
        // is scoped to the Now Playing window only; tablets remain freely rotatable.
        val phoneLayoutAtLaunch = resources.configuration.smallestScreenWidthDp < 600
        requestedOrientation = if (phoneLayoutAtLaunch) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            BitChordTheme(darkTheme = darkTheme) {
                BitChordApp(
                    darkTheme = darkTheme,
                    openNowPlayingRequest = openNowPlayingRequest,
                    onOpenNowPlayingRequestConsumed = { openNowPlayingRequest = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "com.music.orb.action.OPEN_NOW_PLAYING"
    }
}

private data class NowPlayingLaunchVisual(
    val token: Int,
    val song: Song,
    val origin: Rect,
    val originKind: NowPlayingLaunchOriginKind,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitChordApp(
    darkTheme: Boolean,
    openNowPlayingRequest: Boolean,
    onOpenNowPlayingRequestConsumed: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var tabBackStack by remember { mutableStateOf<List<Int>>(emptyList()) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var homeSection by rememberSaveable { mutableIntStateOf(0) }
    var homeHeaderCollapsedDuringSectionTransition by remember { mutableStateOf<Boolean?>(null) }
    var homeSectionTransitionGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(homeSection) {
        if (homeSection > 3) homeSection = 0
    }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    val allowScreenRotation by AppSettings.allowScreenRotation.collectAsStateWithLifecycle()
    val hideStatusBarNowPlaying by AppSettings.hideStatusBarNowPlaying.collectAsStateWithLifecycle()
    val rootConfiguration = LocalConfiguration.current
    val rootIsPhoneLayout = rootConfiguration.smallestScreenWidthDp < 600

    // On phones, rotation belongs exclusively to Now Playing. The rest of Orb
    // remains portrait-only even when the preference is enabled. Because the
    // Activity handles configuration changes in place, opening/closing the sheet
    // or rotating the device never recreates the Now Playing state.
    LaunchedEffect(showNowPlaying, allowScreenRotation, rootIsPhoneLayout) {
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = when {
            !rootIsPhoneLayout -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showNowPlaying && allowScreenRotation -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    var appRootSize by remember { mutableStateOf(IntSize.Zero) }
    var lastPointerDown by remember { mutableStateOf<Offset?>(null) }
    var miniPlayerBounds by remember { mutableStateOf<Rect?>(null) }
    var nowPlayingLaunchToken by remember { mutableIntStateOf(0) }
    var nowPlayingLaunchVisual by remember { mutableStateOf<NowPlayingLaunchVisual?>(null) }
    var nowPlayingLaunchContentVisible by remember { mutableStateOf(true) }
    val appDensity = LocalDensity.current
    var showLogin by remember { mutableStateOf(false) }
    var authInProgress by remember { mutableStateOf(false) }
    var firstAccountOnboardingRequested by rememberSaveable { mutableStateOf(false) }
    var firstAccountOnboardingStep by rememberSaveable { mutableStateOf<String?>(null) }
    var firstAccountYoutubeConnecting by remember { mutableStateOf(false) }
    var firstAccountUsername by rememberSaveable { mutableStateOf("") }
    var firstAccountUsernameSaving by remember { mutableStateOf(false) }
    var firstAccountUsernameError by remember { mutableStateOf<String?>(null) }
    val firstAccountOnboardingScope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showAccountIntegrations by remember { mutableStateOf(false) }
    var showOrbProfile by rememberSaveable { mutableStateOf(false) }
    var publicProfileUserId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showListenBrainzLogin by remember { mutableStateOf(false) }
    var showLastfmLogin by remember { mutableStateOf(false) }
    var youtubeIdentityPicker by remember { mutableStateOf<List<YouTubeAccountIdentity>?>(null) }
    var youtubeIdentitySwitching by remember { mutableStateOf(false) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var linksLoading by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var playlistActions by remember { mutableStateOf<UserPlaylist?>(null) }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    val betaUpdatesEnabled by AppSettings.betaUpdatesEnabled.collectAsStateWithLifecycle()
    val stableAutoUpdatesEnabled by AppSettings.stableAutoUpdatesEnabled.collectAsStateWithLifecycle()
    val automaticUpdateAvailableRaw by UpdateAvailableStore.available.collectAsStateWithLifecycle()
    val automaticUpdateAvailable = automaticUpdateAvailableRaw
        ?.takeIf { BetaUpdateChecker.looksNewerThanInstalled(it) }
    val automaticUpdateReadyRaw by UpdateReadyStore.ready.collectAsStateWithLifecycle()
    val automaticUpdateReady = automaticUpdateReadyRaw
        ?.takeIf { BetaUpdateChecker.looksNewerThanInstalled(it.tagName) }
    var showAutomaticUpdateDialog by remember { mutableStateOf(false) }
    var searchFocusTrigger by remember { mutableIntStateOf(0) }
    var exploreSearchActive by rememberSaveable { mutableStateOf(false) }
    var librarySearchActive by rememberSaveable { mutableStateOf(false) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(selectedTab) {
        if (selectedTab != TAB_LIBRARY) {
            librarySearchActive = false
            libraryQuery = ""
        }
    }

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val homeYouTubeSections by viewModel.homeYouTubeSections.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    // Suggestions uses three explicit taste sources: qualified local recents,
    // YouTube Music's Digital Jukebox/Speed Dial, and My Supermix. The visible
    // 20-card buffer is still session-stable; no radio/watch-next is created
    // here. A suggestion's radio is requested only after the user taps it.
    val recommendationSessionToken = HomeRecommendationSession.sessionToken
    var relatedHomeSuggestions by remember(recommendationSessionToken) {
        mutableStateOf<List<ShelfItem>>(emptyList())
    }
    var supermixLoadedForSession by remember(recommendationSessionToken) {
        mutableStateOf(false)
    }
    var suggestionContinuationAttempts by remember(recommendationSessionToken) {
        mutableIntStateOf(0)
    }
    val homeReadyForSuggestions = homeState is UiState.Success
    val suggestionCatalogueKey = (homeState as? UiState.Success)?.data.orEmpty()
        .joinToString("||") { shelf ->
            val ids = shelf.items.asSequence()
                .map { it.videoId ?: it.browseId.orEmpty() }
                .take(16)
                .joinToString(",")
            "${shelf.title}:$ids"
        }
    val youtubeSuggestionKey = homeYouTubeSections.joinToString("||") { shelf ->
        val ids = shelf.items.asSequence()
            .map { it.videoId ?: it.browseId.orEmpty() }
            .take(16)
            .joinToString(",")
        "${shelf.title}:$ids"
    }
    val homeRefreshingForSuggestions = MainViewModel.Feed.HOME in refreshing

    LaunchedEffect(
        recommendationSessionToken,
        homeReadyForSuggestions,
        suggestionCatalogueKey,
        youtubeSuggestionKey,
        homeRefreshingForSuggestions,
    ) {
        if (!homeReadyForSuggestions || homeRefreshingForSuggestions) return@LaunchedEffect

        val sourceShelves = (homeState as? UiState.Success)?.data.orEmpty()
        val youtubeShelves = homeYouTubeSections
        val discoveryShelves = sourceShelves + youtubeShelves

        val recent = distinctHomeItems(
            matchingItems(sourceShelves) {
                it.contains("recently played") ||
                    it.contains("tocadas recentemente") ||
                    it.contains("reproducidas recientemente")
            }.ifEmpty {
                RecentPlaybackStore.snapshot().map { it.asHomeItem() }
            },
        ).filter { !it.videoId.isNullOrBlank() }

        val freshJukebox = distinctHomeItems(
            discoveryShelves
                .filter { isJukeboxShelfTitle(it.title) || isJukeboxShelfTitle(it.subtitle) }
                .flatMap { it.items }
                .filter { !it.videoId.isNullOrBlank() },
        )
        if (freshJukebox.isNotEmpty()) HomeSuggestionStore.saveJukebox(freshJukebox)
        val jukebox = freshJukebox.ifEmpty { HomeSuggestionStore.loadJukebox() }

        val directSupermix = distinctHomeItems(
            discoveryShelves
                .filter { isSupermixTitle(it.title) || isSupermixTitle(it.subtitle) }
                .flatMap { it.items }
                .filter { !it.videoId.isNullOrBlank() },
        )
        if (directSupermix.isNotEmpty()) HomeSuggestionStore.saveSupermix(directSupermix)

        val supermixCard = discoveryShelves.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { item ->
                !item.browseId.isNullOrBlank() &&
                    (isSupermixTitle(item.title) || isSupermixTitle(item.subtitle))
            }
        val supermixBrowseId = supermixCard?.browseId
            ?: HomeSuggestionStore.loadSupermixBrowseId()
        supermixCard?.browseId?.let { HomeSuggestionStore.saveSupermixBrowseId(it) }

        var supermix = directSupermix.ifEmpty { HomeSuggestionStore.loadSupermix() }

        fun publishMixedSuggestions() {
            relatedHomeSuggestions = mixedSuggestionItems(
                recent = recent,
                jukebox = jukebox,
                supermix = supermix,
                fallback = HomeSuggestionStore.loadPool(),
                fillRemainder = true,
            )
        }

        // Publish the available balanced mix immediately. Missing sources do
        // not prevent replacement; cached candidates may fill remaining slots.
        publishMixedSuggestions()

        // Jukebox Digital and/or My Supermix can live on the first Home
        // continuation rather than FEmusic_home's initial page. Ask for that
        // page opportunistically; it does not block the cards already shown.
        val needsMorePersonalisedSources = jukebox.isEmpty() ||
            (supermix.isEmpty() && supermixBrowseId.isNullOrBlank())
        if (needsMorePersonalisedSources && suggestionContinuationAttempts < 2) {
            suggestionContinuationAttempts += 1
            viewModel.loadMoreHome()
        }

        if (!supermixLoadedForSession && !supermixBrowseId.isNullOrBlank()) {
            val fetchResult = YtMusicRepository.browseSongs(supermixBrowseId)
            // If this effect is cancelled during browse, leave the flag false.
            supermixLoadedForSession = fetchResult.isSuccess
            val fetched = fetchResult.getOrNull()
                ?.songs
                .orEmpty()
                .asSequence()
                .filter { !it.isVideo && !viewModel.shouldAvoidPlayback(it) }
                .map { it.asHomeItem() }
                .toList()
            if (fetched.isNotEmpty()) {
                HomeSuggestionStore.saveSupermix(fetched, supermixBrowseId)
                supermix = fetched
                // Do not wait for the Jukebox fetch (or vice versa). Recompose
                // the carousel now; additional source cards will be inserted on
                // the next effect pass as they arrive.
                publishMixedSuggestions()
            }
        }
    }
    val releasesHubState by viewModel.releasesHub.collectAsStateWithLifecycle()
    val trendingHubState by viewModel.trendingHub.collectAsStateWithLifecycle()
    val discoverHubState by viewModel.discoverHub.collectAsStateWithLifecycle()
    val communityPlaylistsHubState by viewModel.communityPlaylistsHub.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.playlists.collectAsStateWithLifecycle()
    val userPlaylistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()
    val userPlaylistsLoaded by viewModel.playlistsLoaded.collectAsStateWithLifecycle()
    val activeUpdateDownload by UpdateDownloadStore.active.collectAsStateWithLifecycle()

    var updateDialogShown by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()

    // The legacy stable-update card/dialog is kept for ordinary stable users.
    // Once the user chooses an automatic channel, that channel becomes the sole
    // source of update prompts. A Beta build also never advertises a stable
    // release until the user explicitly leaves Beta in Settings.
    val updateNotice = updateAvailable.takeUnless {
        betaUpdatesEnabled || stableAutoUpdatesEnabled || BuildConfig.IS_BETA
    }

    LaunchedEffect(betaUpdatesEnabled, stableAutoUpdatesEnabled, BuildConfig.IS_BETA) {
        if (betaUpdatesEnabled || stableAutoUpdatesEnabled || BuildConfig.IS_BETA) {
            showUpdateDialog = false
        }
    }

    LaunchedEffect(updateNotice) {
        if (updateNotice != null && !updateDialogShown) {
            updateDialogShown = true
            showUpdateDialog = true
        }
    }

    // Background work only discovers releases; it never downloads an APK. The
    // metadata is durable so the full update card can be shown immediately on
    // launch. A ready state can still exist after an explicit manual download.
    LaunchedEffect(automaticUpdateAvailable?.tagName, automaticUpdateAvailable?.channel) {
        if (automaticUpdateAvailable != null && automaticUpdateReady == null) {
            showUpdateDialog = false
            showAutomaticUpdateDialog = true
        }
    }
    LaunchedEffect(automaticUpdateReady?.tagName, automaticUpdateReady?.channel) {
        if (automaticUpdateReady != null) {
            showUpdateDialog = false
            showAutomaticUpdateDialog = true
        }
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searchRefreshing by viewModel.searchRefreshing.collectAsStateWithLifecycle()
    val exploreState by viewModel.explore.collectAsStateWithLifecycle()
    val libraryState by viewModel.library.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val youtubeCompatibilityRequired by
        OrbGoogleAuth.youtubeCompatibilityRequired.collectAsStateWithLifecycle()
    val youtubeAccount by OrbGoogleAuth.youtubeAccount.collectAsStateWithLifecycle()

    fun finishFirstAccountOnboarding() {
        firstAccountOnboardingStep = null
        firstAccountOnboardingRequested = false
        firstAccountYoutubeConnecting = false
        firstAccountUsernameSaving = false
        firstAccountUsernameError = null
    }

    fun advanceFirstAccountOnboardingToUsername() {
        val userId = SocialRepository.currentUserId()
        if (userId == null) {
            finishFirstAccountOnboarding()
            return
        }
        firstAccountOnboardingScope.launch {
            val profile = runCatching { SocialRepository.myProfile() }.getOrNull()
            val existingUsername = profile?.username?.trim().orEmpty()

            // Never recommend username creation if the Orb account already has
            // one, regardless of local onboarding flags.
            if (existingUsername.isNotBlank()) {
                FirstAccountOnboardingStore.markUsernameHandled(context, userId)
                finishFirstAccountOnboarding()
                return@launch
            }

            if (!FirstAccountOnboardingStore.usernameHandled(context, userId)) {
                firstAccountUsername = ""
                firstAccountUsernameError = null
                firstAccountOnboardingStep = "username"
            } else {
                finishFirstAccountOnboarding()
            }
        }
    }

    // Resume only onboarding that was explicitly started by a Google sign-in.
    // This prevents an app update from presenting old users as first-time users.
    LaunchedEffect(
        signedIn,
        firstAccountOnboardingRequested,
        youtubeAccount?.handle,
        youtubeAccount?.email,
    ) {
        if (!signedIn) return@LaunchedEffect

        val userId = SocialRepository.currentUserId() ?: return@LaunchedEffect
        val started =
            firstAccountOnboardingRequested ||
                FirstAccountOnboardingStore.hasStarted(context, userId)
        if (!started) return@LaunchedEffect

        FirstAccountOnboardingStore.markStarted(context, userId)

        // Resolve both prerequisites before deciding what to show. A connected
        // YouTube Music account or an already-created Orb username must never
        // produce a redundant recommendation.
        val youtubeConnected =
            youtubeAccount != null ||
                OrbGoogleAuth.storedYoutubeAccount() != null

        val profile = runCatching { SocialRepository.myProfile() }.getOrNull()
        val hasUsername = !profile?.username.isNullOrBlank()

        if (youtubeConnected) {
            FirstAccountOnboardingStore.markYoutubeHandled(context, userId)
        }
        if (hasUsername) {
            FirstAccountOnboardingStore.markUsernameHandled(context, userId)
        }

        // If both requirements are already satisfied, onboarding is complete
        // without presenting any dialog.
        if (youtubeConnected && hasUsername) {
            finishFirstAccountOnboarding()
            return@LaunchedEffect
        }

        // Step 1 is shown only when YouTube Music is genuinely not connected
        // and this recommendation has not already been handled.
        if (
            !youtubeConnected &&
            !FirstAccountOnboardingStore.youtubeHandled(context, userId)
        ) {
            firstAccountOnboardingStep = "youtube"
            return@LaunchedEffect
        }

        // Step 2 is shown only when the profile genuinely has no username and
        // the recommendation has not already been handled.
        if (
            !hasUsername &&
            !FirstAccountOnboardingStore.usernameHandled(context, userId)
        ) {
            firstAccountUsername = ""
            firstAccountUsernameError = null
            firstAccountOnboardingStep = "username"
            return@LaunchedEffect
        }

        finishFirstAccountOnboarding()
    }

    fun requestGoogleSignIn() {
        if (authInProgress) return
        val activity = context as? MainActivity
        if (activity == null) {
            Toast.makeText(context, "Google sign-in is unavailable in this context.", Toast.LENGTH_LONG).show()
            return
        }
        authInProgress = true
        activity.signInWithOrbGoogle { result ->
            authInProgress = false
            result.onSuccess {
                SocialRepository.currentUserId()?.let { userId ->
                    FirstAccountOnboardingStore.markStarted(context, userId)
                }
                firstAccountOnboardingRequested = true
                firstAccountOnboardingStep = null
                firstAccountUsername = ""
                firstAccountUsernameError = null
                viewModel.onGoogleSignedIn()
                selectedTab = TAB_HOME
                tabBackStack = emptyList()
                showLogin = false
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Google sign-in could not be completed.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Orb is an account-first experience. Credential Manager is now the only
    // normal account entry point: one Google identity feeds Supabase Auth and
    // the YouTube authorization request.
    if (!signedIn) {
        SystemBarIcons(dark = false)
        LaunchedEffect(Unit) {
            selectedTab = TAB_HOME
            tabBackStack = emptyList()
            showNowPlaying = false
            showSettings = false
            showSources = false
            showAccountIntegrations = false
            showOrbProfile = false
            publicProfileUserId = null
            showLogin = false
            while (viewModel.closeDetail()) {
                // Drop any page that belonged to the signed-in session.
            }
        }
        WelcomeLoginScreen(
            onSignIn = { requestGoogleSignIn() },
        )
        return
    }

    // A private YouTube Music endpoint may reject the otherwise valid Google
    // OAuth bearer. That no longer interrupts onboarding: Orb stays signed in
    // and the browser compatibility session is offered only when the listener
    // explicitly opens the YouTube Music integration/account controls.

    val friendsViewModel: FriendsViewModel = viewModel()
    val friendsRefreshing by friendsViewModel.refreshing.collectAsStateWithLifecycle()
    val friendsSearchOpen by friendsViewModel.userSearchOpen.collectAsStateWithLifecycle()
    val statsNotificationsOpen by friendsViewModel.notificationsOpen.collectAsStateWithLifecycle()
    val statsNotificationUnread by friendsViewModel.notificationUnreadCount.collectAsStateWithLifecycle()
    val artistRankingOpen by friendsViewModel.artistRankingOpen.collectAsStateWithLifecycle()
    val statsPeriod by friendsViewModel.statsPeriod.collectAsStateWithLifecycle()
    val homeTopArtists by friendsViewModel.homeTopArtists.collectAsStateWithLifecycle()
    val homeTopAlbums by friendsViewModel.homeTopAlbums.collectAsStateWithLifecycle()
    val homeStatsReady by friendsViewModel.homeStatsReady.collectAsStateWithLifecycle()

    val useYouTubeLibrary by AppSettings.useYouTubeMusicLibrary.collectAsStateWithLifecycle()
    val songMenu by viewModel.songMenu.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val orbProfile by SocialRepository.myProfileState.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn, orbProfile?.id) {
        if (signedIn) friendsViewModel.ensureHomeStatsLoaded()
    }
    val displayAccount = remember(account, orbProfile) {
        val currentAccount = account
        val activeProfile = orbProfile?.takeIf { it.id == SocialRepository.currentUserId() }
        currentAccount?.copy(
            name = activeProfile?.displayName?.takeIf { it.isNotBlank() } ?: currentAccount.name,
            thumbnailUrl = activeProfile?.avatarIconUrl?.takeIf { it.isNotBlank() }
                ?: activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: currentAccount.thumbnailUrl,
        )
    }
    val googleIdentity = OrbGoogleAuth.storedIdentity()
    LaunchedEffect(signedIn, googleIdentity?.uniqueId) {
        if (signedIn && googleIdentity != null) {
            runCatching {
                val existing = SocialRepository.myProfile()
                SocialRepository.updateProfileMetadata(
                    displayName = googleIdentity.displayName.takeIf { existing?.displayName.isNullOrBlank() },
                    avatarUrl = googleIdentity.avatarUrl.takeIf { existing?.avatarUrl.isNullOrBlank() },
                )
            }
        }
    }
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val refreshingDetails by viewModel.refreshingDetails.collectAsStateWithLifecycle()
    val detail = detailStack.lastOrNull()
    val isLocalDetail = detail?.browseId?.startsWith("local:") == true
    val isDetailVisible =
        detail != null && !isLocalDetail && !showSettings && !showSources && !showAccountIntegrations
    val forceDarkDetail = isDetailVisible
    val profilePageOpen = showOrbProfile || publicProfileUserId != null
    var profileTopBarInfo by remember {
        mutableStateOf(
            ProfileTopBarInfo(
                title = "",
                subtitle = null,
                pageColor = Color.Transparent,
                contentColor = Color.White,
                contentVariantColor = Color.White.copy(alpha = 0.78f),
                collapseOffsetPx = Float.POSITIVE_INFINITY,
            ),
        )
    }

    // Album / playlist / artist detail content now uses the same approved
    // dark treatment in both Stable and Beta. Surrounding chrome and system-bar
    // icons still follow the user's actual app theme.
    SystemBarIcons(dark = !darkTheme && !showNowPlaying)

    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    val artistPreferences by viewModel.artistPreferences.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()

    LaunchedEffect(detail) {
        if (detail != null) {
            showSettings = false
            showSources = false
        }
    }
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    LaunchedEffect(savedDownloads, detail?.browseId) {
        if (detail?.browseId == "local:downloads") {
            viewModel.reloadLocalDetail("local:downloads")
        }
    }

    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)

    // A dislike or blocked artist is a transport rule, not just a recommendation
    // hint. Keep search results visible, but remove excluded tracks from the
    // future queue immediately so AutoPlay/repeat cannot bring them back.
    LaunchedEffect(controller, likeStatuses, artistPreferences) {
        val live = controller ?: return@LaunchedEffect
        val currentIndex = live.currentMediaItemIndex
        for (i in live.mediaItemCount - 1 downTo 0) {
            if (i == currentIndex) continue
            val queuedSong = live.getMediaItemAt(i).toSong()
            if (viewModel.shouldAvoidPlayback(queuedSong)) {
                live.removeMediaItem(i)
            }
        }
        live.currentMediaItem?.toSong()?.let { current ->
            if (viewModel.shouldAvoidPlayback(current) && live.repeatMode != Player.REPEAT_MODE_OFF) {
                live.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    fun signOutAndStopPlayback() {
        // Account media must never keep playing behind the signed-out welcome
        // screen. Stop first while the controller is still attached, then
        // remove the timeline so no stale track can resume after logout.
        controller?.stop()
        controller?.clearMediaItems()
        viewModel.signOut()
    }

    // Crossfade swaps the MediaSession backing player at handoff. During
    // that swap MediaController may briefly publish no current item, or rebuild
    // its timeline in more than one event. The Now Playing window must NOT use
    // that transient transport state as its own lifetime.
    //
    // Keep the last valid UI song until another valid song replaces it. We do not
    // clear this on player.song == null: showNowPlaying is the only owner of the
    // player's visibility. This separates "which track is current?" from
    // "is the Now Playing window open?".
    var retainedNowPlayingSong by remember { mutableStateOf<Song?>(null) }
    var pendingNowPlayingSong by remember { mutableStateOf<Song?>(null) }
    var pendingPlaybackCommitted by remember { mutableStateOf(false) }
    var playbackRequestGeneration by remember { mutableIntStateOf(0) }

    // INTRO_BED intentionally lets the transport/session move to B while A is
    // still the foreground record. Keep a UI-only copy of A so Now Playing can
    // follow perceived dominance instead of the technical MediaSession owner.
    val automixVisualTransition by
        AppSettings.automixVisualTransition.collectAsStateWithLifecycle()
    var automixVisualOutgoingSong by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(automixVisualTransition?.outgoingMediaId) {
        val visual = automixVisualTransition
        if (visual == null) {
            automixVisualOutgoingSong = null
        } else {
            automixVisualOutgoingSong = listOfNotNull(
                retainedNowPlayingSong,
                player.song,
                pendingNowPlayingSong,
            ).firstOrNull { it.videoId == visual.outgoingMediaId }
                ?: automixVisualOutgoingSong?.takeIf { it.videoId == visual.outgoingMediaId }
        }
    }

    LaunchedEffect(
        player.song,
        pendingPlaybackCommitted,
        pendingNowPlayingSong?.videoId,
        automixVisualTransition?.incomingMediaId,
    ) {
        player.song?.let { current ->
            val visual = automixVisualTransition
            val incomingStillVisuallyBlending = visual != null &&
                visual.progress < 0.999f &&
                current.videoId == visual.incomingMediaId
            if (!incomingStillVisuallyBlending) {
                retainedNowPlayingSong = current
            }
            if (pendingPlaybackCommitted && pendingNowPlayingSong?.videoId == current.videoId) {
                pendingNowPlayingSong = null
                pendingPlaybackCommitted = false
            }
        }
    }

    val automixVisualProgress = automixVisualTransition?.progress?.coerceIn(0f, 1f) ?: 1f
    val automixVisualActive = automixVisualTransition != null &&
        automixVisualOutgoingSong != null &&
        automixVisualProgress < 0.999f

    // The Automix wipe is a physical top-to-bottom frontier. Derive the
    // content handoff points from the actual screen aspect so metadata and the
    // seek timeline cannot jump together with the artwork. Order is:
    // artwork -> title/artist -> lyric strip -> ThinSlider -> transport area.
    val automixVisualConfiguration = LocalConfiguration.current
    val automixArtworkEndProgress = (
        automixVisualConfiguration.screenWidthDp.toFloat() /
            automixVisualConfiguration.screenHeightDp.coerceAtLeast(1).toFloat()
        ).coerceIn(0.30f, 0.72f)
    val automixLowerSpan = (1f - automixArtworkEndProgress).coerceAtLeast(0.01f)
    val automixSliderEndProgress =
        (automixArtworkEndProgress + automixLowerSpan * 0.43f).coerceIn(0f, 1f)

    // A newly tapped item owns the Now Playing surface immediately, even while
    // its audio-only identity and preferred source are still resolving. During
    // INTRO_BED, however, A remains the displayed song until the short visual
    // dissolve actually begins; B being technically current is not enough.
    val nowPlayingSong = when {
        pendingNowPlayingSong != null -> pendingNowPlayingSong
        automixVisualActive && automixVisualProgress <= 0.001f -> automixVisualOutgoingSong
        else -> player.song ?: retainedNowPlayingSong
    }

    // A tap on the MediaSession notification/Now Bar is a navigation request,
    // not merely an app-launch request. Keep it pending until MediaController
    // has restored the currently playing item, then open the existing Now
    // Playing surface directly. This works for both a resumed singleTask
    // activity and a cold Activity recreation while PlaybackService survives.
    LaunchedEffect(openNowPlayingRequest, nowPlayingSong?.videoId) {
        if (openNowPlayingRequest && nowPlayingSong != null) {
            showNowPlaying = true
            onOpenNowPlayingRequestConsumed()
        }
    }

    fun prepareNowPlayingLaunch(song: Song) {
        if (showNowPlaying || appRootSize.width <= 0 || appRootSize.height <= 0) return

        val preciseOrigin = NowPlayingLaunchOriginRegistry.consume()
        val fallbackSide = with(appDensity) { 76.dp.toPx() }
            .coerceAtMost(minOf(appRootSize.width, appRootSize.height).toFloat())
        val fallbackCenter = lastPointerDown ?: Offset(
            x = appRootSize.width * 0.18f,
            y = appRootSize.height * 0.72f,
        )
        val maxLeft = (appRootSize.width - fallbackSide).coerceAtLeast(0f)
        val maxTop = (appRootSize.height - fallbackSide).coerceAtLeast(0f)
        val fallbackOrigin = Rect(
            left = (fallbackCenter.x - fallbackSide / 2f).coerceIn(0f, maxLeft),
            top = (fallbackCenter.y - fallbackSide / 2f).coerceIn(0f, maxTop),
            right = (fallbackCenter.x - fallbackSide / 2f).coerceIn(0f, maxLeft) + fallbackSide,
            bottom = (fallbackCenter.y - fallbackSide / 2f).coerceIn(0f, maxTop) + fallbackSide,
        )

        nowPlayingLaunchToken += 1
        nowPlayingLaunchContentVisible = false
        nowPlayingLaunchVisual = NowPlayingLaunchVisual(
            token = nowPlayingLaunchToken,
            song = song,
            origin = preciseOrigin?.bounds ?: fallbackOrigin,
            originKind = preciseOrigin?.kind ?: NowPlayingLaunchOriginKind.ARTWORK,
        )
    }

    LaunchedEffect(nowPlayingLaunchVisual?.token) {
        val token = nowPlayingLaunchVisual?.token ?: return@LaunchedEffect
        // Let the source artwork do the visual navigation first. The full
        // player fades in underneath near the end so Material's sheet motion is
        // never what the listener perceives as the opening transition.
        // Start the real player under the transform early enough that it is
        // already opaque before the transform releases its backdrop. This
        // removes the one-frame Home flash visible in the old handoff.
        delay(180L)
        if (nowPlayingLaunchVisual?.token == token) {
            nowPlayingLaunchContentVisible = true
        }
        delay(320L)
        if (nowPlayingLaunchVisual?.token == token) {
            nowPlayingLaunchVisual = null
        }
    }

    fun beginPlaybackRequest(song: Song, showPreparingSet: Boolean = false): Int {
        playbackRequestGeneration += 1
        NerdStats.clearOpeningQualityPreflight(song.videoId)
        pendingNowPlayingSong = song
        pendingPlaybackCommitted = false
        retainedNowPlayingSong = song
        // Do not silence the current deck while an optional album-edition lookup
        // is still resolving. The new queue replaces it atomically once transport
        // is ready to start; this removes the dead-air gap between tap and first note.
        prepareNowPlayingLaunch(song)
        showNowPlaying = true
        return playbackRequestGeneration
    }

    fun finishPlaybackPreparation(requestGeneration: Int) {
        if (requestGeneration != playbackRequestGeneration) return
    }

    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()
    val activeNetworkMetered by AppSettings.meteredConnection.collectAsStateWithLifecycle()

    val automixForSuggestions by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    var autoplaySeed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        autoplay,
        automixForSuggestions,
        player.queueIndex,
        player.queue.size,
        player.song?.videoId,
        player.repeatMode,
        activeNetworkMetered,
    ) {
        val song = player.song ?: return@LaunchedEffect
        if (!autoplay || activeNetworkMetered == null) return@LaunchedEffect
        if (player.repeatMode == Player.REPEAT_MODE_ALL) return@LaunchedEffect

        // Refill only when the live queue is genuinely close to its end.
        // Two remaining tracks give Automix enough runway to inspect/order the next
        // batch without maintaining a large speculative radio tail.
        val remaining = (player.queue.lastIndex - player.queueIndex).coerceAtLeast(0)
        if (remaining > AUTOPLAY_PREFETCH_REMAINING) return@LaunchedEffect

        // Seed from the tail that will actually lead into the new batch, not
        // necessarily from the song playing right now. On an album this means
        // recommendations are related to its last track even when we fetch them
        // two songs early; on an existing AutoPlay run it means refilling from
        // the current tail rather than repeatedly from an older seed.
        val tail = player.queue.lastOrNull() ?: song
        val seedOwners = buildList {
            add(tail)
            if (song.videoId != tail.videoId) add(song)
            player.queue
                .asReversed()
                .asSequence()
                .filter { candidate -> candidate.videoId != tail.videoId && candidate.videoId != song.videoId }
                .take(AUTOPLAY_SEED_FALLBACKS)
                .forEach(::add)
        }.distinctBy { it.videoId }

        val seedId = tail.videoId
        if (autoplaySeed == seedId) return@LaunchedEffect
        autoplaySeed = seedId

        // De-dupe only against the recent listening tail. Keeping the entire
        // session as an exclusion set eventually exhausts a radio neighbourhood
        // and makes AutoPlay stop after a few batches.
        val recentQueue = player.queue.takeLast(AUTOPLAY_RECENT_DEDUPE_WINDOW)
        val discovered = mutableListOf<Song>()
        var anyRadioSucceeded = false

        for (owner in seedOwners) {
            if (discovered.size >= AUTOPLAY_REFILL_BATCH) break
            val ytSeed = youtubeSeedFor(owner) ?: continue
            YtMusicRepository.radio(ytSeed)
                .onSuccess { related ->
                    anyRadioSucceeded = true
                    val ordered = related
                        .filterNot(viewModel::shouldAvoidPlayback)
                        .sortedByDescending(viewModel::shouldPreferPlayback)
                    val extra = QueueBuilder.extend(
                        recentQueue + discovered,
                        ordered,
                        AUTOPLAY_REFILL_BATCH - discovered.size,
                    )
                    discovered += extra
                }
        }

        if (discovered.isNotEmpty()) {
            val resolved = coroutineScope {
                discovered
                    .take(AUTOPLAY_REFILL_BATCH)
                    .map { async { YtMusicRepository.resolveAudio(it) } }
                    .awaitAll()
            }
            controller?.addMediaItems(
                resolved
                    .filterNot(viewModel::shouldAvoidPlayback)
                    .take(AUTOPLAY_REFILL_BATCH)
                    .map { it.copy(fromAutoplay = true).toMediaItem() },
            )
        }

        // Never permanently poison an exhausted/failed seed. A later queue/song
        // update can immediately try again, and fallback seeds widen discovery
        // without abandoning the previous-track musical anchor.
        if (discovered.isEmpty() || !anyRadioSucceeded) {
            if (autoplaySeed == seedId) autoplaySeed = null
        }
    }

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()

    // Artwork starts the INTRO_BED visual handoff. Lyrics are deliberately later:
    // keep A's already-loaded line while B is only being revealed through the
    // cover, then hand the lyric owner to B near the end of the visual window.
    // Previously player.song changed at the technical session swap, which cleared
    // A's lyric strip before the artwork had visibly moved at all.
    val outgoingOwnsLyrics = automixVisualActive &&
        automixVisualProgress < automixSliderEndProgress
    val lyricsTargetSong = when {
        pendingNowPlayingSong != null -> pendingNowPlayingSong
        outgoingOwnsLyrics -> automixVisualOutgoingSong
        else -> player.song
    }
    val lyricsTargetDurationMs = when {
        pendingNowPlayingSong != null -> 0L
        outgoingOwnsLyrics -> automixVisualTransition?.outgoingDurationMs?.takeIf { it > 0L } ?: 0L
        else -> player.durationMs
    }
    LaunchedEffect(
        lyricsTargetSong?.videoId,
        lyricsTargetDurationMs,
        syncedLyricsEnabled,
        lyricsSources,
        prioritizeSyllableSync,
    ) {
        lyricsTargetSong?.let {
            viewModel.loadLyrics(it.videoId, it.title, it.artist, lyricsTargetDurationMs, it.albumName)
        }
    }

    // Each top-level surface keeps its own list position for the whole app
    // session. Home filters are independent pages too: switching to Releases
    // and back to For You restores the exact position instead of resetting it.
    val homeListStates = remember { List(4) { LazyListState() } }
    val homeHeaderCollapseOffsetPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val homeListState = homeListStates[homeSection.coerceIn(0, homeListStates.lastIndex)]
    val libraryListState = rememberLazyListState()
    LaunchedEffect(librarySearchActive, libraryQuery) {
        if (librarySearchActive) libraryListState.scrollToItem(0)
    }
    val searchListState = rememberLazyListState()
    val friendsListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val accountScrollState = rememberScrollState()
    val sourcesScrollState = rememberScrollState()
    val profileScrollState = rememberScrollState()
    LaunchedEffect(showOrbProfile, publicProfileUserId) {
        if (profilePageOpen) {
            profileTopBarInfo = profileTopBarInfo.copy(
                title = "",
                subtitle = null,
                collapseOffsetPx = Float.POSITIVE_INFINITY,
            )
            profileScrollState.scrollTo(0)
        }
    }
    val currentListState = when (selectedTab) {
        TAB_HOME -> homeListState
        TAB_EXPLORE -> searchListState
        TAB_LIBRARY -> libraryListState
        TAB_FRIENDS -> friendsListState
        else -> homeListState
    }

    val homePull = rememberPullToRefreshState()
    val explorePull = rememberPullToRefreshState()
    val libraryPull = rememberPullToRefreshState()
    val friendsPull = rememberPullToRefreshState()
    val detailPull = rememberPullToRefreshState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentFeed = when {
        showSettings || showSources || showAccountIntegrations || showOrbProfile || publicProfileUserId != null || detail != null -> null
        selectedTab == TAB_HOME -> when (homeSection) {
            1 -> MainViewModel.Feed.RELEASES
            2 -> MainViewModel.Feed.TRENDING
            3 -> MainViewModel.Feed.COMMUNITY_PLAYLISTS
            else -> MainViewModel.Feed.HOME
        }
        // Explore owns the discovery recommendations now; loading the feed here
        // keeps the fixed search header instant while the grid fills below it.
        selectedTab == TAB_EXPLORE -> MainViewModel.Feed.DISCOVER
        selectedTab == TAB_LIBRARY -> MainViewModel.Feed.LIBRARY
        else -> null
    }
    LaunchedEffect(currentFeed) {
        when (currentFeed) {
            MainViewModel.Feed.HOME -> viewModel.onHomeShown()
            MainViewModel.Feed.RELEASES -> viewModel.loadReleasesHub()
            MainViewModel.Feed.TRENDING -> viewModel.loadTrendingHub()
            MainViewModel.Feed.DISCOVER -> viewModel.loadDiscoverHub()
            MainViewModel.Feed.COMMUNITY_PLAYLISTS -> {
                viewModel.loadCommunityPlaylistsHub()
                viewModel.loadPlaylists()
            }
            MainViewModel.Feed.LIBRARY -> viewModel.onLibraryShown()
            else -> Unit
        }
    }

    // Only the listening-history shelf refreshes on a timer. Every other Home
    // shelf remains session-cached and pull-to-refresh stays explicit.
    LaunchedEffect(
        selectedTab,
        homeSection,
        showSettings,
        showSources,
        showAccountIntegrations,
        showOrbProfile,
        publicProfileUserId,
        detail?.browseId,
        lifecycleOwner,
    ) {
        val forYouVisible = selectedTab == TAB_HOME &&
                homeSection == 0 &&
                !showSettings &&
                !showSources &&
                !showAccountIntegrations &&
                !showOrbProfile &&
                publicProfileUserId == null &&
                detail == null
        if (!forYouVisible) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Suggestions owns the first paint/network priority. Give its cached
            // artwork and fresh Home batch a brief head start before the
            // cross-device recent-listening request opens another connection.
            delay(2_000L)
            while (true) {
                viewModel.refreshRecentlyPlayed()
                delay(RECENTLY_PLAYED_REFRESH_MS)
            }
        }
    }

    val currentPull = when (currentFeed) {
        MainViewModel.Feed.HOME,
        MainViewModel.Feed.RELEASES,
        MainViewModel.Feed.TRENDING,
        MainViewModel.Feed.COMMUNITY_PLAYLISTS -> homePull
        MainViewModel.Feed.EXPLORE,
        MainViewModel.Feed.DISCOVER -> explorePull
        MainViewModel.Feed.LIBRARY -> libraryPull
        null -> null
    }
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                    currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    val detailListStates = remember { mutableMapOf<String, LazyListState>() }
    val emptyDetailListState = remember { LazyListState() }
    val detailListState = detail?.browseId?.let { browseId ->
        detailListStates.getOrPut(browseId) { LazyListState() }
    } ?: emptyDetailListState
    val detailRefreshing = detail?.browseId?.let { it in refreshingDetails } == true
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                    detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    // Detail pages own their top haze independently from Home and Now Playing.
    // With the hero fully visible there is no top glass at all. The haze only
    // begins once the user actually scrolls, then ramps smoothly to full strength.
    val detailTopBlurIntensity by remember(detailListState) {
        derivedStateOf {
            if (detailListState.firstVisibleItemIndex > 0) {
                0.75f
            } else {
                val offset = detailListState.firstVisibleItemScrollOffset
                if (offset <= 0) {
                    0f
                } else {
                    (offset / 320f).coerceIn(0f, 1f) * 0.75f
                }
            }
        }
    }

    val profileScrolled by remember(profileScrollState, profileTopBarInfo.collapseOffsetPx) {
        derivedStateOf {
            val threshold = profileTopBarInfo.collapseOffsetPx
            threshold.isFinite() && profileScrollState.value.toFloat() >= threshold
        }
    }
    val profileTopBlurIntensity by remember(profileScrollState) {
        derivedStateOf {
            val offset = profileScrollState.value
            if (offset <= 0) 0f else (offset / 320f).coerceIn(0f, 1f) * 0.75f
        }
    }

    val tabPlay = stringResource(R.string.tab_play)
    val tabExplore = stringResource(R.string.tab_explore)
    val tabLibrary = stringResource(R.string.tab_library)
    val tabFriends = stringResource(R.string.tab_friends)

    // Search now lives inside Explore. Keeping it out of the bottom bar makes
    // Explore the single discovery/search destination instead of maintaining
    // two pages with overlapping purposes.
    val tabs = remember(tabPlay, tabExplore, tabLibrary, tabFriends) {
        listOf(
            BottomTab(tabPlay, BitChordIcons.Play),
            BottomTab(tabExplore, BitChordIcons.Explore),
            BottomTab(tabLibrary, BitChordIcons.Library),
            BottomTab(tabFriends, Icons.Rounded.BarChart),
        )
    }

    val scope = rememberCoroutineScope()

    suspend fun List<Song>.resolvedForQueue(): List<Song> = coroutineScope {
        map { async { YtMusicRepository.resolvePreferredPlaybackVersion(it) } }.awaitAll()
    }

    /**
     * Album-version preference must never become a several-second transport gate.
     * Start the lookup in the background, wait only through a short first-note
     * budget, then use the requested recording if the catalogue is still slow.
     * A timed-out lookup is cancelled and retried only after playback has had a
     * clean runway, so album canonicalisation never competes with first audio.
     */
    suspend fun preferredVersionForImmediateStart(song: Song): Song {
        if (!AppSettings.prioritizeAlbumVersions.value) return song
        val lookup = scope.async {
            runCatching { YtMusicRepository.resolvePreferredPlaybackVersion(song) }.getOrDefault(song)
        }
        val immediate = withTimeoutOrNull(ALBUM_VERSION_FIRST_NOTE_BUDGET_MS) { lookup.await() }
        if (immediate != null) return immediate

        // Once the first-note budget expires, stop competing with ExoPlayer for
        // the same connection pool. Warm the edition cache only after playback
        // has had a clean runway to become audible and start its quality upgrade.
        lookup.cancel()
        scope.launch {
            delay(ALBUM_VERSION_BACKGROUND_WARM_DELAY_MS)
            runCatching { YtMusicRepository.resolvePreferredPlaybackVersion(song) }
        }
        return song
    }

    val play: (List<Song>, Int) -> Unit = play@{ songs, index ->
        if (index !in songs.indices) return@play
        val requested = songs[index]
        // Search can still surface a disliked/blocked recording, but transport
        // must never start it again until the listener clears that preference.
        if (viewModel.shouldAvoidPlayback(requested)) return@play

        val queued = songs.filterNot(viewModel::shouldAvoidPlayback)
        if (queued.isEmpty()) return@play
        val eligibleIndex = songs.take(index).count { !viewModel.shouldAvoidPlayback(it) }
            .coerceIn(0, queued.lastIndex)

        autoplaySeed = null
        val requestGeneration = beginPlaybackRequest(
            requested,
            showPreparingSet = false,
        )
        scope.launch {
            try {
                // Keep the normal zero-delay transport path. Only the explicit
                // album-version preference is allowed to resolve the selected
                // single/remix before commit; quality upgrades still happen live.
                if (requestGeneration != playbackRequestGeneration) return@launch
                val preferredRequested = preferredVersionForImmediateStart(requested)
                if (viewModel.shouldAvoidPlayback(preferredRequested)) return@launch
                val playbackQueue = queued.toMutableList().apply {
                    this[eligibleIndex] = preferredRequested
                }
                pendingNowPlayingSong = preferredRequested
                val c = controller ?: return@launch
                c.playSongs(playbackQueue, eligibleIndex)
                pendingPlaybackCommitted = true
                playbackQueue.forEachIndexed { i, song ->
                    if (i == eligibleIndex) return@forEachIndexed
                    launch {
                        val resolved = YtMusicRepository.resolvePreferredPlaybackVersion(song)
                        if (viewModel.shouldAvoidPlayback(resolved)) return@launch
                        if (resolved.videoId == song.videoId && resolved.albumId == song.albumId) return@launch
                        val live = controller ?: return@launch
                        val at = (0 until live.mediaItemCount)
                            .firstOrNull { live.getMediaItemAt(it).mediaId == song.videoId }
                            ?: return@launch
                        val previousItem = live.getMediaItemAt(at)
                        val previousSong = previousItem.toSong()
                        val replacement = resolved.copy(
                            albumId = if (AppSettings.prioritizeAlbumVersions.value) {
                                resolved.albumId
                            } else {
                                previousSong.albumId ?: resolved.albumId
                            },
                            albumName = if (AppSettings.prioritizeAlbumVersions.value) {
                                resolved.albumName
                            } else {
                                previousSong.albumName ?: resolved.albumName
                            },
                            setVideoId = previousSong.setVideoId,
                            sourcePlaylistId = previousSong.sourcePlaylistId,
                            sourcePlaylistTitle = previousSong.sourcePlaylistTitle,
                            sourcePlaylistArtworkUrl = previousSong.sourcePlaylistArtworkUrl,
                            queuePinned = previousSong.queuePinned,
                            fromAutoplay = previousSong.fromAutoplay,
                        )
                        live.replaceMediaItem(
                            at,
                            replacement.toMediaItem(albumSequential = previousItem.albumSequential),
                        )
                    }
                }
            } finally {
                finishPlaybackPreparation(requestGeneration)
            }
        }
    }

    /**
     * Starts a radio session from an explicitly chosen recommendation.
     * The rotating Home carousel uses this path so the first tap immediately
     * creates a related AutoPlay tail and makes the persistent infinity toggle
     * reflect that AutoPlay session.
     */
    val playRadio: (Song) -> Unit = radio@{ song ->
        if (viewModel.shouldAvoidPlayback(song)) return@radio
        autoplaySeed = song.videoId
        val requestGeneration = beginPlaybackRequest(song)
        scope.launch {
            try {
                // Keep the recommendation carousel immediate unless the listener
                // explicitly enabled album-version preference; in that mode a
                // single/remix may be canonicalised before playback begins.
                if (requestGeneration != playbackRequestGeneration) return@launch
                val preferredSong = preferredVersionForImmediateStart(song)
                if (viewModel.shouldAvoidPlayback(preferredSong)) return@launch
                pendingNowPlayingSong = preferredSong
                val c = controller ?: return@launch
                c.playSongs(listOf(preferredSong), 0)
                pendingPlaybackCommitted = true

                // Choosing a recommendation explicitly starts an AutoPlay
                // session, so the infinity toggle must reflect what the queue
                // is actually doing. Seed first to prevent the ordinary
                // AutoPlay effect from racing this same radio request.
                autoplaySeed = preferredSong.videoId
                if (!AppSettings.autoplay.value) {
                    AppSettings.setAutoplay(true)
                }

                val seed = youtubeSeedFor(preferredSong) ?: return@launch
                YtMusicRepository.radio(seed).onSuccess { related ->
                    if (controller?.currentMediaItem?.mediaId != preferredSong.videoId) return@onSuccess
                    val extra = QueueBuilder.extend(
                        listOf(preferredSong),
                        related
                            .filterNot(viewModel::shouldAvoidPlayback)
                            .sortedByDescending(viewModel::shouldPreferPlayback),
                        RADIO_BATCH,
                    )
                    if (extra.isNotEmpty()) {
                        controller?.addMediaItems(
                            extra.resolvedForQueue().filterNot(viewModel::shouldAvoidPlayback).map {
                                it.copy(fromAutoplay = true).toMediaItem()
                            },
                        )
                    }
                }
            } finally {
                finishPlaybackPreparation(requestGeneration)
            }
        }
    }

    /**
     * Plays exactly one explicitly selected track in both release channels.
     * AutoPlay is not implied by a tap; once the listener explicitly enables
     * infinity, the normal effect above appends a randomized related batch.
     */
    val playSingle: (Song) -> Unit = single@{ song ->
        if (viewModel.shouldAvoidPlayback(song)) return@single
        autoplaySeed = null
        val requestGeneration = beginPlaybackRequest(song)
        scope.launch {
            try {
                // Same policy as queue playback: normal taps start immediately;
                // the opt-in album-version resolver may canonicalise a single/remix
                // first, while PlaybackService still handles quality upgrades live.
                if (requestGeneration != playbackRequestGeneration) return@launch
                val preferredSong = preferredVersionForImmediateStart(song)
                if (viewModel.shouldAvoidPlayback(preferredSong)) return@launch
                pendingNowPlayingSong = preferredSong
                val c = controller ?: return@launch
                c.playSongs(listOf(preferredSong), 0)
                pendingPlaybackCommitted = true
            } finally {
                finishPlaybackPreparation(requestGeneration)
            }
        }
    }

    val playDirect: (Song) -> Unit = { song ->
        playSingle(song)
    }
    val addToQueue: (Song) -> Unit = add@{ song ->
        if (viewModel.shouldAvoidPlayback(song)) return@add
        scope.launch {
            val resolved = YtMusicRepository.resolvePreferredPlaybackVersion(song)
            if (viewModel.shouldAvoidPlayback(resolved)) return@launch
            controller?.let { controller ->
                // "Add to queue" keeps
                // the listener's requested order, above the AutoPlay section.
                controller.addMediaItem(
                    controller.autoplaySectionStart(),
                    resolved.toMediaItem(),
                )
                OrbNoticeCenter.post(
                    OrbNotice(
                        title = context.getString(R.string.notice_added_to_queue),
                        message = resolved.title,
                        icon = OrbNoticeIcon.QUEUE,
                    ),
                )
            }
        }
    }
    val playNext: (Song) -> Unit = next@{ song ->
        if (viewModel.shouldAvoidPlayback(song)) return@next
        scope.launch {
            val resolved = YtMusicRepository.resolvePreferredPlaybackVersion(song)
            if (viewModel.shouldAvoidPlayback(resolved)) return@launch
            controller?.let { controller ->
                // "Play next" remains an explicit transport instruction even
                // while playback is active.
                val item = resolved.copy(queuePinned = true).toMediaItem()
                controller.addMediaItem(
                    (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount),
                    item,
                )
                OrbNoticeCenter.post(
                    OrbNotice(
                        title = context.getString(R.string.notice_play_next),
                        message = resolved.title,
                        icon = OrbNoticeIcon.PLAY_NEXT,
                    ),
                )
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    val postDownloadNotice: (List<Song>) -> Unit = { songs ->
        if (songs.isNotEmpty()) {
            OrbNoticeCenter.post(
                OrbNotice(
                    title = if (songs.size == 1) {
                        context.getString(R.string.notice_download_started)
                    } else {
                        context.getString(R.string.notice_downloads_started, songs.size)
                    },
                    message = songs.singleOrNull()?.title,
                    icon = OrbNoticeIcon.DOWNLOAD,
                ),
            )
        }
    }

    var downloadPending by remember { mutableStateOf<List<Song>>(emptyList()) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val songs = downloadPending
        downloadPending = emptyList()
        when {
            songs.isEmpty() -> Unit
            granted -> {
                songs.forEach { Downloads.enqueue(context, it) }
                postDownloadNotice(songs)
            }
            else -> Toast
                .makeText(context, "Storage access is needed to save songs", Toast.LENGTH_SHORT)
                .show()
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
        } else {
            Toast.makeText(context, "Storage permission is required to read local audio files", Toast.LENGTH_SHORT).show()
        }
    }

    val startDownload: (List<Song>) -> Unit = { requested ->
        val saved = Downloads.saved.value
        val songs = requested.filter { it.videoId !in saved }
        if (songs.isNotEmpty()) {
            val needsStorage = DownloadStore.needsLegacyPermission() &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (needsStorage) {
                downloadPending = songs
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                songs.forEach { Downloads.enqueue(context, it) }
                postDownloadNotice(songs)
            }
        } else if (requested.isNotEmpty()) {
            OrbNoticeCenter.post(
                OrbNotice(
                    title = context.getString(R.string.notice_already_downloaded),
                    message = requested.singleOrNull()?.title,
                    icon = OrbNoticeIcon.DOWNLOAD,
                ),
            )
        }
    }

    val listBottomPadding = if (OrbFlavorUi.expressive) {
        if (player.song != null) 228.dp else 154.dp
    } else {
        if (player.song != null) 210.dp else 140.dp
    }
    val listPadding = PaddingValues(
        top = if (OrbFlavorUi.expressive) 104.dp else 96.dp,
        bottom = listBottomPadding,
    )
    val homeListPadding = PaddingValues(
        top = if (OrbFlavorUi.expressive) 224.dp else 96.dp,
        bottom = listBottomPadding,
    )
    val exploreListPadding = PaddingValues(
        // Collapsed Home geometry: status bar + 62dp title row + 58dp search row,
        // plus the same breathing room used before the first shelf.
        top = if (OrbFlavorUi.expressive) 170.dp else 96.dp,
        bottom = listBottomPadding,
    )
    val statsHasPeriodHeader = OrbFlavorUi.expressive && !friendsSearchOpen && !statsNotificationsOpen
    val statsListPadding = PaddingValues(
        top = if (statsHasPeriodHeader) 162.dp else if (OrbFlavorUi.expressive) 104.dp else 96.dp,
        bottom = listBottomPadding,
    )

    val yourPlaylistsTitle = stringResource(R.string.expressive_playlists_yours)
    val recentPlaylistsTitle = stringResource(R.string.expressive_playlists_recent)
    val communityPlaylistsTitle = stringResource(R.string.expressive_playlists_community)
    // Keep the surrounding detail chrome on the exact same artwork-derived
    // background/foreground decision used by DetailScreen. This prevents the
    // collapsed title, back arrow and sheets from drifting away from the page.
    val detailSourcePalette = rememberArtworkPalette(
        detail?.thumbnailUrl,
        dark = darkTheme,
    )
    // DetailScreen uses the ArtworkPalette's own player foreground decision.
    // Reuse that exact decision for chrome and page-originated action sheets so
    // opening a sheet cannot flip a light page from black text to white text
    // (or the opposite).
    val detailSurfaceColor = rememberArtworkBottomEdgeColor(
        image = detail?.thumbnailUrl,
        fallback = detailSourcePalette.playerBackground,
    )
    val detailContentColor = bestForegroundForArtworkSurface(detailSurfaceColor)
    val detailPalette = detailSourcePalette.copy(
        background = detailSurfaceColor,
        wash = detailSurfaceColor,
        accent = detailContentColor,
        onBackground = detailContentColor,
        onBackgroundVariant = detailContentColor.copy(alpha = 0.78f),
        divider = detailContentColor.copy(alpha = 0.12f),
    )

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { appRootSize = it }
            .pointerInput(Unit) {
                // Observe, never consume. Every playback surface can therefore
                // fall back to the actual touch origin even if it has not yet
                // opted into precise artwork-bound tracking.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let {
                            lastPointerDown = it.position
                        }
                    }
                }
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        val canHandleAppBack = !showNowPlaying && (
            activeUpdateDownload != null ||
            showAutomaticUpdateDialog ||
                (detail == null && selectedTab == TAB_FRIENDS && friendsSearchOpen) ||
                (detail == null && selectedTab == TAB_FRIENDS && artistRankingOpen) ||
                (detail == null && selectedTab == TAB_FRIENDS && statsNotificationsOpen) ||
                showUpdateDialog ||
                showListenBrainzLogin ||
                showLastfmLogin ||
                publicProfileUserId != null ||
                showOrbProfile ||
                showAccountIntegrations ||
                showSources ||
                showSettings ||
                detail != null ||
                tabBackStack.isNotEmpty() ||
                selectedTab != TAB_HOME
            )

        fun navigateBackOneLevel(): Boolean {
            when {
                activeUpdateDownload != null -> activeUpdateDownload?.release?.tagName?.let(UpdateDownloadStore::requestCancel)
                showAutomaticUpdateDialog -> showAutomaticUpdateDialog = false
                detail == null && selectedTab == TAB_FRIENDS && friendsSearchOpen ->
                    friendsViewModel.closeUserSearch()
                detail == null && selectedTab == TAB_FRIENDS && artistRankingOpen ->
                    friendsViewModel.closeArtistRanking()
                detail == null && selectedTab == TAB_FRIENDS && statsNotificationsOpen ->
                    friendsViewModel.closeNotifications()
                showUpdateDialog -> showUpdateDialog = false
                showListenBrainzLogin -> showListenBrainzLogin = false
                showLastfmLogin -> showLastfmLogin = false
                publicProfileUserId != null -> publicProfileUserId = null
                showOrbProfile -> {
                    showOrbProfile = false
                    showAccountIntegrations = true
                }
                showAccountIntegrations -> showAccountIntegrations = false
                showSources -> {
                    showSources = false
                    showSettings = true
                }
                showSettings -> showSettings = false
                detail != null -> viewModel.closeDetail()
                tabBackStack.isNotEmpty() -> {
                    selectedTab = tabBackStack.last()
                    tabBackStack = tabBackStack.dropLast(1)
                }
                selectedTab != TAB_HOME -> selectedTab = TAB_HOME
                else -> return false
            }
            return true
        }

        // Activity Compose's predictive handler is wired to Android's native back
        // dispatcher. While the gesture is in progress the current page follows
        // the finger slightly; the actual navigation is committed only when the
        // system invokes Back. Cancelling the gesture leaves the page untouched.
        PredictiveBackHandler(enabled = canHandleAppBack) { progress ->
            try {
                progress.collect { event ->
                    predictiveBackProgress = event.progress.coerceIn(0f, 1f)
                }
                navigateBackOneLevel()
            } catch (_: CancellationException) {
                // Native predictive back was cancelled: keep the current page.
            } finally {
                predictiveBackProgress = 0f
            }
        }

        val contentTarget = AppContentTarget(
            kind = when {
                publicProfileUserId != null -> AppContentKind.PUBLIC_PROFILE
                showOrbProfile -> AppContentKind.ORB_PROFILE
                showAccountIntegrations -> AppContentKind.ACCOUNT
                showSources -> AppContentKind.SOURCES
                showSettings -> AppContentKind.SETTINGS
                detail != null -> AppContentKind.DETAIL
                else -> AppContentKind.TAB
            },
            tab = selectedTab,
            homeSection = homeSection,
            detailDepth = detailStack.size,
            detail = detail,
            profileId = publicProfileUserId,
        )
        AnimatedContent(
            targetState = contentTarget,
            contentKey = { it.key },
            transitionSpec = {
                val forward = when {
                    initialState.kind == AppContentKind.ORB_PROFILE && targetState.kind != AppContentKind.ORB_PROFILE -> false
                    initialState.kind == AppContentKind.PUBLIC_PROFILE && targetState.kind != AppContentKind.PUBLIC_PROFILE -> false
                    targetState.kind == AppContentKind.ORB_PROFILE || targetState.kind == AppContentKind.PUBLIC_PROFILE -> true
                    initialState.kind != AppContentKind.DETAIL && targetState.kind == AppContentKind.DETAIL -> true
                    initialState.kind == AppContentKind.DETAIL && targetState.kind != AppContentKind.DETAIL -> false
                    initialState.kind == AppContentKind.DETAIL && targetState.kind == AppContentKind.DETAIL ->
                        targetState.detailDepth >= initialState.detailDepth
                    initialState.kind == AppContentKind.TAB && targetState.kind == AppContentKind.TAB ->
                        if (initialState.tab == targetState.tab) {
                            targetState.homeSection >= initialState.homeSection
                        } else {
                            targetState.tab >= initialState.tab
                        }
                    else -> true
                }
                val direction = if (forward) 1 else -1
                val changingHomeSection =
                    initialState.kind == AppContentKind.TAB &&
                        targetState.kind == AppContentKind.TAB &&
                        initialState.tab == TAB_HOME &&
                        targetState.tab == TAB_HOME &&
                        initialState.homeSection != targetState.homeSection

                if (changingHomeSection) {
                    // Home filters are peers, not navigation destinations. Use a
                    // restrained shared-axis/fade-through movement and keep both
                    // pages clipped to the same viewport. Removing the scale
                    // transform also removes the little vertical "hop" visible
                    // when a dense shelf enters from another filter.
                    (
                        slideInHorizontally(
                            animationSpec = tween(420, easing = FastOutSlowInEasing),
                            initialOffsetX = { width -> direction * width / 5 },
                        ) + fadeIn(
                            animationSpec = tween(300, delayMillis = 55, easing = FastOutSlowInEasing),
                        )
                    ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(360, easing = FastOutSlowInEasing),
                            targetOffsetX = { width -> -direction * width / 8 },
                        ) + fadeOut(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        )
                    )
                } else {
                    (
                        slideInHorizontally(
                            animationSpec = tween(360),
                            initialOffsetX = { width -> direction * width / 7 },
                        ) + fadeIn(tween(240)) + scaleIn(tween(360), initialScale = 0.985f)
                    ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(300),
                            targetOffsetX = { width -> -direction * width / 10 },
                        ) + fadeOut(tween(190)) + scaleOut(tween(300), targetScale = 0.992f)
                    )
                }
            },
            modifier = Modifier
                .hazeSource(hazeState)
                .graphicsLayer {
                    translationX = predictiveBackProgress * 34.dp.toPx()
                    val backScale = 1f - predictiveBackProgress * 0.012f
                    scaleX = backScale
                    scaleY = backScale
                },
            label = "content",
        ) { target ->
            val page = target.detail
            if (target.kind == AppContentKind.PUBLIC_PROFILE && target.profileId != null) {
                PublicProfileScreen(
                    userId = target.profileId,
                    contentPadding = listPadding,
                    onBack = { publicProfileUserId = null },
                    onOpenProfile = { profileId ->
                        if (profileId != SocialRepository.currentUserId()) {
                            publicProfileUserId = profileId
                            scope.launch { profileScrollState.scrollTo(0) }
                        }
                    },
                    scrollState = profileScrollState,
                    onTopBarInfoChange = { profileTopBarInfo = it },
                )
            } else if (target.kind == AppContentKind.ORB_PROFILE) {
                OrbProfileScreen(
                    account = displayAccount,
                    contentPadding = listPadding,
                    onBack = {
                        showOrbProfile = false
                        showAccountIntegrations = true
                    },
                    onOpenProfile = { profileId ->
                        if (profileId != SocialRepository.currentUserId()) {
                            publicProfileUserId = profileId
                            scope.launch { profileScrollState.scrollTo(0) }
                        }
                    },
                    scrollState = profileScrollState,
                    onTopBarInfoChange = { profileTopBarInfo = it },
                )
            } else if (target.kind == AppContentKind.ACCOUNT) {
                AccountIntegrationsScreen(
                    signedIn = signedIn,
                    account = displayAccount,
                    youtubeAccount = youtubeAccount,
                    youtubeNeedsSetup = youtubeCompatibilityRequired,
                    onSignIn = {
                        showAccountIntegrations = false
                        showSettings = false
                        requestGoogleSignIn()
                    },
                    onOpenOrbProfile = {
                        showAccountIntegrations = false
                        showOrbProfile = true
                    },
                    onManageYouTube = {
                        if (!youtubeIdentitySwitching) {
                            youtubeIdentitySwitching = true
                            scope.launch {
                                val identities = OrbGoogleAuth.availableYoutubeIdentities()
                                youtubeIdentitySwitching = false
                                if (identities.isNotEmpty()) {
                                    youtubeIdentityPicker = identities
                                } else {
                                    OrbGoogleAuth.requestYoutubeChannelSwitch()
                                    showLogin = true
                                }
                            }
                        }
                    },
                    onSignOut = { signOutAndStopPlayback() },
                    contentPadding = listPadding,
                    scrollState = accountScrollState,
                )
            } else if (target.kind == AppContentKind.SOURCES) {
                SourcesScreen(
                    contentPadding = listPadding,
                    scrollState = sourcesScrollState,
                )
            } else if (target.kind == AppContentKind.SETTINGS) {
                SettingsScreen(
                    onSources = {
                        showSettings = false
                        showSources = true
                    },
                    onLyricsSources = { showLyricsSources = true },
                    account = displayAccount,
                    contentPadding = listPadding,
                    scrollState = if (OrbFlavorUi.expressive) settingsScrollState else null,
                )
            } else if (page != null && page.browseId.startsWith("local:")) {
                val localState = page.songs
                val localSongs = (localState as? com.music.orb.data.model.UiState.Success)
                    ?.data.orEmpty()
                LocalMusicScreen(
                    songs = localSongs,
                    onSongClick = play,
                    onSongLongPress = { songActions = it },
                    onSongSwipe = onSongSwipe,
                    onShuffle = { songs ->
                        QueueShuffle.enableForNextQueue()
                        play(songs, songs.indices.random())
                    },
                    emptyMessage = (localState as? com.music.orb.data.model.UiState.Error)
                        ?.message,
                    refreshing = detailRefreshing,
                    onRefresh = { viewModel.refreshDetail(page.browseId) },
                    pullState = detailPull,
                    isDownloadsPage = page.browseId == "local:downloads",
                    onRemoveAllDownloads = if (page.browseId == "local:downloads") {
                        {
                            scope.launch {
                                LocalMediaRepository.deleteAllDownloads(context)
                                viewModel.reloadLocalDetail("local:downloads")
                            }
                        }
                    } else {
                        null
                    },
                    contentPadding = listPadding,
                )
            } else if (page != null) {
                val withAlbum: (Song) -> Song = { song ->
                    when (page.type) {
                        BrowseType.ALBUM -> song.copy(
                            albumId = page.browseId,
                            albumName = song.albumName ?: page.title,
                        )
                        BrowseType.PLAYLIST -> song.copy(
                            sourcePlaylistId = page.browseId,
                            sourcePlaylistTitle = page.title,
                            sourcePlaylistArtworkUrl = page.thumbnailUrl,
                        )
                        else -> song
                    }
                }
                val playDetail: (List<Song>, Int) -> Unit = { songs, index ->
                    play(songs.map(withAlbum), index)
                }
                // Album / artist / playlist CONTENT is composed under the app's
                // real dark theme in both release channels so the page body matches
                // Dark mode exactly. Surrounding chrome still follows the selected theme.
                DetailContentTheme(enabled = forceDarkDetail) {
                    DetailScreen(
                        page = page,
                        listState = detailListState,
                        onSongClick = playDetail,
                        onSongLongPress = { songActions = withAlbum(it) },
                        onSongSwipe = onSongSwipe,
                        onSongAddToQueue = { song -> addToQueue(withAlbum(song)) },
                        onSongPlayNext = { song -> playNext(withAlbum(song)) },
                        onShuffle = { songs ->
                            QueueShuffle.enableForNextQueue()
                            val contextual = songs.map(withAlbum)
                            play(contextual, contextual.indices.random())
                        },
                        onSectionItemClick = { item ->
                            item.browseId?.let { id ->
                                viewModel.openDetail(
                                    browseId = id,
                                    title = item.title,
                                    subtitle = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    type = BrowseType.ALBUM,
                                    isExplicit = item.isExplicit,
                                )
                            }
                        },
                        onDownloadAll = { songs -> startDownload(songs.map(withAlbum)) },
                        onArtistClick = { id, name ->
                            viewModel.openDetail(id, name, "Artist", null, BrowseType.ARTIST)
                        },
                        onAddSuggested = { song -> viewModel.addSuggestedSong(page.browseId, song) },
                        onToggleLibrary = if (signedIn) {
                            {
                                OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                                viewModel.toggleLibrary(page.browseId)
                            }
                        } else {
                            null
                        },
                        artistLiked = page.type == BrowseType.ARTIST &&
                            artistPreferences[ArtistPreferenceStore.artistKey(page.title)] == ArtistPreference.LIKE,
                        artistBlocked = page.type == BrowseType.ARTIST &&
                            artistPreferences[ArtistPreferenceStore.artistKey(page.title)] == ArtistPreference.BLOCK,
                        onToggleArtistLike = if (page.type == BrowseType.ARTIST) {
                            { viewModel.toggleArtistLike(page.title) }
                        } else null,
                        onToggleArtistBlock = if (page.type == BrowseType.ARTIST) {
                            { viewModel.toggleArtistBlock(page.title) }
                        } else null,
                        refreshing = detailRefreshing,
                        onRefresh = { viewModel.refreshDetail(page.browseId) },
                        pullState = detailPull,
                        onLoadMore = { viewModel.loadMoreDetail(page.browseId) },
                        contentPadding = listPadding,
                    )
                }
            } else when (target.tab) {
                TAB_HOME -> HomeScreen(
                    state = when (target.homeSection) {
                        1 -> buildAlbumsState(
                            releasesHubState,
                            homeTopAlbums,
                            favoritesLoading = signedIn && !homeStatsReady,
                        )
                        2 -> trendingHubState
                        3 -> buildPlaylistsState(
                            community = communityPlaylistsHubState,
                            ownPlaylists = userPlaylists,
                            ownPlaylistsLoading = userPlaylistsLoading,
                            ownPlaylistsLoaded = userPlaylistsLoaded,
                            signedIn = signedIn,
                            yoursTitle = yourPlaylistsTitle,
                            recentTitle = recentPlaylistsTitle,
                            communityTitle = communityPlaylistsTitle,
                        )
                        else -> buildForYouState(
                            homeState,
                            libraryState,
                            homeTopArtists,
                            homeYouTubeSections,
                            relatedHomeSuggestions,
                        )
                    },
                    listState = homeListStates[
                        target.homeSection.coerceIn(0, homeListStates.lastIndex)
                    ],
                    signedIn = signedIn,
                    onSignIn = { requestGoogleSignIn() },
                    onItemClick = { item ->
                        when {
                            item.videoId != null -> item.asSong()?.let { playDirect(it) }
                            item.browseId != null -> viewModel.openDetail(
                                browseId = item.browseId,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                type = item.type,
                                isExplicit = item.isExplicit,
                            )
                            item.type == BrowseType.ALBUM -> scope.launch {
                                val query = listOf(item.title, item.subtitle)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                                val album = YtMusicRepository.search(query, SearchFilter.ALBUMS)
                                    .getOrNull()
                                    .orEmpty()
                                    .filterIsInstance<SearchResult.Browse>()
                                    .map { it.item }
                                    .filter { it.type == BrowseType.ALBUM }
                                    .minByOrNull { candidate ->
                                        val titleMatches =
                                            normalizedShelfTitle(candidate.title) == normalizedShelfTitle(item.title)
                                        val artistKey = normalizedShelfTitle(item.subtitle)
                                        val artistMatches = artistKey.isBlank() ||
                                            normalizedShelfTitle(candidate.subtitle).contains(artistKey)
                                        when {
                                            titleMatches && artistMatches -> 0
                                            titleMatches -> 1
                                            else -> 2
                                        }
                                    }
                                album?.let { resolved ->
                                    viewModel.openDetail(
                                        browseId = resolved.browseId,
                                        title = resolved.title,
                                        subtitle = resolved.subtitle,
                                        thumbnailUrl = resolved.thumbnailUrl,
                                        type = BrowseType.ALBUM,
                                        isExplicit = resolved.isExplicit,
                                    )
                                }
                            }
                            item.type == BrowseType.ARTIST -> scope.launch {
                                val artist = YtMusicRepository.search(item.title, SearchFilter.ARTISTS)
                                    .getOrNull()
                                    .orEmpty()
                                    .filterIsInstance<SearchResult.Browse>()
                                    .map { it.item }
                                    .filter { it.type == BrowseType.ARTIST }
                                    .minByOrNull { candidate ->
                                        if (normalizedShelfTitle(candidate.title) == normalizedShelfTitle(item.title)) 0 else 1
                                    }
                                artist?.let { resolved ->
                                    viewModel.openDetail(
                                        browseId = resolved.browseId,
                                        title = resolved.title,
                                        subtitle = resolved.subtitle,
                                        thumbnailUrl = resolved.thumbnailUrl,
                                        type = BrowseType.ARTIST,
                                    )
                                }
                            }
                        }
                    },
                    onItemLongPress = { item ->
                        if (!item.videoId.isNullOrBlank()) {
                            songActions = item.asSong()
                        } else if (!item.browseId.isNullOrBlank()) {
                            // Collection cards have their actions on the detail page;
                            // a long press takes the user straight to that context.
                            viewModel.openDetail(
                                browseId = requireNotNull(item.browseId),
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                type = item.type,
                                isExplicit = item.isExplicit,
                            )
                        }
                    },
                    onRankedSongClick = { songs, index -> play(songs, index) },
                    onRecommendationClick = { item ->
                        item.asSong()?.let { song -> playRadio(song) }
                    },
                    onRetry = {
                        when (target.homeSection) {
                            1 -> viewModel.loadReleasesHub(force = true)
                            2 -> viewModel.loadTrendingHub(force = true)
                            3 -> {
                                viewModel.loadCommunityPlaylistsHub(force = true)
                                viewModel.loadPlaylists()
                            }
                            else -> viewModel.loadHome()
                        }
                    },
                    refreshing = currentFeed != null && currentFeed in refreshing,
                    onRefresh = {
                        currentFeed?.let { feed ->
                            if (feed == MainViewModel.Feed.HOME && target.homeSection == 0) {
                                HomeRecommendationSession.requestRefresh()
                            }
                            viewModel.refresh(feed)
                        }
                    },
                    pullState = currentPull ?: homePull,
                    contentPadding = homeListPadding,
                    onLoadMore = null,
                    loadingMore = false,
                    fullTrackBadgesInShelves = true,
                    homeHeaderMode = OrbFlavorUi.expressive,
                    heroFirstShelf = target.homeSection == 0,
                )
                TAB_EXPLORE -> SearchScreen(
                    query = query,
                    onQueryChange = { value ->
                        exploreSearchActive = true
                        viewModel.onQueryChange(value)
                    },
                    filter = filter,
                    onFilterChange = viewModel::onFilterChange,
                    results = results,
                    listState = searchListState,
                    // The real text field now belongs to ExploreFrostedHeader.
                    // This composable owns only suggestions, filters, history
                    // and results below the glass header.
                    showSearchField = !OrbFlavorUi.expressive,
                    // Recent searches + discovery are the idle Explore page.
                    // The fixed Expressive header remains the only search field.
                    showIdleContent = true,
                    showHeaderAura = false,
                    onSearchActivated = { exploreSearchActive = true },
                    onSongClick = { songs, index ->
                        songs.getOrNull(index)?.let { song ->
                            viewModel.recordSearch(song)
                            playDirect(song)
                        }
                    },
                    onSongLongPress = { songActions = it },
                    onSongSwipe = onSongSwipe,
                    onBrowseClick = { item ->
                        viewModel.recordSearch(item)
                        viewModel.openDetail(
                            browseId = item.browseId,
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                            type = item.type,
                            isExplicit = item.isExplicit,
                        )
                    },
                    onBrowseLongPress = { item ->
                        viewModel.recordSearch(item)
                        viewModel.openDetail(
                            browseId = item.browseId,
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                            type = item.type,
                            isExplicit = item.isExplicit,
                        )
                    },
                    // SearchHistory is newest-first; Explore shows only the four most recent items.
                    history = searchHistory.take(4),
                    discoverState = discoverHubState,
                    onDiscoverItemClick = { item ->
                        when {
                            item.videoId != null -> playDirect(
                                Song(
                                    videoId = item.videoId,
                                    title = item.title,
                                    artist = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    isExplicit = item.isExplicit,
                                ),
                            )
                            item.browseId != null -> viewModel.openDetail(
                                browseId = item.browseId,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                type = item.type,
                                isExplicit = item.isExplicit,
                            )
                        }
                    },
                    onDiscoverItemLongPress = { item ->
                        if (!item.videoId.isNullOrBlank()) {
                            songActions = Song(
                                videoId = requireNotNull(item.videoId),
                                title = item.title,
                                artist = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                isExplicit = item.isExplicit,
                            )
                        } else if (!item.browseId.isNullOrBlank()) {
                            viewModel.openDetail(
                                browseId = requireNotNull(item.browseId),
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                type = item.type,
                                isExplicit = item.isExplicit,
                            )
                        }
                    },
                    onDiscoverRetry = { viewModel.loadDiscoverHub(force = true) },
                    suggestions = searchSuggestions,
                    onSubmit = viewModel::submitSearch,
                    onSuggestionClick = viewModel::searchFor,
                    onHistoryClick = viewModel::searchFor,
                    onHistoryRemove = viewModel::removeSearch,
                    onHistoryClear = viewModel::clearSearchHistory,
                    refreshing = if (query.isBlank()) {
                        MainViewModel.Feed.DISCOVER in refreshing
                    } else {
                        searchRefreshing
                    },
                    onRefresh = {
                        if (query.isBlank()) {
                            viewModel.refresh(MainViewModel.Feed.DISCOVER)
                        } else {
                            viewModel.refreshSearch()
                        }
                    },
                    pullState = explorePull,
                    contentPadding = if (OrbFlavorUi.expressive) exploreListPadding else listPadding,
                )
                TAB_LIBRARY -> LibraryScreen(
                    signedIn = signedIn,
                    usingOrbLibrary = !useYouTubeLibrary,
                    state = libraryState,
                    query = libraryQuery,
                    listState = libraryListState,
                    onShelfItemClick = { item ->
                        when {
                            !item.videoId.isNullOrBlank() -> playDirect(
                                Song(
                                    videoId = requireNotNull(item.videoId),
                                    title = item.title,
                                    artist = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    isExplicit = item.isExplicit,
                                ),
                            )
                            item.browseId != null -> {
                                val id = requireNotNull(item.browseId)
                                if (id == "local:all" && !LocalMediaRepository.hasStoragePermission(context)) {
                                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        Manifest.permission.READ_MEDIA_AUDIO
                                    } else {
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                    }
                                    mediaPermissionLauncher.launch(perm)
                                }
                                viewModel.openDetail(
                                    browseId = id,
                                    title = item.title,
                                    subtitle = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    type = item.type,
                                    isExplicit = item.isExplicit,
                                )
                            }
                        }
                    },
                    onShelfItemLongPress = { item ->
                        if (useYouTubeLibrary) {
                            playlistActions = viewModel.editablePlaylist(item.browseId)
                        }
                    },
                    onNewPlaylist = { creatingPlaylist = true },
                    onSignIn = { requestGoogleSignIn() },
                    onRetry = viewModel::loadLibrary,
                    refreshing = MainViewModel.Feed.LIBRARY in refreshing,
                    onRefresh = { viewModel.refresh(MainViewModel.Feed.LIBRARY) },
                    pullState = libraryPull,
                    contentPadding = listPadding,
                )
                TAB_FRIENDS -> FriendsScreen(
                    listState = friendsListState,
                    pullState = friendsPull,
                    contentPadding = statsListPadding,
                    friendsViewModel = friendsViewModel,
                    onOpenProfile = { profile ->
                        publicProfileUserId = profile.id
                    },
                )
                else -> Unit
            }
        }

        val topFadeBlurIntensity = when {
            profilePageOpen -> profileTopBlurIntensity
            isDetailVisible -> detailTopBlurIntensity
            else -> 0f
        }
        if (topFadeBlurIntensity > 0f) {
            TopFadeBlur(
                hazeState = hazeState,
                pageColor = if (profilePageOpen) profileTopBarInfo.pageColor else detailPalette.wash,
                peakIntensity = topFadeBlurIntensity,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        val titleListenNow = stringResource(R.string.title_listen_now)
        val titleSettingsText = stringResource(R.string.title_settings)
        val titleAccountText = stringResource(R.string.title_account_integrations)
        val titleAudioSourcesText = stringResource(R.string.audio_sources_title)
        val titleOrbProfileText = stringResource(R.string.title_orb_profile)

        MaterialTheme(
            colorScheme = when {
                isDetailVisible -> MaterialTheme.colorScheme.copy(
                    primary = detailPalette.onBackground,
                    onBackground = detailPalette.onBackground,
                    onSurface = detailPalette.onBackground,
                    onSurfaceVariant = detailPalette.onBackgroundVariant,
                )
                profilePageOpen -> MaterialTheme.colorScheme.copy(
                    onBackground = profileTopBarInfo.contentColor,
                    onSurface = profileTopBarInfo.contentColor,
                    onSurfaceVariant = profileTopBarInfo.contentVariantColor,
                )
                else -> MaterialTheme.colorScheme
            },
        ) {
            val useExpandedHomeHeader = OrbFlavorUi.expressive &&
                    !showSettings &&
                    !showSources &&
                    !showAccountIntegrations &&
                    !profilePageOpen &&
                    detail == null &&
                    selectedTab == TAB_HOME
            val useCompactExploreHeader = OrbFlavorUi.expressive &&
                    !showSettings &&
                    !showSources &&
                    !showAccountIntegrations &&
                    !profilePageOpen &&
                    detail == null &&
                    selectedTab == TAB_EXPLORE
            val useLibraryHeader = OrbFlavorUi.expressive &&
                    !showSettings &&
                    !showSources &&
                    !showAccountIntegrations &&
                    !profilePageOpen &&
                    detail == null &&
                    selectedTab == TAB_LIBRARY
            val useStatsHeader = !showSettings &&
                    !showSources &&
                    !showAccountIntegrations &&
                    !profilePageOpen &&
                    detail == null &&
                    selectedTab == TAB_FRIENDS

            if (useExpandedHomeHeader) {
                HomeFrostedHeader(
                    account = displayAccount,
                    hazeState = hazeState,
                    scrolled = homeHeaderCollapsedDuringSectionTransition ?: scrolled,
                    selectedSection = homeSection,
                    sections = listOf(
                        stringResource(R.string.expressive_home_for_you),
                        stringResource(R.string.expressive_home_new_releases),
                        stringResource(R.string.expressive_trending_now),
                        stringResource(R.string.expressive_discovery_community),
                    ),
                    onSectionSelected = { section ->
                        if (homeSection != section) {
                            val targetState = homeListStates[
                                section.coerceIn(0, homeListStates.lastIndex)
                            ]
                            val keepCollapsed = homeHeaderCollapsedDuringSectionTransition ?: scrolled
                            val targetNeedsCollapsePosition =
                                targetState.firstVisibleItemIndex == 0 &&
                                    targetState.firstVisibleItemScrollOffset <= 24

                            // Keep the current header geometry only while the peer
                            // feeds cross the screen. Crucially, select the new feed
                            // *before* touching its LazyListState. scrollToItem() can
                            // suspend until first layout when a state belongs to an
                            // off-screen list; doing it first used to deadlock the tab
                            // change and leave this header lock set forever.
                            homeSectionTransitionGeneration += 1
                            val transitionGeneration = homeSectionTransitionGeneration
                            homeHeaderCollapsedDuringSectionTransition = keepCollapsed
                            homeSection = section

                            scope.launch {
                                if (keepCollapsed && targetNeedsCollapsePosition) {
                                    // Give AnimatedContent one frame to compose the
                                    // incoming HomeScreen, then align it with the
                                    // already-collapsed header. The timeout is a hard
                                    // safety net: tab navigation must never wait for a
                                    // LazyListState to become attached.
                                    delay(16L)
                                    withTimeoutOrNull(240L) {
                                        targetState.scrollToItem(0, homeHeaderCollapseOffsetPx)
                                    }
                                }

                                delay(440L)
                                if (homeSectionTransitionGeneration == transitionGeneration) {
                                    homeHeaderCollapsedDuringSectionTransition = null
                                }
                            }
                        }
                    },
                    onAccountClick = { showAccountIntegrations = true },
                    onSettingsClick = { showSettings = true },
                    refreshing = currentFeed != null && currentFeed in refreshing,
                    pullFraction = { currentPull?.distanceFraction ?: 0f },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else if (useCompactExploreHeader) {
                ExploreFrostedHeader(
                    account = displayAccount,
                    hazeState = hazeState,
                    title = tabExplore,
                    searchHint = stringResource(R.string.search_hint),
                    query = query,
                    onQueryChange = { value ->
                        exploreSearchActive = true
                        viewModel.onQueryChange(value)
                    },
                    onSearchSubmit = {
                        exploreSearchActive = true
                        viewModel.submitSearch()
                    },
                    onSearchActivated = { exploreSearchActive = true },
                    focusTrigger = searchFocusTrigger,
                    onAccountClick = { showAccountIntegrations = true },
                    refreshing = if (query.isBlank()) {
                        MainViewModel.Feed.DISCOVER in refreshing
                    } else {
                        searchRefreshing
                    },
                    pullFraction = { explorePull.distanceFraction },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else if (useLibraryHeader) {
                LibraryFrostedHeader(
                    account = displayAccount,
                    hazeState = hazeState,
                    title = tabLibrary,
                    searchActive = librarySearchActive,
                    query = libraryQuery,
                    onQueryChange = { libraryQuery = it },
                    onAccountClick = { showAccountIntegrations = true },
                    onSearchClick = { librarySearchActive = true },
                    onCloseSearch = {
                        librarySearchActive = false
                        libraryQuery = ""
                    },
                    refreshing = MainViewModel.Feed.LIBRARY in refreshing,
                    pullFraction = { libraryPull.distanceFraction },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else if (useStatsHeader) {
                StatsFrostedHeader(
                    hazeState = hazeState,
                    title = tabFriends,
                    searchTitle = when {
                        statsNotificationsOpen -> stringResource(R.string.stats_notifications_title)
                        artistRankingOpen -> stringResource(R.string.stats_artist_ranking_title)
                        else -> stringResource(R.string.stats_user_search_title)
                    },
                    searchMode = friendsSearchOpen || artistRankingOpen || statsNotificationsOpen,
                    notificationCount = statsNotificationUnread,
                    periodLabels = listOf(
                        stringResource(R.string.stats_period_week),
                        stringResource(R.string.stats_period_month),
                        stringResource(R.string.stats_period_quarter),
                        stringResource(R.string.stats_period_semester),
                        stringResource(R.string.stats_period_year),
                    ),
                    selectedPeriodIndex = StatsPeriod.values().indexOf(statsPeriod).coerceAtLeast(0),
                    onPeriodSelected = { index ->
                        StatsPeriod.values().getOrNull(index)?.let(friendsViewModel::setStatsPeriod)
                    },
                    showPeriodSelector = !friendsSearchOpen && !statsNotificationsOpen,
                    onSearchClick = friendsViewModel::openUserSearch,
                    onNotificationsClick = friendsViewModel::openNotifications,
                    onBack = when {
                        statsNotificationsOpen -> friendsViewModel::closeNotifications
                        artistRankingOpen -> friendsViewModel::closeArtistRanking
                        else -> friendsViewModel::closeUserSearch
                    },
                    refreshing = friendsRefreshing,
                    pullFraction = { friendsPull.distanceFraction },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
            FrostedTopBar(
                title = when {
                    profilePageOpen -> profileTopBarInfo.title.ifBlank { titleOrbProfileText }
                    showAccountIntegrations -> titleAccountText
                    showSources -> titleAudioSourcesText
                    showSettings -> titleSettingsText
                    detail != null -> detail.title
                    else -> tabs[selectedTab].let {
                        if (it.label == tabPlay) titleListenNow else it.label
                    }
                },
                hazeState = hazeState,
                subtitle = if (profilePageOpen) profileTopBarInfo.subtitle else null,
                ownBackdrop = !profilePageOpen && (detail == null || isLocalDetail),
                showBrandingWhenNoBack = !profilePageOpen,
                scrolled = when {
                    profilePageOpen -> profileScrolled
                    showSettings || showSources || showAccountIntegrations -> true
                    detail != null -> detailScrolled
                    else -> scrolled
                },
                refreshing = when {
                    profilePageOpen -> false
                    detail != null -> detailRefreshing
                    selectedTab == TAB_FRIENDS -> friendsRefreshing
                    else -> currentFeed != null && currentFeed in refreshing
                },
                pullFraction = {
                    when {
                        profilePageOpen -> 0f
                        detail != null -> detailPull.distanceFraction
                        selectedTab == TAB_FRIENDS -> friendsPull.distanceFraction
                        else -> currentPull?.distanceFraction ?: 0f
                    }
                },
                onBack = when {
                    profilePageOpen -> null
                    showAccountIntegrations -> ({ showAccountIntegrations = false })
                    showSources -> ({ showSources = false; showSettings = true })
                    showSettings -> ({ showSettings = false })
                    detail != null -> ({ viewModel.closeDetail(); Unit })
                    else -> null
                },
                plainBackButton = detail != null,
                modifier = Modifier.align(Alignment.TopCenter),
                actions = {
                    if (!showSettings && !showSources && !showAccountIntegrations && !profilePageOpen && detail == null && selectedTab == TAB_HOME) {
                        val readyUpdate = automaticUpdateReady
                        if (readyUpdate != null) {
                            IconButton(
                                onClick = { showAutomaticUpdateDialog = true },
                                modifier = Modifier.then(
                                    if (OrbFlavorUi.expressive) {
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                    } else Modifier
                                ),
                            ) {
                                Icon(
                                    Icons.Rounded.SystemUpdate,
                                    contentDescription = stringResource(
                                        R.string.beta_updates_in_app_icon_description,
                                        readyUpdate.displayName,
                                    ),
                                    tint = if (OrbFlavorUi.expressive) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        } else if (automaticUpdateAvailable != null) {
                            val availableUpdate = automaticUpdateAvailable!!
                            IconButton(
                                onClick = { showAutomaticUpdateDialog = true },
                                modifier = Modifier.then(
                                    if (OrbFlavorUi.expressive) {
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                    } else Modifier
                                ),
                            ) {
                                Icon(
                                    Icons.Rounded.SystemUpdate,
                                    contentDescription = stringResource(
                                        R.string.beta_updates_in_app_icon_description,
                                        availableUpdate.displayName,
                                    ),
                                    tint = if (OrbFlavorUi.expressive) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        } else {
                            updateNotice?.let { update ->
                                IconButton(
                                    onClick = { showUpdateDialog = true },
                                    modifier = Modifier.then(
                                        if (OrbFlavorUi.expressive) {
                                            Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                        } else Modifier
                                    ),
                                ) {
                                    Icon(
                                        Icons.Rounded.SystemUpdate,
                                        contentDescription = stringResource(R.string.beta_ui_update_available, update.version),
                                        tint = if (OrbFlavorUi.expressive) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (
                        !OrbFlavorUi.expressive &&
                        !showSettings &&
                        !showSources &&
                        !showAccountIntegrations &&
                        !profilePageOpen &&
                        detail == null &&
                        selectedTab == TAB_HOME
                    ) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = titleSettingsText,
                            )
                        }
                    }
                    if (!showSettings && !showSources && !showAccountIntegrations && !profilePageOpen && detail == null) {
                        TopBarAccountButton(
                            account = displayAccount,
                            onClick = { showAccountIntegrations = true },
                        )
                    }
                },
            )
            }
        }

        BottomFadeBlur(
            hazeState = hazeState,
            withMiniPlayer = player.song != null,
            pageColor = if (isDetailVisible) detailPalette.background else MaterialTheme.colorScheme.background,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            player.song?.let { song ->
                MiniPlayer(
                    song = song,
                    isPlaying = player.isPlaying,
                    isLoading = player.isLoading || false,
                    hazeState = hazeState,
                    onPlayPause = {
                        if (!false) {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        }
                    },
                    onNext = {
                        if (!false) controller?.seekToNextMediaItem()
                    },
                    onExpand = {
                        miniPlayerBounds?.let {
                            NowPlayingLaunchOriginRegistry.record(
                                it,
                                NowPlayingLaunchOriginKind.MINI_PLAYER,
                            )
                        }
                        nowPlayingSong?.let(::prepareNowPlayingLaunch)
                        showNowPlaying = true
                    },
                    onClearQueue = {
                        controller?.clearPlaybackQueue()
                        autoplaySeed = null
                        pendingNowPlayingSong = null
                        pendingPlaybackCommitted = false
                        retainedNowPlayingSong = null
                    },
                    controlsEnabled = !false,
                    onBoundsChanged = { miniPlayerBounds = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FloatingBottomBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                hazeState = hazeState,
                notificationTabIndex = TAB_FRIENDS,
                showNotificationDot = statsNotificationUnread > 0 && selectedTab != TAB_FRIENDS,
                onTabSelected = { index ->
                    if (index != TAB_EXPLORE) {
                        searchFocusTrigger = 0
                    }
                    if (index != TAB_FRIENDS) {
                        if (friendsSearchOpen) friendsViewModel.closeUserSearch()
                        if (statsNotificationsOpen) friendsViewModel.closeNotifications()
                        if (artistRankingOpen) friendsViewModel.closeArtistRanking()
                    }

                    // Bottom navigation is universal: any tab tap dismisses every
                    // pushed page first, including Profile. Do this unconditionally
                    // instead of relying on profilePageOpen from the current frame.
                    showOrbProfile = false
                    publicProfileUserId = null
                    showSettings = false
                    showSources = false
                    showAccountIntegrations = false
                    viewModel.clearDetail()

                    if (index != selectedTab) {
                        tabBackStack = (tabBackStack + selectedTab).takeLast(MAX_TAB_BACK_HISTORY)
                        selectedTab = index
                    }
                },
            )
        }

        if (showNowPlaying) {
            // These are WINDOW states, not track states. They are intentionally
            // remembered before any song-dependent composition so a crossfade
            // handoff cannot replace the sheet's navigation or animation state.
            var nowPlayingLyricsOpen by rememberSaveable { mutableStateOf(false) }
            var nowPlayingQueueOpen by rememberSaveable { mutableStateOf(false) }

            // SheetState belongs to the lifetime of the open Now Playing window.
            // If it is recreated when the current song changes, Material3 starts a
            // fresh Hidden -> Expanded animation and the player looks like it closed
            // and reopened. Keep it above every song/videoId-dependent subtree.
            val nowPlayingSheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { target ->
                    if (
                        target == SheetValue.Hidden &&
                        (nowPlayingLyricsOpen || nowPlayingQueueOpen)
                    ) {
                        when {
                            nowPlayingLyricsOpen -> nowPlayingLyricsOpen = false
                            nowPlayingQueueOpen -> nowPlayingQueueOpen = false
                        }
                        false
                    } else {
                        true
                    }
                },
            )
            var nowPlayingSheetDismissProgress by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(nowPlayingSheetState, appRootSize.height) {
                snapshotFlow {
                    if (appRootSize.height <= 0) 0f else {
                        val offset = runCatching { nowPlayingSheetState.requireOffset() }.getOrDefault(0f)
                        (offset / appRootSize.height.toFloat()).coerceIn(0f, 1f)
                    }
                }.collect { nowPlayingSheetDismissProgress = it }
            }

            val currentNowPlayingSong = nowPlayingSong
            if (currentNowPlayingSong != null) {
                var links by remember { mutableStateOf<Song?>(null) }
                LaunchedEffect(currentNowPlayingSong.videoId, currentNowPlayingSong.artist) {
                    links = null
                    linksLoading = false
                    val current = currentNowPlayingSong
                    val compositeArtistCredit = current.artist.contains(",") ||
                        current.artist.contains(" & ") ||
                        current.artist.contains(" feat", ignoreCase = true) ||
                        current.artist.contains(" featuring", ignoreCase = true) ||
                        current.artist.contains(" ft.", ignoreCase = true) ||
                        current.artist.contains(" ft ", ignoreCase = true) ||
                        current.artist.contains(" with ", ignoreCase = true) ||
                        current.artist.contains(" x ", ignoreCase = true) ||
                        current.artist.contains(";")

                    val needsMetadata = current.albumId == null || current.artistId == null
                    val needsArtistLinks = compositeArtistCredit
                    if (!needsMetadata && !needsArtistLinks) return@LaunchedEffect

                    linksLoading = true
                    val extra = if (needsMetadata || current.artistLinks.size < 2) {
                        YtMusicRepository.trackLinks(current.videoId).getOrNull()
                    } else {
                        null
                    }
                    val mergedSeeds = (current.artistLinks + extra?.artistLinks.orEmpty())
                        .filter { it.artistId.isNotBlank() && it.name.isNotBlank() }
                        .distinctBy { it.artistId }
                    val resolvedArtists = if (needsArtistLinks) {
                        YtMusicRepository.resolveArtistLinks(current.artist, mergedSeeds)
                    } else {
                        mergedSeeds
                    }
                    links = current.copy(
                        artistId = current.artistId ?: extra?.artistId ?: resolvedArtists.firstOrNull()?.artistId,
                        artistLinks = resolvedArtists,
                        albumId = current.albumId ?: extra?.albumId,
                        albumName = current.albumName ?: extra?.albumName,
                        releaseYear = current.releaseYear ?: extra?.releaseYear,
                    )
                    linksLoading = false
                }
                val song = links
                    ?.takeIf { it.videoId == currentNowPlayingSong.videoId }
                    ?: currentNowPlayingSong
                LaunchedEffect(song) {
                    if (songActions?.videoId == song.videoId) songActions = song
                }

                ModalBottomSheet(
                    onDismissRequest = {
                        // Internal Now Playing modules own the first dismissal.
                        when {
                            nowPlayingLyricsOpen -> nowPlayingLyricsOpen = false
                            nowPlayingQueueOpen -> nowPlayingQueueOpen = false
                            else -> showNowPlaying = false
                        }
                    },
                    sheetState = nowPlayingSheetState,
                    // Back is owned by the BackHandler INSIDE NowPlayingScreen.
                    // The sheet itself must stay out of system-Back dispatch; otherwise
                    // it can race the internal lyrics/queue stack or the album behind it.
                    properties = ModalBottomSheetProperties(
                        shouldDismissOnBackPress = false,
                    ),
                    shape = RectangleShape,
                    containerColor = Color.Transparent,
                    // During a source-container transform the root artwork must
                    // remain visible through the sheet window. Once the opening
                    // completes, restore the normal modal dim.
                    scrimColor = if (nowPlayingLaunchVisual != null) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
                    },
                    dragHandle = null,
                    // Material 3 caps modal sheets on large screens by default.
                    // Now Playing owns its own responsive tablet layout, so the
                    // sheet itself must span the full window; otherwise the
                    // landscape controls pane is laid out beyond the sheet and
                    // portrait tablets show uncovered side gutters.
                    sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) {
                    val visualFromSong = automixVisualOutgoingSong
                        ?.takeIf { from ->
                            val visual = automixVisualTransition
                            visual != null &&
                                song.videoId == visual.incomingMediaId &&
                                from.videoId == visual.outgoingMediaId &&
                                automixVisualProgress < 0.999f
                        }
                    val visuallyOutgoing = automixVisualTransition
                        ?.takeIf { automixVisualActive && automixVisualProgress < automixSliderEndProgress }
                    val nowPlayingContentAlpha by animateFloatAsState(
                        targetValue = if (nowPlayingLaunchContentVisible) 1f else 0f,
                        animationSpec = tween(170, easing = FastOutSlowInEasing),
                        label = "now-playing-source-content",
                    )

                    Box(Modifier.fillMaxSize()) {
                        val collapseProgress = nowPlayingSheetDismissProgress.coerceIn(0f, 1f)
                        val collapseEased = collapseProgress * collapseProgress * (3f - 2f * collapseProgress)
                        val miniWidthRatio = miniPlayerBounds
                            ?.let { (it.width / appRootSize.width.coerceAtLeast(1).toFloat()).coerceIn(0.82f, 0.98f) }
                            ?: 0.92f
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val dragScale = 1f - (1f - miniWidthRatio) * collapseEased
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                    val tailFade = ((collapseProgress - 0.68f) / 0.32f).coerceIn(0f, 1f)
                                    alpha = nowPlayingContentAlpha * (1f - tailFade)
                                    shape = RoundedCornerShape(28.dp * collapseEased)
                                    clip = collapseEased > 0.01f
                                },
                        ) {
                            NowPlayingScreen(
                                albumSequential = player.albumSequential,
                        song = song,
                        transitionFromSong = visualFromSong,
                        trackTransitionProgress = automixVisualProgress,
                        isPlaying = player.isPlaying && pendingNowPlayingSong == null,
                        isLoading = player.isLoading || pendingNowPlayingSong != null || false,
                        // While the selected song is still being resolved, never
                        // leak the previous song's progress/transport into the new
                        // Now Playing surface.
                        positionMs = when {
                            pendingNowPlayingSong != null -> 0L
                            visuallyOutgoing != null -> visuallyOutgoing.outgoingPositionMs
                            else -> player.positionMs
                        },
                        durationMs = when {
                            pendingNowPlayingSong != null -> 0L
                            visuallyOutgoing != null && visuallyOutgoing.outgoingDurationMs > 0L ->
                                visuallyOutgoing.outgoingDurationMs
                            else -> player.durationMs
                        },
                        onPlayPause = {
                            if (pendingNowPlayingSong == null && !false) {
                                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            }
                        },
                        onNext = {
                            if (pendingNowPlayingSong == null && !false) {
                                controller?.seekToNextMediaItem()
                            }
                        },
                        onPrevious = {
                            if (pendingNowPlayingSong == null && !false) {
                                controller?.seekToPrevious()
                            }
                        },
                        onSeekFraction = { fraction ->
                            if (pendingNowPlayingSong == null) controller?.let { player ->
                                val duration = player.duration
                                if (duration > 0) {
                                    player.seekTo(
                                        (fraction * duration).toLong()
                                            .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                                    )
                                }
                            }
                        },
                        onSeek = { target ->
                            if (pendingNowPlayingSong == null) controller?.let { player ->
                                val duration = player.duration
                                player.seekTo(
                                    if (duration > 0) {
                                        target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                                    } else {
                                        target.coerceAtLeast(0L)
                                    },
                                )
                            }
                        },
                        queue = player.queue,
                        queueIndex = player.queueIndex,
                        hasPrevious = player.hasPrevious,
                        hasNext = player.hasNext,
                        repeatMode = player.repeatMode,
                        shuffleEnabled = shuffleEnabled,
                        autoplayEnabled = autoplay,
                        signedIn = signedIn,
                        likeStatus = viewModel.likeStatusOf(song, likeStatuses),
                        onToggleLike = {
                            OrbHaptics.perform(context, OrbHaptics.Kind.LIKE)
                            viewModel.toggleLike(song)
                        },
                        onToggleShuffle = { controller?.let(QueueShuffle::toggle) },
                        onCycleRepeat = {
                            controller?.let {
                                val next = when (it.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                if (next == Player.REPEAT_MODE_ALL) it.dropAutoplayTracks()
                                it.repeatMode = next
                            }
                        },
                        onToggleAutoplay = {
                            val on = !autoplay
                            AppSettings.setAutoplay(on)
                            if (on) {
                                autoplaySeed = null
                            } else {
                                controller?.dropAutoplayTracks()
                            }
                        },
                        onJumpTo = { controller?.seekToDefaultPosition(it) },
                        onRemoveFromQueue = { controller?.removeMediaItem(it) },
                        onMoveInQueue = { from, to ->
                            controller?.let { c ->
                                c.moveMediaItemByUser(from, to)
                            }
                        },
                        onOpenMenu = { songActions = song },
                        onOpenAlbum = { id ->
                            showNowPlaying = false
                            viewModel.openDetail(
                                id,
                                song.albumName ?: song.title,
                                song.artist,
                                song.thumbnailUrl,
                                BrowseType.ALBUM,
                                song.isExplicit,
                            )
                        },
                        onOpenArtist = { id, name ->
                            if (!id.isNullOrBlank()) {
                                showNowPlaying = false
                                viewModel.openDetail(id, name, "Artist", null, BrowseType.ARTIST)
                            } else {
                                // Some watch/queue renderers expose only the
                                // primary artist. Keep every visible collaborator
                                // clickable and resolve a missing endpoint only
                                // when that specific name is tapped.
                                scope.launch {
                                    // Guard ambiguous duo/band names first:
                                    // if the whole visible credit is itself a
                                    // canonical artist (e.g. Mumford & Sons),
                                    // open that page. Otherwise resolve only
                                    // the exact collaborator that was tapped.
                                    val resolved =
                                        YtMusicRepository.resolveArtistLink(song.artist)
                                            ?: YtMusicRepository.resolveArtistLink(name)
                                    if (resolved != null) {
                                        showNowPlaying = false
                                        viewModel.openDetail(
                                            resolved.artistId,
                                            resolved.name,
                                            "Artist",
                                            null,
                                            BrowseType.ARTIST,
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Não foi possível abrir $name",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        lyrics = lyrics,
                        lyricsSource = lyricsSource,
                        lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
                        lyricsOpen = nowPlayingLyricsOpen,
                        onLyricsOpenChange = { open ->
                            nowPlayingLyricsOpen = open
                            if (open) nowPlayingQueueOpen = false
                        },
                        queueOpen = nowPlayingQueueOpen,
                        onQueueOpenChange = { open ->
                            nowPlayingQueueOpen = open
                            if (open) nowPlayingLyricsOpen = false
                        },
                        onBackFromNowPlaying = {
                            // Do not touch `detail`: it is the album/artist page that
                            // must still be there after Now Playing is removed.
                            showNowPlaying = false
                        },
                        transportEnabled = !false,
                        onClearQueue = {
                            controller?.clearQueueKeepingCurrent()
                            autoplaySeed = null
                        },
                    )
                        }
                        // ModalBottomSheet owns a separate window layer. Keep the
                        // notice host inside that layer while Now Playing is open so
                        // action feedback can drop over the player instead of behind it.
                        OrbNoticeHost(Modifier.align(Alignment.TopCenter))
                    }
                }

            }
        }

        songActions?.let { song ->
            val fromPlayer = showNowPlaying
            val share: () -> Unit = {
                OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                songActions = null
                scope.launch {
                    runCatching { SongShareStoryRenderer.share(context, song) }
                        .onFailure { error ->
                            OrbNoticeCenter.post(
                                OrbNotice(
                                    title = context.getString(R.string.song_action_share),
                                    message = error.message ?: context.getString(R.string.song_share_failed),
                                    icon = OrbNoticeIcon.INFO,
                                ),
                            )
                        }
                }
            }
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                songActions = null
                showNowPlaying = false
                val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
                viewModel.openDetail(
                    id,
                    title,
                    sub,
                    art,
                    type,
                    isExplicit = type == BrowseType.ALBUM && song.isExplicit,
                )
            }
            LaunchedEffect(song.videoId) { viewModel.loadSongMenu(song.videoId) }
            val editable = viewModel.editablePlaylist(detail?.browseId)
                ?.takeIf { !fromPlayer && song.setVideoId != null }
            ModalBottomSheet(
                onDismissRequest = { songActions = null },
                containerColor = Color.Transparent,
                scrimColor = Color.Black.copy(alpha = 0.40f),
                dragHandle = null,
            ) {
                if (fromPlayer) {
                    PlayerActionSheetSystemBars(hideStatusBarNowPlaying)
                }
                SongActionsSheet(
                    song = song,
                    signedIn = signedIn,
                    paletteOverride = detailPalette.takeIf {
                        !fromPlayer && isDetailVisible
                    },
                    usePlayerPalette = fromPlayer,
                    likeStatus = viewModel.likeStatusOf(song, likeStatuses),
                    inLibrary = songMenu?.inLibrary == true,
                    libraryActionAvailable = songMenu != null,
                    onToggleLibrary = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                        viewModel.toggleSongLibrary(song)
                        songActions = null
                    },
                    onPlayNext = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                        playNext(song)
                        songActions = null
                    },
                    onAddToQueue = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                        addToQueue(song)
                        songActions = null
                    },
                    onDownload = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                        startDownload(listOf(song))
                        songActions = null
                    },
                    onToggleLike = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.LIKE)
                        viewModel.toggleLike(song)
                    },
                    onToggleDislike = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                        viewModel.toggleDislike(song)
                    },
                    onAddToPlaylist = {
                        OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                        songActions = null
                        viewModel.loadPlaylists()
                        playlistTarget = song
                    },
                    onRemoveFromPlaylist = editable?.let {
                        {
                            songActions = null
                            viewModel.removeFromPlaylist(it.browseId, song)
                        }
                    },
                    onOpenAlbum = { id ->
                        openPage(
                            id,
                            song.albumName ?: song.title,
                            song.artist,
                            BrowseType.ALBUM,
                        )
                    },
                    onOpenArtist = { id ->
                        openPage(id, song.artist, "Artist", BrowseType.ARTIST)
                    },
                    allowOfflineDownloadRemoval = !fromPlayer && detail?.browseId == "local:downloads",
                    onDownloadRemoved = if (!fromPlayer && detail?.browseId == "local:downloads") {
                        {
                            viewModel.reloadLocalDetail("local:downloads")
                            songActions = null
                        }
                    } else {
                        null
                    },
                    resolvingLinks = fromPlayer && linksLoading,
                    showSleepTimer = fromPlayer,
                    onShare = share,
                    onCopyLog = if (fromPlayer && BuildConfig.IS_BETA) {
                        {
                            songActions = null
                            scope.launch {
                                val text = TrackLog.forTrack(song, NerdStats.current.value)
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(
                                    context,
                                    "Log copied · ${text.lineSequence().count()} lines",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }

        if (playlistTarget != null || creatingPlaylist) {
            val target = playlistTarget
            val dismiss = {
                playlistTarget = null
                creatingPlaylist = false
            }
            ModalBottomSheet(
                onDismissRequest = dismiss,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                PlaylistPickerSheet(
                    playlists = playlists,
                    loading = playlistsLoading,
                    song = target,
                    startCreating = target == null,
                    onPick = { playlist ->
                        target?.let { viewModel.addToPlaylist(playlist, it) }
                        dismiss()
                    },
                    onCreate = { title, privacy ->
                        viewModel.createPlaylist(title, privacy, target)
                        dismiss()
                    },
                )
            }
        }

        playlistActions?.let { playlist ->
            ModalBottomSheet(
                onDismissRequest = { playlistActions = null },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                PlaylistActionsSheet(
                    playlist = playlist,
                    onOpen = {
                        playlistActions = null
                        viewModel.openDetail(
                            browseId = playlist.browseId,
                            title = playlist.title,
                            subtitle = playlist.subtitle,
                            thumbnailUrl = playlist.thumbnailUrl,
                            type = BrowseType.PLAYLIST,
                        )
                    },
                    onRename = { name ->
                        playlistActions = null
                        viewModel.renamePlaylist(playlist, name)
                    },
                    onDelete = {
                        playlistActions = null
                        viewModel.deletePlaylist(playlist)
                    },
                )
            }
        }

        if (
            signedIn &&
            !showLogin &&
            youtubeIdentityPicker == null &&
            firstAccountOnboardingStep == "youtube"
        ) {
            YouTubeMusicFirstAccountDialog(
                connecting = firstAccountYoutubeConnecting,
                onConnect = {
                    val activity = context as? MainActivity
                    val userId = SocialRepository.currentUserId()
                    if (activity == null || userId == null || firstAccountYoutubeConnecting) {
                        return@YouTubeMusicFirstAccountDialog
                    }

                    FirstAccountOnboardingStore.markYoutubeHandled(context, userId)
                    firstAccountYoutubeConnecting = true
                    firstAccountOnboardingStep = null
                    activity.connectYoutubeMusic { result ->
                        firstAccountYoutubeConnecting = false
                        result.onSuccess {
                            viewModel.onYoutubeIdentityChanged()
                            advanceFirstAccountOnboardingToUsername()
                        }.onFailure {
                            if (OrbGoogleAuth.youtubeCompatibilityRequired.value) {
                                // Keep step 2 queued behind the compatibility
                                // browser so username creation follows it.
                                firstAccountOnboardingStep = "username"
                                showLogin = true
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.first_account_youtube_error),
                                    Toast.LENGTH_LONG,
                                ).show()
                                advanceFirstAccountOnboardingToUsername()
                            }
                        }
                    }
                },
                onLater = {
                    SocialRepository.currentUserId()?.let { userId ->
                        FirstAccountOnboardingStore.markYoutubeHandled(context, userId)
                    }
                    advanceFirstAccountOnboardingToUsername()
                },
            )
        }

        if (
            signedIn &&
            !showLogin &&
            youtubeIdentityPicker == null &&
            firstAccountOnboardingStep == "username"
        ) {
            UsernameFirstAccountDialog(
                username = firstAccountUsername,
                saving = firstAccountUsernameSaving,
                errorMessage = firstAccountUsernameError,
                onUsernameChange = { raw ->
                    firstAccountUsername = raw
                        .trimStart()
                        .removePrefix("@")
                        .lowercase(Locale.ROOT)
                        .filter { it.isLetterOrDigit() || it == '.' || it == '_' }
                        .take(30)
                    firstAccountUsernameError = null
                },
                onCreate = {
                    if (firstAccountUsernameSaving) {
                        return@UsernameFirstAccountDialog
                    }
                    val username = firstAccountUsername.trim()
                    val valid = Regex("^[a-z0-9._]{3,30}$").matches(username)
                    if (!valid) {
                        firstAccountUsernameError =
                            context.getString(R.string.profile_username_error)
                    } else {
                        firstAccountUsernameSaving = true
                        firstAccountUsernameError = null
                        firstAccountOnboardingScope.launch {
                            SocialRepository.setUsername(username)
                                .onSuccess {
                                    SocialRepository.currentUserId()?.let { userId ->
                                        FirstAccountOnboardingStore.markUsernameHandled(
                                            context,
                                            userId,
                                        )
                                    }
                                    runCatching { SocialRepository.myProfile() }
                                    finishFirstAccountOnboarding()
                                }
                                .onFailure {
                                    firstAccountUsernameError =
                                        context.getString(R.string.first_account_username_error)
                                }
                            firstAccountUsernameSaving = false
                        }
                    }
                },
                onLater = {
                    SocialRepository.currentUserId()?.let { userId ->
                        FirstAccountOnboardingStore.markUsernameHandled(context, userId)
                    }
                    finishFirstAccountOnboarding()
                },
            )
        }

        if (showLogin) {
            BackHandler { showLogin = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { showLogin = false },
                            modifier = Modifier.then(
                                if (OrbFlavorUi.expressive) {
                                    Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                } else Modifier
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            text = stringResource(R.string.youtube_music_compatibility_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        text = stringResource(R.string.youtube_music_compatibility_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    YtMusicLoginScreen(
                        onSessionCaptured = { session ->
                            viewModel.onLegacyYoutubeLinked(session) { result ->
                                result.onSuccess {
                                    showLogin = false
                                    scope.launch {
                                        val identities = OrbGoogleAuth.availableYoutubeIdentities()
                                        if (identities.size > 1) youtubeIdentityPicker = identities
                                    }
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "This YouTube Music session could not be linked.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    )
                }
            }
        }

        youtubeIdentityPicker?.let { identities ->
            YouTubeIdentityPickerDialog(
                identities = identities,
                currentIdentityKey = identities.firstOrNull { it.isSelected }?.stableKey,
                onDismiss = { youtubeIdentityPicker = null },
                onSelect = { identity ->
                    if (!youtubeIdentitySwitching) {
                        youtubeIdentitySwitching = true
                        scope.launch {
                            runCatching { OrbGoogleAuth.selectYoutubeIdentity(identity) }
                                .onSuccess {
                                    youtubeIdentityPicker = null
                                    viewModel.onYoutubeIdentityChanged()
                                }
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "Could not switch YouTube channel.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            youtubeIdentitySwitching = false
                        }
                    }
                },
                onReconnect = {
                    youtubeIdentityPicker = null
                    OrbGoogleAuth.requestYoutubeChannelSwitch()
                    showLogin = true
                },
            )
        }

        val currentUpdateDownload = activeUpdateDownload
        if (currentUpdateDownload != null) {
            val active = currentUpdateDownload
            UpdateAvailableDialog(
                version = active.release.tagName.removePrefix("v"),
                hazeState = hazeState,
                mode = SoftwareUpdateDialogMode.DOWNLOADING,
                progress = active.progress,
                releaseNotes = active.release.releaseNotes,
                heroImageUrl = active.release.heroImageUrl,
                onDismiss = { },
                onUpdate = { },
                secondaryActionLabel = stringResource(R.string.software_update_cancel),
                onSecondaryAction = { UpdateDownloadStore.requestCancel(active.release.tagName) },
            )
        } else if (showAutomaticUpdateDialog) {
            val readyUpdate = automaticUpdateReady
            val availableUpdate = automaticUpdateAvailable
            when {
                readyUpdate != null -> UpdateAvailableDialog(
                    version = readyUpdate.tagName.removePrefix("v"),
                    hazeState = hazeState,
                    mode = SoftwareUpdateDialogMode.READY,
                    releaseNotes = readyUpdate.releaseNotes,
                    heroImageUrl = readyUpdate.heroImageUrl,
                    primaryActionLabel = stringResource(R.string.beta_updates_install_now),
                    onDismiss = { showAutomaticUpdateDialog = false },
                    onUpdate = {
                        UpdateReadyStore.onInstallRequested(context)
                        context.startActivity(UpdateReadyStore.installIntent(context, readyUpdate))
                        showAutomaticUpdateDialog = false
                    },
                )
                availableUpdate != null -> UpdateAvailableDialog(
                    version = availableUpdate.tagName.removePrefix("v"),
                    hazeState = hazeState,
                    mode = SoftwareUpdateDialogMode.AVAILABLE,
                    releaseNotes = availableUpdate.releaseNotes,
                    heroImageUrl = availableUpdate.heroImageUrl,
                    onDismiss = { showAutomaticUpdateDialog = false },
                    onUpdate = {
                        scope.launch {
                            when (val result = ManualUpdateChecker.download(context, availableUpdate)) {
                                is ManualUpdateResult.Failed -> Toast.makeText(
                                    context,
                                    result.reason ?: context.getString(R.string.update_manual_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                                else -> Unit
                            }
                        }
                    },
                )
            }
        } else if (showUpdateDialog) {
            updateNotice?.let { update ->
                UpdateAvailableDialog(
                    version = update.version,
                    hazeState = hazeState,
                    mode = SoftwareUpdateDialogMode.AVAILABLE,
                    releaseNotes = update.release.releaseNotes,
                    heroImageUrl = update.release.heroImageUrl,
                    onDismiss = { showUpdateDialog = false },
                    onUpdate = {
                        scope.launch {
                            when (val result = ManualUpdateChecker.download(context, update.release)) {
                                is ManualUpdateResult.Ready -> showUpdateDialog = false
                                is ManualUpdateResult.Failed -> {
                                    Toast.makeText(
                                        context,
                                        result.reason ?: context.getString(R.string.update_manual_failed),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                else -> Unit
                            }
                        }
                    },
                )
            }
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (showListenBrainzLogin) {
            var tokenInput by remember { mutableStateOf(listenBrainzToken) }
            ListenBrainzTokenAlert(
                hazeState = hazeState,
                tokenInput = tokenInput,
                onTokenInputChange = { tokenInput = it },
                onSave = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzLogin = false
                },
                onDismiss = { showListenBrainzLogin = false },
            )
        }

        if (showLastfmLogin) {
            var usernameInput by remember { mutableStateOf("") }
            var passwordInput by remember { mutableStateOf("") }
            var lastfmError by remember { mutableStateOf<String?>(null) }
            var lastfmLoading by remember { mutableStateOf(false) }
            LastfmLoginAlert(
                hazeState = hazeState,
                usernameInput = usernameInput,
                onUsernameInputChange = { usernameInput = it },
                passwordInput = passwordInput,
                onPasswordInputChange = { passwordInput = it },
                error = lastfmError,
                loading = lastfmLoading,
                onSignIn = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            LastFM.initialize(
                                apiKey = LastFM.FALLBACK_COMPAT_API_KEY,
                                secret = LastFM.FALLBACK_COMPAT_SECRET,
                            )
                            LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                .onSuccess { auth ->
                                    AppSettings.setLastfmSessionKey(auth.session.key)
                                    AppSettings.setLastfmUsername(auth.session.name)
                                    AppSettings.setLastfmEnabled(true)
                                    showLastfmLogin = false
                                }
                                .onFailure { e ->
                                    lastfmError = e.message ?: "Login failed"
                                }
                        } catch (e: Exception) {
                            lastfmError = e.message ?: "Login failed"
                        } finally {
                            lastfmLoading = false
                        }
                    }
                },
                onDismiss = { if (!lastfmLoading) showLastfmLogin = false },
            )
        }

        nowPlayingLaunchVisual?.let { visual ->
            NowPlayingLaunchOverlay(
                visual = visual,
                rootSize = appRootSize,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(80f),
            )
        }

        if (!showNowPlaying) {
            OrbNoticeHost(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun NowPlayingLaunchOverlay(
    visual: NowPlayingLaunchVisual,
    rootSize: IntSize,
    modifier: Modifier = Modifier,
) {
    if (rootSize.width <= 0 || rootSize.height <= 0) return

    val progress = remember(visual.token) { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(visual.token) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(430, easing = FastOutSlowInEasing),
        )
    }

    val p = progress.value.coerceIn(0f, 1f)
    val artworkFade = if (p < 0.84f) 1f else (1f - (p - 0.84f) / 0.16f).coerceIn(0f, 1f)
    // The backdrop stays established until the real player has completed its
    // 170 ms fade-in (started at 180 ms), then hands off to it. Previously the
    // backdrop faded before the player was ready, briefly exposing Home; keeping
    // it opaque forever would merely move the blink to the final overlay removal.
    val backdropIn = (p / 0.58f).coerceIn(0f, 1f)
    val backdropOut = if (p < 0.82f) 1f else (1f - (p - 0.82f) / 0.18f).coerceIn(0f, 1f)
    val backdropAlpha = backdropIn * backdropOut * 0.98f
    val rootWidthPx = rootSize.width.toFloat()
    val rootHeightPx = rootSize.height.toFloat()

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = backdropAlpha)),
    ) {
        if (visual.originKind == NowPlayingLaunchOriginKind.MINI_PLAYER) {
            val origin = visual.origin
            val currentLeft = origin.left * (1f - p)
            val currentTop = origin.top * (1f - p)
            val currentWidth = origin.width + (rootWidthPx - origin.width) * p
            val currentHeight = origin.height + (rootHeightPx - origin.height) * p
            val currentCenterX = currentLeft + currentWidth / 2f
            val currentCenterY = currentTop + currentHeight / 2f
            val rootCenterX = rootWidthPx / 2f
            val rootCenterY = rootHeightPx / 2f
            val corner = 26.dp * (1f - p)

            // The MiniPlayer is a container transition, not an artwork-only
            // transition: its complete frosted pill expands into the player.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = currentWidth / rootWidthPx.coerceAtLeast(1f)
                        scaleY = currentHeight / rootHeightPx.coerceAtLeast(1f)
                        translationX = currentCenterX - rootCenterX
                        translationY = currentCenterY - rootCenterY
                        shape = RoundedCornerShape(corner)
                        clip = true
                        alpha = artworkFade
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )

            val expressiveMini = OrbFlavorUi.expressive
            val insetPx = with(density) { (if (expressiveMini) 8.dp else 5.dp).toPx() }
            val sourceArtSize = with(density) { (if (expressiveMini) 50.dp else 40.dp).toPx() }
            val sourceArt = Rect(
                left = origin.left + insetPx,
                top = origin.top + insetPx,
                right = origin.left + insetPx + sourceArtSize,
                bottom = origin.top + insetPx + sourceArtSize,
            )
            val destinationArt = Rect(0f, 0f, rootWidthPx, rootWidthPx)
            val artLeft = sourceArt.left + (destinationArt.left - sourceArt.left) * p
            val artTop = sourceArt.top + (destinationArt.top - sourceArt.top) * p
            val artWidth = sourceArt.width + (destinationArt.width - sourceArt.width) * p
            val artHeight = sourceArt.height + (destinationArt.height - sourceArt.height) * p
            val artCenterX = artLeft + artWidth / 2f
            val artCenterY = artTop + artHeight / 2f
            val destinationWidthDp = with(density) { rootWidthPx.toDp() }
            val destinationHeightDp = with(density) { rootWidthPx.toDp() }
            val artCorner = (if (expressiveMini) 17.dp else 7.dp) * (1f - p)

            AsyncImage(
                model = visual.song.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(destinationWidthDp, destinationHeightDp)
                    .graphicsLayer {
                        scaleX = artWidth / rootWidthPx.coerceAtLeast(1f)
                        scaleY = artHeight / rootWidthPx.coerceAtLeast(1f)
                        translationX = artCenterX - rootCenterX
                        translationY = artCenterY - rootWidthPx / 2f
                        alpha = artworkFade
                        shape = RoundedCornerShape(artCorner)
                        clip = true
                    },
            )

            // Carry the MiniPlayer's textual identity with the container for
            // the first half of the transform. The old artwork-only motion made
            // the rest of the pill vanish in place while the cover flew upward.
            val sourceTextX = origin.left + insetPx + sourceArtSize + with(density) { 12.dp.toPx() }
            val sourceTitleY = origin.top + with(density) { 12.dp.toPx() }
            val sourceArtistY = sourceTitleY + with(density) { 22.dp.toPx() }
            val destinationTextX = with(density) { 28.dp.toPx() }
            val destinationTitleY = rootWidthPx + with(density) { 28.dp.toPx() }
            val destinationArtistY = destinationTitleY + with(density) { 27.dp.toPx() }
            val textTravel = (p / 0.72f).coerceIn(0f, 1f)
            val textFade = if (p < 0.58f) 1f else (1f - (p - 0.58f) / 0.24f).coerceIn(0f, 1f)
            val textX = sourceTextX + (destinationTextX - sourceTextX) * textTravel
            val titleY = sourceTitleY + (destinationTitleY - sourceTitleY) * textTravel
            val artistY = sourceArtistY + (destinationArtistY - sourceArtistY) * textTravel
            Text(
                text = visual.song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    translationX = textX
                    translationY = titleY
                    alpha = textFade
                    val scale = 1f + 0.10f * textTravel
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            )
            Text(
                text = visual.song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    translationX = textX
                    translationY = artistY
                    alpha = textFade
                    val scale = 1f + 0.06f * textTravel
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            )
        } else {
            val destinationWidthPx = rootWidthPx
            val destinationHeightPx = destinationWidthPx
            val origin = visual.origin
            val currentWidth = origin.width + (destinationWidthPx - origin.width) * p
            val currentHeight = origin.height + (destinationHeightPx - origin.height) * p
            val originCenterX = origin.left + origin.width / 2f
            val originCenterY = origin.top + origin.height / 2f
            val destinationCenterX = destinationWidthPx / 2f
            val destinationCenterY = destinationHeightPx / 2f
            val currentCenterX = originCenterX + (destinationCenterX - originCenterX) * p
            val currentCenterY = originCenterY + (destinationCenterY - originCenterY) * p
            val destinationWidthDp = with(density) { destinationWidthPx.toDp() }
            val destinationHeightDp = with(density) { destinationHeightPx.toDp() }
            val corner = 14.dp * (1f - p)
            val shadowPx = with(density) { (18.dp * (1f - p)).toPx() }

            AsyncImage(
                model = visual.song.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(destinationWidthDp, destinationHeightDp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        scaleX = currentWidth / destinationWidthPx.coerceAtLeast(1f)
                        scaleY = currentHeight / destinationHeightPx.coerceAtLeast(1f)
                        translationX = currentCenterX - destinationCenterX
                        translationY = currentCenterY - destinationCenterY
                        alpha = artworkFade
                        shadowElevation = shadowPx
                        shape = RoundedCornerShape(corner)
                        clip = true
                    },
            )
        }
    }
}

@Composable
private fun YouTubeIdentityPickerDialog(
    identities: List<YouTubeAccountIdentity>,
    currentIdentityKey: String?,
    onDismiss: () -> Unit,
    onSelect: (YouTubeAccountIdentity) -> Unit,
    onReconnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_channel_picker_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.youtube_channel_picker_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                identities.forEach { identity ->
                    val selected = identity.isSelected || identity.stableKey == currentIdentityKey
                    TextButton(
                        onClick = { onSelect(identity) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(
                                text = identity.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val detail = buildList {
                                identity.handle.takeIf { it.isNotBlank() }?.let(::add)
                                if (selected) add(stringResource(R.string.youtube_channel_picker_current))
                            }.joinToString(" · ")
                            if (detail.isNotBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.youtube_channel_picker_cancel))
            }
        },
        dismissButton = {
            TextButton(onClick = onReconnect) {
                Text(stringResource(R.string.youtube_channel_picker_reconnect))
            }
        },
    )
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

private const val RADIO_BATCH = 3

/** Maximum time first playback waits for a preferred Lossless stream before normal fallback. */

/** Keep this many queued tracks after the playhead before AutoPlay is refilled. */
private const val AUTOPLAY_PREFETCH_REMAINING = 2

/** AutoPlay grows in small musical batches so Automix can curate each A -> B -> C chain. */
private const val AUTOPLAY_REFILL_BATCH = 3

/** Avoid immediate/recent repeats without turning a long session into an ever-growing blacklist. */
private const val AUTOPLAY_RECENT_DEDUPE_WINDOW = 36

/** If the immediate radio neighbourhood is exhausted, walk a few recent seeds and keep going. */
private const val AUTOPLAY_SEED_FALLBACKS = 4

private const val SEEK_END_GUARD_MS = 1_000L
private const val ALBUM_VERSION_FIRST_NOTE_BUDGET_MS = 850L
private const val ALBUM_VERSION_BACKGROUND_WARM_DELAY_MS = 7_000L
private const val RECENTLY_PLAYED_REFRESH_MS = 4L * 60L * 1_000L

private suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

private val DETAIL_TITLE_DROP = 320.dp

private const val TAB_HOME = 0
private const val TAB_EXPLORE = 1
private const val TAB_LIBRARY = 2
private const val TAB_FRIENDS = 3
private const val MAX_TAB_BACK_HISTORY = 16
