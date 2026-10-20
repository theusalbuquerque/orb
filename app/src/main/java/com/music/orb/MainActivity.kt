@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb
import com.music.orb.ui.lockscreen.OrbLockscreenLayer

import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.MINI_ARTWORK_CORNER_PERCENT
import com.music.orb.ui.components.PlayerTransportVisual
import com.music.orb.ui.components.PlayerIdentityAppearance
import com.music.orb.ui.components.ExplicitTitle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.KeyguardManager
import android.os.PowerManager
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.SystemUpdate
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.music.orb.data.settings.AccountSettingsSync
import com.music.orb.data.settings.HomeSuggestionStore
import com.music.orb.data.settings.RecentPlaybackStore
import com.music.orb.data.settings.ArtistPreference
import com.music.orb.data.settings.ArtistPreferenceStore
import com.music.orb.data.settings.FirstAccountOnboardingStore
import com.music.orb.data.settings.ThemeMode
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
import com.music.orb.ui.screens.OrbLockSettingsScreen
import com.music.orb.ui.screens.SourcesScreen
import com.music.orb.playback.AutoplaySession
import com.music.orb.playback.QueueShuffle
import com.music.orb.playback.autoplaySectionStart
import com.music.orb.playback.albumSequential
import com.music.orb.playback.clearQueueKeepingCurrent
import com.music.orb.playback.clearPlaybackQueue
import com.music.orb.playback.dropAutoplayTracks
import com.music.orb.playback.fromAutoplay
import com.music.orb.playback.playSongs
import com.music.orb.playback.moveMediaItemByUser
import com.music.orb.playback.toMediaItem
import com.music.orb.playback.toSong
import com.music.orb.download.DownloadStore
import com.music.orb.download.Downloads
import com.music.orb.download.OfflineDownloadQuality
import com.music.orb.handoff.OrbTouchTransfer
import com.music.orb.handoff.TouchPlaybackSnapshot
import com.music.orb.handoff.TouchTransferPayload
import com.music.orb.handoff.toTouchSong
import com.music.orb.ui.components.PlaylistActionsSheet
import com.music.orb.ui.components.OrbRefreshDroplet
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
import com.music.orb.ui.components.LocalOrbNavigationGlassHazeState
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
import com.music.orb.ui.notifications.OrbNotice
import com.music.orb.ui.notifications.OrbNoticeCenter
import com.music.orb.ui.notifications.OrbNoticeIcon
import com.music.orb.ui.icons.OrbIcons
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.orb.BuildConfig
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.social.SocialRepository
import com.music.orb.ui.player.NowPlayingArguments
import com.music.orb.ui.player.NowPlayingScreen
import com.music.orb.ui.player.NowPlayingLaunchOriginRegistry
import com.music.orb.ui.player.NowPlayingLaunchOriginKind
import com.music.orb.ui.screens.DetailScreen
import com.music.orb.ui.screens.LocalMusicScreen
import com.music.orb.ui.screens.HomeScreen
import com.music.orb.ui.screens.HOME_RECOMMENDATIONS_SHELF_TITLE
import com.music.orb.ui.screens.HOME_TOP_ARTISTS_SHELF_TITLE
import com.music.orb.ui.screens.HOME_FAVORITE_ALBUM_RELEASES_SHELF_TITLE
import com.music.orb.ui.screens.HOME_SIMILAR_TO_SUBTITLE
import com.music.orb.ui.screens.LibraryScreen
import com.music.orb.ui.screens.SearchScreen
import com.music.orb.ui.screens.PlaybackHistorySheet
import com.music.orb.ui.screens.FriendsScreen
import com.music.orb.ui.screens.WelcomeLoginScreen
import com.music.orb.ui.screens.OrbProfileScreen
import com.music.orb.ui.screens.PublicProfileScreen
import com.music.orb.ui.screens.ProfileTopBarInfo
import com.music.orb.ui.theme.OrbTheme
import com.music.orb.ui.theme.rememberArtworkPalette
import com.music.orb.ui.theme.SystemBarIcons
import com.music.orb.ui.flavor.OrbFlavorUi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

private data class AppContentTarget(
    val kind: AppContentKind,
    val tab: Int,
    val homeSection: Int,
    val detailDepth: Int,
    val detail: DetailPage? = null,
    val profileId: String? = null,
    val statsOverview: Boolean = false,
) {
    val key: String
        get() = when (kind) {
            // Home's section selector is part of one tab, not a new app-level
            // destination. Keeping the same AnimatedContent key prevents the
            // whole page (including the Haze header) from being torn down and
            // cross-faded every time Para você/Álbuns/Em alta/Playlists changes.
            AppContentKind.TAB -> "tab:$tab"
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

private const val HOME_SUGGESTION_LIMIT = 25

private fun isJukeboxShelfTitle(value: String): Boolean {
    val title = normalizedShelfTitle(value)
    return title.contains("jukebox") ||
            title.contains("speed dial") ||
            title.contains("accesos directos") ||
            title.contains("acessos diretos") ||
            title.contains("atalhos")
}

private fun isQuickPicksShelfTitle(value: String): Boolean {
    val title = normalizedShelfTitle(value)
    return title.contains("quick picks") ||
            title.contains("quick pick") ||
            title.contains("escolha a dedo") ||
            title.contains("escolhidas a dedo") ||
            title.contains("selección rápida") ||
            title.contains("seleccion rapida")
}

private fun isSupermixTitle(value: String): Boolean {
    val title = normalizedShelfTitle(value)
    return title.contains("supermix") || title.contains("super mix")
}

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

    val sources = listOf(recent, jukebox, supermix).mapIndexed { index, source ->
        clean(source)
            .shuffled(kotlin.random.Random(HomeRecommendationSession.sessionToken + index))
            .sortedBy { it.videoId in HomeRecommendationSession.previousVideoIds }
    }
    val positions = IntArray(sources.size)
    val addedFromSource = IntArray(sources.size)
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

private fun String?.isYouTubeVideoThumbnail(): Boolean {
    val url = this?.lowercase(Locale.ROOT)?.substringBefore('?') ?: return false
    return url.endsWith("/hqdefault.jpg") ||
            url.endsWith("/mqdefault.jpg") ||
            url.endsWith("/sddefault.jpg") ||
            url.endsWith("/maxresdefault.jpg") ||
            url.endsWith("/0.jpg") ||
            url.endsWith("/1.jpg") ||
            url.endsWith("/2.jpg") ||
            url.endsWith("/3.jpg")
}

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
                    }
                    items.size < HOME_SUGGESTION_LIMIT -> {
                        items = clean
                        if (clean.size >= HOME_SUGGESTION_LIMIT) {
                            HomeSuggestionStore.replaceCurrent(clean)
                        }
                    }
                    clean.size >= HOME_SUGGESTION_LIMIT -> {
                        HomeSuggestionStore.stageNext(clean)
                    }
                }
            }
        }
        return items.take(HOME_SUGGESTION_LIMIT)
    }

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

    fun requestRefresh() {
        ensureLoaded()
        previousVideoIds = items.mapNotNull { it.videoId }.toSet()
        generation += 1L
        refreshRequested = true
        lastCandidateFingerprint = null
    }
}

private fun buildForYouState(
    home: UiState<List<HomeShelf>>,
    library: UiState<LibraryPage>,
    topArtists: List<StatsLeader>,
    youtubeSections: List<HomeShelf>,
    relatedSuggestions: List<ShelfItem>,
): UiState<List<HomeShelf>> {
    // The For You surface is deliberately curated into a stable product order.
    // YouTube's underlying FEmusic_home still supplies the personalized rows;
    // Orb only chooses which of those rows belong here and where they appear.
    val recommendations = relatedSuggestions.take(HOME_SUGGESTION_LIMIT)
    val shelves = (home as? UiState.Success)?.data.orEmpty()
    val discoveryShelves = distinctHomeShelves(shelves + youtubeSections)
    val libraryPage = (library as? UiState.Success)?.data

    fun addShelf(
        destination: MutableList<HomeShelf>,
        title: String,
        items: List<ShelfItem>,
        subtitle: String = "",
        limit: Int = 30,
    ) {
        if (items.isNotEmpty()) {
            destination += HomeShelf(
                title = title,
                items = distinctHomeItems(items).take(limit),
                subtitle = subtitle,
            )
        }
    }

    fun shelfText(shelf: HomeShelf): String = normalizedShelfTitle(
        "${shelf.title} ${shelf.subtitle}",
    )

    fun findShelf(predicate: (String) -> Boolean): HomeShelf? =
        discoveryShelves.firstOrNull { predicate(shelfText(it)) && it.items.isNotEmpty() }

    fun isListenAgain(text: String): Boolean =
        text.contains("listen again") ||
            text.contains("ouça novamente") ||
            text.contains("ouvir novamente") ||
            text.contains("escuchar de nuevo")

    fun isMixtapesForYou(text: String): Boolean =
        text.contains("mixed for you") ||
            text.contains("mixes for you") ||
            text.contains("mixes para você") ||
            text.contains("mixes para voce") ||
            text.contains("mixes para ti") ||
            text.contains("mixtapes para você") ||
            text.contains("mixtapes para voce") ||
            text.contains("mixtapes criadas para você") ||
            text.contains("mixtapes criadas para voce") ||
            text.contains("mixtapes created for you")

    fun isOldFavorites(text: String): Boolean =
        text.contains("forgotten favorites") ||
            text.contains("old favorites") ||
            text.contains("old favourites") ||
            text.contains("forgotten favourites") ||
            text.contains("favoritos antiguos") ||
            text.contains("favoritos antigos") ||
            text.contains("favoritos esquecidos") ||
            text.contains("favoritos olvidados")

    fun isSimilarTo(text: String): Boolean =
        text.contains("similar to") ||
            text.contains("more like") ||
            text.contains("parecido com") ||
            text.contains("parecidos com") ||
            text.contains("parecido a") ||
            text.contains("similar a")

    fun isDailyDiscoveries(text: String): Boolean =
        text.contains("daily discoveries") ||
            text.contains("your daily discoveries") ||
            text.contains("suas descobertas diárias") ||
            text.contains("suas descobertas diarias") ||
            text.contains("descobertas diárias") ||
            text.contains("descobertas diarias") ||
            text.contains("tus descubrimientos diarios") ||
            text.contains("descubrimientos diarios")

    val catalogueArtists = discoveryShelves
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

    val listenAgain = findShelf(::isListenAgain)
    val mixtapes = findShelf(::isMixtapesForYou)
    val releases = findShelf { text ->
        text.contains("new releases") || text.contains("lançamentos") ||
            text.contains("lancamentos") || text.contains("nuevos lanzamientos")
    }
    val oldFavorites = findShelf(::isOldFavorites)
    val dailyDiscoveries = findShelf(::isDailyDiscoveries)

    // Prefer the highest-ranked listened artist for which YouTube actually
    // supplied a "Similar to" shelf. This keeps the section personalized and
    // avoids inventing a recommendation row the service did not return.
    val similarToTopArtist: Pair<StatsLeader, HomeShelf>? = topArtists
        .asSequence()
        .mapNotNull { leader ->
            val artistKey = normalizedShelfTitle(leader.title)
            discoveryShelves.firstOrNull { shelf ->
                val combined = shelfText(shelf)
                val titleKey = normalizedShelfTitle(shelf.title)
                isSimilarTo(combined) &&
                    (titleKey == artistKey ||
                        titleKey.contains(artistKey) ||
                        artistKey.contains(titleKey))
            }?.let { leader to it }
        }
        .firstOrNull()

    val curated = buildList {
        // 1. Suggestions — exact Quick Picks / Escolha a dedo songs.
        add(HomeShelf(HOME_RECOMMENDATIONS_SHELF_TITLE, recommendations))

        // 2. Listen again.
        listenAgain?.let { shelf ->
            addShelf(this, "Listen again", shelf.items)
        }

        // 3. Mixtapes for you.
        mixtapes?.let { shelf ->
            addShelf(this, "Mixtapes for you", shelf.items)
        }

        releases?.let { shelf -> addShelf(this, "New releases", shelf.items) }

        // 4. From your library.
        addShelf(
            this,
            "From your library",
            youtubeLibrarySongs + librarySongs + homeLibrarySongs,
        )

        // 5. Old favorites.
        oldFavorites?.let { shelf ->
            addShelf(this, "Old favorites", shelf.items)
        }

        // 6. Similar to one of the listener's most-played artists. The small
        // strapline is rendered above the artist name to mirror YouTube Music.
        similarToTopArtist?.let { (leader, shelf) ->
            addShelf(
                destination = this,
                title = leader.title,
                items = shelf.items,
                subtitle = HOME_SIMILAR_TO_SUBTITLE,
            )
        }

        // 7. Daily discoveries.
        dailyDiscoveries?.let { shelf ->
            addShelf(this, "Daily discoveries", shelf.items)
        }

        // 8. Artists.
        addShelf(this, HOME_TOP_ARTISTS_SHELF_TITLE, topArtistItems, limit = 10)
    }

    return if (home is UiState.Error && curated.all { it.items.isEmpty() }) {
        home
    } else {
        UiState.Success(curated)
    }
}

private fun distinctHomeShelves(shelves: Iterable<HomeShelf>): List<HomeShelf> {
    val seen = HashSet<String>()
    return shelves.filter { shelf ->
        val key = "${normalizedShelfTitle(shelf.title)}|${normalizedShelfTitle(shelf.subtitle)}"
        seen.add(key)
    }
}

private fun buildAlbumsState(
    hub: UiState<List<HomeShelf>>,
    library: UiState<LibraryPage>,
): UiState<List<HomeShelf>> {
    // Albums is a deliberately small, stable hub. Network recommendations and
    // the user's saved library are independent sources, so a slow Library
    // request must not hide New albums / Albums for you that are already ready.
    if (hub is UiState.Loading && library !is UiState.Success) return UiState.Loading

    val shelves = (hub as? UiState.Success)?.data.orEmpty()
    val newAlbumsShelf = shelves.firstOrNull {
        normalizedShelfTitle(it.title) in setOf("new albums", "novos álbuns", "nuevos álbumes")
    }
    val albumsForYouShelf = shelves.firstOrNull {
        normalizedShelfTitle(it.title) in setOf(
            "albums for you",
            "álbuns para você",
            "álbumes para ti",
        )
    }
    val suggestionsShelf = shelves.firstOrNull {
        normalizedShelfTitle(it.title) in setOf(
            "albums you may like",
            "álbuns que você pode gostar",
            "álbumes que te pueden gustar",
        )
    }

    // LibraryPage is already backed by whichever library backend the user chose
    // (YouTube Music or Orb local library), so this is the canonical source for
    // "Your albums" rather than listening-history rankings.
    val savedAlbums = distinctHomeItems(
        (library as? UiState.Success)
            ?.data
            ?.shelves
            .orEmpty()
            .flatMap { shelf -> shelf.items }
            .filter { item -> item.type == BrowseType.ALBUM },
    )

    val curated = buildList {
        // 1. New albums
        newAlbumsShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)

        // 2. Albums for you
        albumsForYouShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)

        // 3. Your albums — releases explicitly saved in the selected library.
        if (savedAlbums.isNotEmpty()) {
            add(HomeShelf(title = "Your albums", items = savedAlbums))
        }

        // 4. Albums you may like
        suggestionsShelf?.takeIf { it.items.isNotEmpty() }?.let(::add)
    }

    return when {
        curated.isNotEmpty() -> UiState.Success(curated)
        hub is UiState.Error -> hub
        library is UiState.Error -> library.let { UiState.Error(it.message) }
        else -> UiState.Success(emptyList())
    }
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
            // Keep a useful catalogue fallback even before buildPlaylistsState can
            // reconcile this recent card with the richer copy from YouTube Music.
            // Previously this was always blank, so generated mixes lost the artist
            // credit that the exact same card shows in Para você.
            subtitle = song.artist.trim(),
            thumbnailUrl = song.sourcePlaylistArtworkUrl ?: song.thumbnailUrl,
            videoId = null,
            browseId = browseId,
            type = BrowseType.PLAYLIST,
        )
    }.take(12)
}

private fun playlistIdentity(id: String?): String? = id
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.removePrefix("VL")

private fun enrichRecentPlaylistItems(
    recent: List<ShelfItem>,
    catalogueCandidates: List<ShelfItem>,
): List<ShelfItem> {
    if (recent.isEmpty() || catalogueCandidates.isEmpty()) return recent

    val byId = LinkedHashMap<String, ShelfItem>()
    val byTitle = LinkedHashMap<String, ShelfItem>()
    catalogueCandidates.forEach { candidate ->
        if (candidate.type != BrowseType.PLAYLIST) return@forEach
        val useful = candidate.subtitle.isNotBlank() || candidate.thumbnailUrl != null
        if (!useful) return@forEach
        playlistIdentity(candidate.browseId)?.let { byId.putIfAbsent(it, candidate) }
        val titleKey = normalizedShelfTitle(candidate.title)
        if (titleKey.isNotBlank()) byTitle.putIfAbsent(titleKey, candidate)
    }

    return recent.map { item ->
        val richer = playlistIdentity(item.browseId)?.let(byId::get)
            ?: byTitle[normalizedShelfTitle(item.title)]
            ?: return@map item
        item.copy(
            // Prefer the full catalogue credit (e.g. "Madonna, Frank Ocean, …")
            // over the single-track fallback captured by RecentPlaybackStore.
            subtitle = richer.subtitle.takeIf { it.isNotBlank() } ?: item.subtitle,
            thumbnailUrl = item.thumbnailUrl ?: richer.thumbnailUrl,
        )
    }
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
    catalogueSections: List<HomeShelf>,
): UiState<List<HomeShelf>> {
    val own = ownPlaylists.map { it.asHomePlaylistItem() }
    val communityItems = (community as? UiState.Success)
        ?.data
        .orEmpty()
        .flatMap { it.items }
        .distinctBy { it.browseId ?: it.title }
    // Use the exact same combined Home catalogue that powers Para você. Some
    // personalised mix shelves (including My Supermix / My Mix variants) live
    // in homeState rather than homeYouTubeSections, and those copies carry the
    // rich multi-artist subtitle. Restricting this reconciliation to only the
    // YouTube continuation shelves made the Playlists tab fall back to the one
    // artist captured by RecentPlaybackStore even though Para você already had
    // the authoritative catalogue card in memory.
    val cataloguePlaylistItems = catalogueSections
        .flatMap { it.items }
        .filter { it.type == BrowseType.PLAYLIST }
    val recent = enrichRecentPlaylistItems(
        recent = recentPlaylistHomeItems(),
        catalogueCandidates = cataloguePlaylistItems + own + communityItems,
    )

    val recaps = if (signedIn) distinctHomeItems(
        catalogueSections.filter { it.title.equals("Recaps", ignoreCase = true) }
            .flatMap { it.items }.filter { it.type == BrowseType.PLAYLIST },
    ) else emptyList()
    val shelves = buildList {
        if (own.isNotEmpty()) add(HomeShelf(yoursTitle, own))
        if (recent.isNotEmpty()) add(HomeShelf(recentTitle, recent))
        if (communityItems.isNotEmpty()) add(HomeShelf(communityTitle, communityItems))
        if (recaps.isNotEmpty()) add(HomeShelf("Recaps", recaps))
    }

    // Local and already fetched shelves are immediately usable. Do not hide them
    // while the independent community/library requests finish in the background.
    if (shelves.isEmpty() && (community is UiState.Loading ||
            (signedIn && (ownPlaylistsLoading || !ownPlaylistsLoaded)))) return UiState.Loading
    return if (shelves.isEmpty() && community is UiState.Error) community else UiState.Success(shelves)
}

@Composable
private fun PlayerActionSheetSystemBars(
    hideStatusBar: Boolean,
) {
    val view = LocalView.current

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
        }
    }
}

@Composable
private fun DetailContentTheme(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        OrbTheme(darkTheme = true) { content() }
    } else {
        content()
    }
}

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.music.orb.data.settings.OrbAppLanguage.wrap(newBase))
    }

    private var pendingYoutubeConnection: ((Result<Unit>) -> Unit)? = null
    private var openNowPlayingRequest by mutableStateOf(false)
    private var pendingYouTubeMusicVideoId by mutableStateOf<String?>(null)

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_NOW_PLAYING) {
            openNowPlayingRequest = true
        }
        if (intent?.action == Intent.ACTION_VIEW) {
            val videoId = com.music.orb.playback.YouTubeMusicLinkParser.videoId(intent.data)
            if (videoId != null) {
                pendingYouTubeMusicVideoId = videoId
            }
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
                    OrbGoogleAuth.requestYoutubeCompatibility()
                }
                callback(connection.map { Unit })
            }
        }

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

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            HomeRecommendationSession.beginAppEntry()
        }
        handleLaunchIntent(intent)
        enableEdgeToEdge()

        // Orb draws its own edge-to-edge transfer light across the system chrome.
        // Keep the status-bar surface fully transparent so Samsung/tablet contrast
        // enforcement cannot introduce a straight divider through the glow.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }

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
            OrbTheme(darkTheme = darkTheme) {
                var showLaunchAnimation by remember {
                    mutableStateOf(savedInstanceState == null && !openNowPlayingRequest)
                }
                Box(Modifier.fillMaxSize()) {
                    if (!showLaunchAnimation) {
                        val launchViewModel: MainViewModel = viewModel()
                        run {
                            OrbApp(
                                darkTheme = darkTheme,
                                openNowPlayingRequest = openNowPlayingRequest,
                                onOpenNowPlayingRequestConsumed = { openNowPlayingRequest = false },
                                sharedYouTubeMusicVideoId = pendingYouTubeMusicVideoId,
                                onSharedYouTubeMusicVideoConsumed = { pendingYouTubeMusicVideoId = null },
                                viewModel = launchViewModel,
                            )
                        }
                    }
                    if (showLaunchAnimation) {
                        com.music.orb.ui.components.OrbAnimatedSplash(
                            darkTheme = darkTheme,
                            onFinished = {
                                showLaunchAnimation = false
                            },
                        )
                    }
                    // If the user declines optional overlay access, retain the
                    // original in-app lock surface for the foreground-only case.
                    // With access granted, the independent Activity owns the lock
                    // screen and finishing it restores the previous app's task.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        !android.provider.Settings.canDrawOverlays(this@MainActivity)
                    ) {
                        OrbLockscreenLayer()
                    }
                }
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
    val destination: Rect? = null,
)

private enum class TouchTransferDirection { OUTGOING, INCOMING }
private enum class TouchTransferPhase { PREPARING, TRANSFERRING }

private data class TouchTransferVisual(
    val token: Int,
    val handoffId: String,
    val song: Song,
    val direction: TouchTransferDirection,
    val phase: TouchTransferPhase,
    val artworkOrigin: Rect? = null,
)

// Keep large screen bodies out of OrbApp's JVM method. Box is inline; this
// composable boundary deliberately is not, so each content body compiles separately.
@Composable
private fun OrbAppSurface(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier,
        contentAlignment = contentAlignment,
        propagateMinConstraints = propagateMinConstraints,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbApp(
    darkTheme: Boolean,
    openNowPlayingRequest: Boolean,
    onOpenNowPlayingRequestConsumed: () -> Unit,
    sharedYouTubeMusicVideoId: String?,
    onSharedYouTubeMusicVideoConsumed: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    val fixedControlsHazeState = remember { HazeState() }
    val predictiveBackPreviewHazeState = remember { HazeState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var tabBackStack by remember { mutableStateOf<List<Int>>(emptyList()) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackSettling by remember { mutableStateOf(false) }
    var predictiveBackExit by remember { mutableFloatStateOf(0f) }
    val predictiveBackFollow by androidx.compose.animation.core.animateFloatAsState(
        targetValue = predictiveBackProgress,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 850f),
        label = "predictiveBackFollow",
    )
    val predictiveBackVisual = if (predictiveBackSettling) predictiveBackProgress else predictiveBackFollow

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
    val rootIsTabletLandscape = !rootIsPhoneLayout && rootConfiguration.screenWidthDp > rootConfiguration.screenHeightDp
    val openModalSheets by com.music.orb.ui.components.SheetBackdropRegistry.openSheets.collectAsStateWithLifecycle()
    // Android blur uses dp, rather than percent: 70% of our existing 52dp full blur.
    val tabletSheetBlur = 36.4.dp

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
    var miniPlayerArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    var miniPlayerIdentityBounds by remember { mutableStateOf<Rect?>(null) }
    var miniPlayerPlayPauseBounds by remember { mutableStateOf<Rect?>(null) }
    var miniPlayerNextBounds by remember { mutableStateOf<Rect?>(null) }
    var nowPlayingIdentityBounds by remember { mutableStateOf<Rect?>(null) }
    var nowPlayingIdentityAppearance by remember { mutableStateOf<PlayerIdentityAppearance?>(null) }
    var nowPlayingArtworkReady by remember { mutableStateOf(false) }
    var nowPlayingPlayPauseBounds by remember { mutableStateOf<Rect?>(null) }
    var nowPlayingNextBounds by remember { mutableStateOf<Rect?>(null) }
    var nowPlayingSheetDismissProgress by remember { mutableFloatStateOf(0f) }
    var nowPlayingSheetOffsetPx by remember { mutableFloatStateOf(0f) }
    var nowPlayingLaunchToken by remember { mutableIntStateOf(0) }
    var nowPlayingLaunchVisual by remember { mutableStateOf<NowPlayingLaunchVisual?>(null) }
    var nowPlayingLaunchProgress by remember { mutableFloatStateOf(1f) }
    val nowPlayingMotionScope = rememberCoroutineScope()
    // Real artwork geometry reported by NowPlayingScreen. Incoming Touch Transfer
    // pre-composes the player invisibly, so by the time the travelling cover arrives
    // we already know its exact destination on phone/tablet and portrait/landscape.
    var nowPlayingArtworkBounds by remember { mutableStateOf<Rect?>(null) }
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
    var premiumLaunchHighlight by remember { mutableStateOf(false) }
    var showOrbLockSettings by rememberSaveable { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showAccountIntegrations by remember { mutableStateOf(false) }
    var showOrbProfile by rememberSaveable { mutableStateOf(false) }
    var publicProfileUserId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showListenBrainzLogin by remember { mutableStateOf(false) }
    var showLastfmLogin by rememberSaveable { mutableStateOf(false) }
    var lastfmPendingToken by rememberSaveable { mutableStateOf("") }
    var youtubeIdentityPicker by remember { mutableStateOf<List<YouTubeAccountIdentity>?>(null) }
    var youtubeIdentitySwitching by remember { mutableStateOf(false) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var libraryCollectionActions by remember { mutableStateOf<com.music.orb.data.model.ShelfItem?>(null) }
    var libraryCollectionShare by remember { mutableStateOf<com.music.orb.data.model.ShelfItem?>(null) }
    var linksLoading by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    var songShareTarget by remember { mutableStateOf<Song?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var roomAction by rememberSaveable { mutableStateOf<String?>(null) }
    val listeningRoom by com.music.orb.data.rooms.RoomRepository.playbackRoom.collectAsStateWithLifecycle()
    var playlistActions by remember { mutableStateOf<UserPlaylist?>(null) }
    val autoplaySetting by AppSettings.autoplay.collectAsStateWithLifecycle()
    val autoplay = autoplaySetting && listeningRoom == null
    val playbackHistoryEntries by RecentPlaybackStore.entries.collectAsStateWithLifecycle()
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
    var showPlaybackHistory by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab != TAB_LIBRARY) {
            librarySearchActive = false
            libraryQuery = ""
        }
    }

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val homeYouTubeSections by viewModel.homeYouTubeSections.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val albumExclusionRevision by viewModel.albumExclusionRevision.collectAsStateWithLifecycle()

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
        albumExclusionRevision,
    ) {
        if (!homeReadyForSuggestions || homeRefreshingForSuggestions) return@LaunchedEffect

        val sourceShelves = (homeState as? UiState.Success)?.data.orEmpty()
        val youtubeShelves = homeYouTubeSections
        val discoveryShelves = sourceShelves + youtubeShelves

        // Keep Quick Picks first, then complete the 25-card carousel with songs from
        // YouTube Music's personalised "Quick picks" / "Escolha a dedo" shelf.
        // Preserve document order and never shuffle this shelf. The old blend
        // below remains only as a resilience fallback for accounts/regions
        // where YouTube does not return Quick Picks at all.
        val quickPicks = discoveryShelves
            .firstOrNull { shelf ->
                isQuickPicksShelfTitle(shelf.title) ||
                        isQuickPicksShelfTitle(shelf.subtitle)
            }
            ?.items
            .orEmpty()
            .asSequence()
            .filter { !it.videoId.isNullOrBlank() && !it.isVideo }
            .filter { item ->
                val song = item.asSong()
                song == null || !viewModel.shouldAvoidPlayback(song)
            }
            .distinctBy { it.videoId }
            .take(HOME_SUGGESTION_LIMIT)
            .toList()

        if (quickPicks.size >= HOME_SUGGESTION_LIMIT) {
            relatedHomeSuggestions = quickPicks
            HomeSuggestionStore.replaceCurrent(quickPicks)
            return@LaunchedEffect
        }

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
            val mixed = mixedSuggestionItems(
                recent = recent,
                jukebox = jukebox,
                supermix = supermix,
                fallback = HomeSuggestionStore.loadPool(),
                fillRemainder = true,
            ).filter { item ->
                val song = item.asSong()
                song == null || !viewModel.shouldAvoidPlayback(song)
            }
            relatedHomeSuggestions = distinctHomeItems(
                quickPicks + mixed + discoveryShelves.flatMap { it.items } + HomeSuggestionStore.loadCurrent(),
            ).filter { item ->
                !item.videoId.isNullOrBlank() && !item.isVideo &&
                    item.asSong()?.let { !viewModel.shouldAvoidPlayback(it) } == true
            }.take(HOME_SUGGESTION_LIMIT)
            if (relatedHomeSuggestions.size >= HOME_SUGGESTION_LIMIT) {
                HomeSuggestionStore.replaceCurrent(relatedHomeSuggestions)
            }
        }

        publishMixedSuggestions()

        val needsMorePersonalisedSources = jukebox.isEmpty() ||
                (supermix.isEmpty() && supermixBrowseId.isNullOrBlank())
        if (needsMorePersonalisedSources && suggestionContinuationAttempts < 2) {
            suggestionContinuationAttempts += 1
            viewModel.loadMoreHome()
        }

        if (!supermixLoadedForSession && !supermixBrowseId.isNullOrBlank()) {
            val fetchResult = YtMusicRepository.browseSongs(supermixBrowseId)
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

    val updateNotice = updateAvailable.takeUnless {
        betaUpdatesEnabled || stableAutoUpdatesEnabled || BuildConfig.IS_BETA
    }

    LaunchedEffect(betaUpdatesEnabled, stableAutoUpdatesEnabled, BuildConfig.IS_BETA) {
        if (betaUpdatesEnabled || stableAutoUpdatesEnabled || BuildConfig.IS_BETA) {
            showUpdateDialog = false
        }
    }

    val updateReminderUntil by AppSettings.updateReminderUntil.collectAsStateWithLifecycle()
    LaunchedEffect(
        updateNotice, automaticUpdateAvailable?.tagName, automaticUpdateAvailable?.channel,
        automaticUpdateReady?.tagName, automaticUpdateReady?.channel, updateReminderUntil,
    ) {
        if (updateNotice == null && automaticUpdateAvailable == null && automaticUpdateReady == null) return@LaunchedEffect
        if (updateReminderUntil > System.currentTimeMillis()) {
            showUpdateDialog = false
            showAutomaticUpdateDialog = false
            while (true) {
                val remaining = updateReminderUntil - System.currentTimeMillis()
                if (remaining <= 0L) break
                delay(remaining.coerceAtMost(60_000L))
            }
        }
        if (automaticUpdateReady != null || automaticUpdateAvailable != null) {
            showUpdateDialog = false
            showAutomaticUpdateDialog = true
        } else if (updateNotice != null && (!updateDialogShown || updateReminderUntil > 0L)) {
            updateDialogShown = true
            showUpdateDialog = true
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

        if (youtubeConnected && hasUsername) {
            finishFirstAccountOnboarding()
            return@LaunchedEffect
        }

        if (
            !youtubeConnected &&
            !FirstAccountOnboardingStore.youtubeHandled(context, userId)
        ) {
            firstAccountOnboardingStep = "youtube"
            return@LaunchedEffect
        }

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
            }
        }
        WelcomeLoginScreen(
            onSignIn = { requestGoogleSignIn() },
        )
        return
    }

    val friendsViewModel: FriendsViewModel = viewModel()
    val friendsRefreshing by friendsViewModel.refreshing.collectAsStateWithLifecycle()
    val friendsSearchOpen by friendsViewModel.userSearchOpen.collectAsStateWithLifecycle()
    val statsNotificationsOpen by friendsViewModel.notificationsOpen.collectAsStateWithLifecycle()
    val statsNotificationUnread by friendsViewModel.notificationUnreadCount.collectAsStateWithLifecycle()
    val artistRankingOpen by friendsViewModel.artistRankingOpen.collectAsStateWithLifecycle()
    val statsPeriod by friendsViewModel.statsPeriod.collectAsStateWithLifecycle()
    val homeTopArtists by friendsViewModel.homeTopArtists.collectAsStateWithLifecycle()

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

    // Premium Orb Touch Transfer. BLE proves physical proximity; Supabase RLS proves that
    // both devices are the same Orb account. The PlaybackService keeps the sender discoverable
    // during active playback; the receiver still needs Orb open for the visible touch handoff.
    val premiumEntitled by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
    val serverOrbSwitchEnabled by AppSettings.serverOrbSwitchEnabled.collectAsStateWithLifecycle()
    val serverControlRefreshIntervalSeconds by AppSettings.serverControlRefreshIntervalSeconds.collectAsStateWithLifecycle()
    val touchTransferEnabled by AppSettings.touchTransferEnabled.collectAsStateWithLifecycle()
    val myOrbProfile by SocialRepository.myProfileState.collectAsStateWithLifecycle()
    val touchTransferAccountId = myOrbProfile?.id ?: SocialRepository.currentUserId()
    val touchLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(touchTransferAccountId, touchLifecycleOwner, serverControlRefreshIntervalSeconds) {
        if (touchTransferAccountId == null) return@LaunchedEffect
        touchLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // One authoritative request, no recursive billing fallback
                    // that fetches the same endpoint again on transient errors.
                    runCatching { com.music.orb.data.billing.ServerControlApi.refresh() }
                }
                // Server capabilities are already cached by account. Avoid
                // revalidating Premium every minute while the app is open.
                delay(serverControlRefreshIntervalSeconds.coerceIn(300L, 900L) * 1_000L)
            }
        }
    }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val keyguardManager = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    fun touchUiEligible(): Boolean =
        touchLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            powerManager?.isInteractive == true &&
            keyguardManager?.isKeyguardLocked != true

    var touchPermissionsGranted by remember {
        mutableStateOf(OrbTouchTransfer.hasRuntimePermissions(context))
    }
    var touchPermissionsRequested by rememberSaveable { mutableStateOf(false) }
    val touchPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        touchPermissionsGranted = grants.values.all { it }
        val accountId = touchTransferAccountId
        if (
            touchPermissionsGranted && premiumEntitled && serverOrbSwitchEnabled && touchTransferEnabled &&
            accountId != null && touchUiEligible()
        ) {
            OrbTouchTransfer.start(context, accountId, OrbTouchTransfer.Client.UI)
        }
    }

    LaunchedEffect(premiumEntitled, touchTransferEnabled, touchTransferAccountId) {
        if (!premiumEntitled || !serverOrbSwitchEnabled || !touchTransferEnabled || touchTransferAccountId == null) {
            OrbTouchTransfer.stop(OrbTouchTransfer.Client.UI)
            return@LaunchedEffect
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && OrbTouchTransfer.supported(context)) {
            touchPermissionsGranted = OrbTouchTransfer.hasRuntimePermissions(context)
            if (!touchPermissionsGranted && !touchPermissionsRequested) {
                touchPermissionsRequested = true
                touchPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ),
                )
            } else if (touchPermissionsGranted && touchUiEligible()) {
                OrbTouchTransfer.start(context, touchTransferAccountId, OrbTouchTransfer.Client.UI)
            }
        }
    }

    DisposableEffect(
        touchLifecycleOwner,
        touchTransferAccountId,
        premiumEntitled,
        touchTransferEnabled,
        touchPermissionsGranted,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    OrbTouchTransfer.setUiActive(true)
                    val accountId = touchTransferAccountId
                    if (
                        premiumEntitled && serverOrbSwitchEnabled && touchTransferEnabled && touchPermissionsGranted &&
                        accountId != null && touchUiEligible()
                    ) {
                        OrbTouchTransfer.start(context, accountId, OrbTouchTransfer.Client.UI)
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    // Discovery is intentionally foreground-only: screen on + unlocked + Orb open.
                    OrbTouchTransfer.setUiActive(false)
                    OrbTouchTransfer.stop(OrbTouchTransfer.Client.UI)
                }
                else -> Unit
            }
        }
        touchLifecycleOwner.lifecycle.addObserver(observer)
        if (touchUiEligible()) {
            OrbTouchTransfer.setUiActive(true)
        }
        onDispose {
            touchLifecycleOwner.lifecycle.removeObserver(observer)
            OrbTouchTransfer.setUiActive(false)
            OrbTouchTransfer.stop(OrbTouchTransfer.Client.UI)
        }
    }

    // Unlock may finish after ON_RESUME. Reconcile eligibility continuously so
    // a missed lifecycle/screen transition cannot leave discovery stopped.
    LaunchedEffect(
        touchLifecycleOwner,
        touchTransferAccountId,
        premiumEntitled,
        touchTransferEnabled,
        touchPermissionsGranted,
    ) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val accountId = touchTransferAccountId
            val eligible = premiumEntitled && serverOrbSwitchEnabled && touchTransferEnabled &&
                touchPermissionsGranted && accountId != null && touchUiEligible()
            OrbTouchTransfer.setUiActive(eligible)
            if (eligible && accountId != null) {
                // start is idempotent: a healthy running session is preserved.
                OrbTouchTransfer.start(context, accountId, OrbTouchTransfer.Client.UI)
            } else {
                OrbTouchTransfer.stop(OrbTouchTransfer.Client.UI)
            }
            kotlinx.coroutines.delay(500L)
        }
    }

    // The Orb account owns application preferences. A second device signed into the same
    // account downloads them immediately; later edits are mirrored back through Supabase.
    DisposableEffect(touchTransferAccountId) {
        touchTransferAccountId?.let { AccountSettingsSync.start(context, it) }
        onDispose { AccountSettingsSync.stop() }
    }

    androidx.compose.runtime.SideEffect {
        OrbTouchTransfer.setNowPlayingOpen(showNowPlaying)
    }
    DisposableEffect(Unit) {
        onDispose { OrbTouchTransfer.setNowPlayingOpen(false) }
    }

    val touchTransferQueue = remember(player.queue) { player.queue.map { it.toTouchSong() } }
    LaunchedEffect(
        player.isPlaying,
        player.positionMs,
        player.queueIndex,
        player.repeatMode,
        touchTransferQueue,
        premiumEntitled,
    ) {
        if (premiumEntitled && serverOrbSwitchEnabled && touchTransferEnabled) {
            OrbTouchTransfer.updatePlayback(
                TouchPlaybackSnapshot(
                    isPlaying = player.isPlaying,
                    positionMs = player.positionMs,
                    queueIndex = player.queueIndex,
                    repeatMode = player.repeatMode,
                    queue = touchTransferQueue,
                ),
            )
        }
    }

    // Must be declared before the Touch Transfer event collector because an
    // incoming transfer retains the arriving song until its artwork expands
    // into Now Playing.
    var retainedNowPlayingSong by remember { mutableStateOf<Song?>(null) }

    var touchTransferVisual by remember { mutableStateOf<TouchTransferVisual?>(null) }
    var transferredAwaySongId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player.song?.videoId, player.isPlaying) {
        if (player.isPlaying || player.song?.videoId != transferredAwaySongId) {
            transferredAwaySongId = null
        }
    }
    var touchTransferVisualToken by remember { mutableIntStateOf(0) }
    val touchTransferHandledIncomingIds = remember(touchTransferAccountId) { linkedSetOf<String>() }
    var touchTransferPreparingIncomingId by remember { mutableStateOf<String?>(null) }
    // While an incoming cover is travelling, keep the real MiniPlayer laid out but
    // transparent. Its measured artwork rect becomes the exact landing destination.
    var touchTransferIncomingLanding by remember { mutableStateOf(false) }
    var touchTransferLandedId by remember { mutableStateOf<String?>(null) }
    var touchTransferDepartedId by remember { mutableStateOf<String?>(null) }
    var touchTransferReceivingId by remember { mutableStateOf<String?>(null) }
    var touchTransferSettledId by remember { mutableStateOf<String?>(null) }
    val touchTransferBlurActive = touchTransferVisual?.let {
        !(it.direction == TouchTransferDirection.INCOMING &&
            it.phase == TouchTransferPhase.TRANSFERRING && touchTransferSettledId == it.handoffId)
    } ?: false
    var touchTransferCollapseNowPlayingToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(touchTransferEnabled) {
        if (!touchTransferEnabled) touchTransferVisual = null
    }
    LaunchedEffect(controller, premiumEntitled, touchTransferEnabled, touchTransferAccountId) {
        OrbTouchTransfer.events.collect { event ->
            val live = controller ?: return@collect
            if (!premiumEntitled || !serverOrbSwitchEnabled || !touchTransferEnabled || touchTransferAccountId == null) return@collect
            when (event) {
                is OrbTouchTransfer.Event.OutgoingOffer -> {
                    touchTransferVisualToken += 1
                    touchTransferVisual = TouchTransferVisual(
                        token = touchTransferVisualToken,
                        handoffId = event.handoffId,
                        song = event.song.toSong(),
                        direction = TouchTransferDirection.OUTGOING,
                        phase = TouchTransferPhase.PREPARING,
                    )
                    OrbHaptics.startTransferGlow(context)
                }

                is OrbTouchTransfer.Event.IncomingOffer -> {
                    if (!touchTransferHandledIncomingIds.add(event.handoffId)) {
                        android.util.Log.d("OrbTouchTransfer", "duplicate incoming offer ignored ${event.handoffId}")
                        return@collect
                    }
                    while (touchTransferHandledIncomingIds.size > 64) {
                        touchTransferHandledIncomingIds.remove(touchTransferHandledIncomingIds.first())
                    }
                    transferredAwaySongId = null
                    touchTransferPreparingIncomingId = event.handoffId
                    touchTransferReceivingId = event.handoffId
                    com.music.orb.data.rooms.RoomSession.receiverPlaybackHeld = true
                    try {

                        // Both displays show the contact glow as soon as the handoff is armed.
                        // PREPARING is glow-only: no album art exists on the receiver yet.
                        touchTransferVisualToken += 1
                        touchTransferVisual = TouchTransferVisual(
                            token = touchTransferVisualToken,
                            handoffId = event.handoffId,
                            song = event.payload.currentSong.toSong(),
                            direction = TouchTransferDirection.INCOMING,
                            phase = TouchTransferPhase.PREPARING,
                        )
                        OrbHaptics.startTransferGlow(context)
                        // Receiver prefetches the image while audio is preparing; otherwise
                        // the transfer animation waits for a fresh CDN round trip.
                        event.payload.currentSong.toSong().let { arriving ->
                            val imageLoader = coil3.SingletonImageLoader.get(context)
                            arriving.artworkAt(1200)?.let { url ->
                                imageLoader.enqueue(coil3.request.ImageRequest.Builder(context)
                                    .data(url).size(1200).build())
                            }
                            arriving.artworkAt(256)?.let { url ->
                                imageLoader.enqueue(coil3.request.ImageRequest.Builder(context)
                                    .data(url).size(256).build())
                            }
                        }

                        val queue = event.payload.queue.map { it.toSong() }
                        if (queue.isEmpty()) {
                            if (touchTransferVisual?.handoffId == event.handoffId) {
                                touchTransferVisual = null
                            }
                            OrbHaptics.stopTransfer(context)
                            OrbTouchTransfer.cancel(event.handoffId)
                            touchTransferPreparingIncomingId = null
                            return@collect
                        }
                        val index = event.payload.queueIndex.coerceIn(0, queue.lastIndex)
                        com.music.orb.data.rooms.RoomRepository.pauseForPersonalPlayback(
                            live.currentMediaItemIndex.coerceAtLeast(0), live.currentPosition.coerceAtLeast(0L))
                        com.music.orb.data.social.PlaybackContinuity.adopt(event.payload.continuation)
                        // Prepare silently. The source keeps playing until this device proves that
                        // Media3 is READY, so a failed network lookup never creates a gap in audio.
                        live.pause()
                        live.setMediaItems(
                            queue.map { it.toMediaItem() },
                            index,
                            event.payload.positionMs.coerceAtLeast(0L),
                        )
                        live.repeatMode = event.payload.repeatMode
                        live.prepare()
                        val ready = withTimeoutOrNull(12_000L) {
                            while (live.playbackState != Player.STATE_READY) delay(45L)
                            true
                        } == true
                        if (ready) {
                            OrbTouchTransfer.receiverPrepared(event.handoffId)
                            touchTransferPreparingIncomingId = null
                        } else {
                            if (touchTransferVisual?.handoffId == event.handoffId) {
                                touchTransferVisual = null
                            }
                            OrbHaptics.stopTransfer(context)
                            OrbTouchTransfer.cancel(event.handoffId)
                            touchTransferPreparingIncomingId = null
                        }
                    } catch (cancelled: CancellationException) {
                        // A recreated collector may resume this transfer; finished IDs stay deduplicated.
                        touchTransferHandledIncomingIds.remove(event.handoffId)
                        touchTransferPreparingIncomingId = null
                        OrbHaptics.stopTransfer(context)
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("OrbTouchTransfer", "receiver preparation failed ${event.handoffId}", error)
                        touchTransferPreparingIncomingId = null
                        touchTransferVisual = null
                        OrbHaptics.stopTransfer(context)
                        OrbTouchTransfer.cancel(event.handoffId)
                    }
                }

                is OrbTouchTransfer.Event.PauseSource -> {
                    // A send always starts from the MiniPlayer. If Now Playing is open, keep the
                    // PREPARING glow alive while its real sheet animation collapses first. Only
                    // after that motion has finished do we detach the artwork from the MiniPlayer
                    // and send it into the transfer portal. This avoids two competing animations
                    // and makes the physical origin of the handoff unambiguous.
                    if (showNowPlaying) {
                        touchTransferCollapseNowPlayingToken += 1
                        withTimeoutOrNull(1_200L) {
                            while (showNowPlaying) delay(16L)
                        }
                        // Give Compose one frame to settle the MiniPlayer geometry after the sheet
                        // leaves. In normal operation its bounds are already retained underneath
                        // the sheet, but this also covers the first-render edge case.
                        delay(24L)
                    }

                    val miniArtworkOrigin = miniPlayerArtworkBounds
                    touchTransferDepartedId = null
                    if (miniArtworkOrigin == null) {
                        OrbTouchTransfer.cancel(event.handoffId)
                        return@collect
                    }
                    touchTransferVisualToken += 1
                    touchTransferVisual = TouchTransferVisual(
                        token = touchTransferVisualToken,
                        handoffId = event.handoffId,
                        song = event.song.toSong(),
                        direction = TouchTransferDirection.OUTGOING,
                        phase = TouchTransferPhase.TRANSFERRING,
                        artworkOrigin = miniArtworkOrigin,
                    )
                    OrbHaptics.startTransferMotion(context)
                    // Playback ownership stays on this device until the MiniPlayer artwork has
                    // physically reached the portal. The receiver therefore resumes from the
                    // final live position, with no audible gap introduced by the UI choreography.
                    val departed = withTimeoutOrNull(3_500L) {
                        androidx.compose.runtime.snapshotFlow { touchTransferDepartedId }
                            .first { it == event.handoffId }
                    }
                    if (departed == null || !OrbTouchTransfer.isUiActive()) {
                        OrbTouchTransfer.cancel(event.handoffId)
                        return@collect
                    }
                    val outgoingRoomId = com.music.orb.data.rooms.RoomRepository.playbackRoom.value?.id
                    outgoingRoomId?.let {
                        com.music.orb.data.rooms.RoomRepository.releaseToOtherDevice(it)
                    }
                    val outgoingCastPosition = com.music.orb.cast.OrbCastBridge.pauseForSwitch()
                    live.pause()
                    transferredAwaySongId = event.song.toSong().videoId
                    try {
                        OrbTouchTransfer.sourcePaused(
                            handoffId = event.handoffId,
                            finalPositionMs = (outgoingCastPosition ?: live.currentPosition).coerceAtLeast(0L),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        com.music.orb.cast.OrbCastBridge.cancelSwitchOut()
                        runCatching { OrbTouchTransfer.cancel(event.handoffId) }
                        val roomId = outgoingRoomId
                        if (roomId != null && com.music.orb.data.rooms.RoomRepository.hasPersonalPlayback) {
                            runCatching {
                                val state = com.music.orb.data.rooms.RoomRepository.open(roomId)
                                if (!state.playbackElsewhere) {
                                    com.music.orb.data.rooms.RoomRepository.activate()
                                    com.music.orb.data.rooms.RoomSession.awaitReady(roomId)
                                    live.play()
                                }
                            }
                        } else live.play()
                        OrbHaptics.stopTransfer(context)
                        touchTransferVisual = null
                        android.widget.Toast.makeText(context, R.string.rooms_error_network,
                            android.widget.Toast.LENGTH_LONG).show()
                        return@collect
                    }
                    com.music.orb.cast.OrbCastBridge.releaseForSwitch()
                    OrbHaptics.completeTransfer(context)
                    if (
                        touchTransferVisual?.handoffId == event.handoffId &&
                        touchTransferVisual?.direction == TouchTransferDirection.OUTGOING &&
                        touchTransferVisual?.phase == TouchTransferPhase.TRANSFERRING
                    ) {
                        touchTransferVisual = null
                    }
                }

                is OrbTouchTransfer.Event.StartReceiver -> {
                    if (!OrbTouchTransfer.canStartReceiver(event.handoffId) || !OrbTouchTransfer.isUiActive()) {
                        OrbTouchTransfer.cancel(event.handoffId)
                        return@collect
                    }
                    val arrivingSong = event.song.toSong()
                    touchTransferLandedId = null
                    com.music.orb.data.rooms.RoomSession.receiverPlaybackHeld = true
                    try {
                        event.payload.roomId?.let { roomId ->
                            com.music.orb.data.rooms.RoomRepository.acceptTransfer(roomId, event.handoffId,
                                OrbTouchTransfer.transferDeviceKey)
                            com.music.orb.data.rooms.RoomSession.awaitReady(roomId)
                        }
                    } catch (cancelled: CancellationException) {
                        com.music.orb.data.rooms.RoomSession.receiverPlaybackHeld = false
                        throw cancelled
                    } catch (_: Exception) {
                        runCatching {
                            com.music.orb.data.rooms.RoomRepository.pauseForPersonalPlayback(
                                live.currentMediaItemIndex.coerceAtLeast(0), live.currentPosition)
                        }
                        live.pause()
                        com.music.orb.data.rooms.RoomSession.receiverPlaybackHeld = false
                        runCatching { OrbTouchTransfer.cancel(event.handoffId) }
                        OrbHaptics.stopTransfer(context)
                        touchTransferVisual = null
                        android.widget.Toast.makeText(context, R.string.rooms_error_network,
                            android.widget.Toast.LENGTH_LONG).show()
                        return@collect
                    }
                    if (event.payload.roomId == null) live.seekTo(event.positionMs.coerceAtLeast(0L))
                    com.music.orb.data.social.PlaybackContinuity.adopt(event.payload.continuation?.takeIf {
                        it.songId == live.currentMediaItem?.mediaId
                    })

                    // Incoming handoff now lands in the real MiniPlayer first. If an old
                    // Now Playing is open on the receiver, collapse it before the artwork
                    // starts moving so there is only one visual destination on screen.
                    if (showNowPlaying) {
                        touchTransferCollapseNowPlayingToken += 1
                        withTimeoutOrNull(1_200L) {
                            while (showNowPlaying) delay(16L)
                        }
                    }

                    retainedNowPlayingSong = arrivingSong
                    nowPlayingLaunchVisual = null
                    nowPlayingLaunchProgress = 1f

                    // Lay out the destination MiniPlayer invisibly first. Clearing the old
                    // bounds prevents a previous track/device geometry from being reused.
                    miniPlayerArtworkBounds = null
                    touchTransferIncomingLanding = true
                    val destinationReady = withTimeoutOrNull(500L) {
                        while (miniPlayerArtworkBounds == null) delay(16L)
                        true
                    } == true
                    if (!destinationReady) {
                        live.pause()
                        OrbTouchTransfer.cancel(event.handoffId)
                        return@collect
                    }

                    touchTransferVisualToken += 1
                    touchTransferVisual = TouchTransferVisual(
                        token = touchTransferVisualToken,
                        handoffId = event.handoffId,
                        song = arrivingSong,
                        direction = TouchTransferDirection.INCOMING,
                        phase = TouchTransferPhase.TRANSFERRING,
                    )
                    OrbHaptics.startTransferMotion(context, durationMs = 1250L)
                    val landed = withTimeoutOrNull(3500L) {
                        androidx.compose.runtime.snapshotFlow { touchTransferLandedId }
                            .first { it == event.handoffId }
                    }
                    if (landed == null || !OrbTouchTransfer.canStartReceiver(event.handoffId) || !OrbTouchTransfer.isUiActive()) {
                        live.pause()
                        runCatching { OrbTouchTransfer.cancel(event.handoffId) }
                        touchTransferVisual = null
                        touchTransferIncomingLanding = false
                        return@collect
                    }
                    val committed = try {
                        OrbTouchTransfer.receiverCompleted(event.handoffId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                    if (!committed) {
                        live.pause()
                        OrbTouchTransfer.cancel(event.handoffId)
                        return@collect
                    }
                    if(event.payload.castDeviceId!=null) {
                        try {
                            val elapsed=if(event.payload.castResumeAtMs>0L)
                                (System.currentTimeMillis()-event.payload.castResumeAtMs).coerceIn(0L,60000L) else 0L
                            com.music.orb.cast.OrbCastBridge.acceptSwitch(event.payload.castDeviceId,
                                event.positionMs.coerceAtLeast(0L)+elapsed)
                        } catch(cancelled: CancellationException) {throw cancelled} catch(_: Exception) {
                            live.pause()
                            OrbTouchTransfer.cancel(event.handoffId)
                            OrbHaptics.stopTransfer(context)
                            touchTransferVisual=null;touchTransferIncomingLanding=false
                            android.widget.Toast.makeText(context,R.string.cast_failed,android.widget.Toast.LENGTH_LONG).show()
                            return@collect
                        }
                    } else live.play()
                    touchTransferReceivingId = null
                    com.music.orb.data.rooms.RoomSession.receiverPlaybackHeld = false
                }

                is OrbTouchTransfer.Event.Failed -> {
                    if (touchTransferReceivingId != null &&
                        (event.handoffId == null || event.handoffId == touchTransferReceivingId)) {
                        live.pause()
                        runCatching {
                            com.music.orb.data.rooms.RoomRepository.pauseForPersonalPlayback(
                                live.currentMediaItemIndex.coerceAtLeast(0), live.currentPosition.coerceAtLeast(0L))
                        }
                        touchTransferReceivingId = null
                    }
                    com.music.orb.data.rooms.RoomSession.receiverPlaybackHeld = false
                    transferredAwaySongId = null
                    OrbHaptics.stopTransfer(context)
                    touchTransferVisual = null
                    touchTransferIncomingLanding = false
                    touchTransferPreparingIncomingId = null
                    OrbNoticeCenter.post(
                        OrbNotice(
                            title = "Transferência por aproximação",
                            message = event.message,
                            icon = OrbNoticeIcon.INFO,
                        ),
                    )
                }
            }
        }
    }

    LaunchedEffect(controller, likeStatuses, artistPreferences, albumExclusionRevision) {
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
        controller?.stop()
        controller?.clearMediaItems()
        viewModel.signOut()
    }

    var pendingNowPlayingSong by remember { mutableStateOf<Song?>(null) }
    var pendingPlaybackCommitted by remember { mutableStateOf(false) }
    var playbackRequestGeneration by remember { mutableIntStateOf(0) }

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

    val automixVisualConfiguration = LocalConfiguration.current
    val automixArtworkEndProgress = (
            automixVisualConfiguration.screenWidthDp.toFloat() /
                    automixVisualConfiguration.screenHeightDp.coerceAtLeast(1).toFloat()
            ).coerceIn(0.30f, 0.72f)
    val automixLowerSpan = (1f - automixArtworkEndProgress).coerceAtLeast(0.01f)
    val automixSliderEndProgress =
        (automixArtworkEndProgress + automixLowerSpan * 0.43f).coerceIn(0f, 1f)

    val nowPlayingSong = when {
        pendingNowPlayingSong != null -> pendingNowPlayingSong
        automixVisualActive && automixVisualProgress <= 0.001f -> automixVisualOutgoingSong
        else -> player.song ?: retainedNowPlayingSong
    }

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
        nowPlayingArtworkBounds = null
        nowPlayingIdentityBounds = null
        nowPlayingIdentityAppearance = null
        nowPlayingArtworkReady = false
        nowPlayingPlayPauseBounds = null
        nowPlayingNextBounds = null
        nowPlayingSheetDismissProgress = 0f
        nowPlayingSheetOffsetPx = 0f
        // Freeze the shared opening timeline at its MiniPlayer origin immediately.
        // The animation starts only once the real Now Playing destination has been
        // measured, so the full MiniPlayer remains visible instead of disappearing
        // into a transparent modal while the sheet is still settling.
        nowPlayingLaunchProgress = 0f
        nowPlayingLaunchVisual = NowPlayingLaunchVisual(
            token = nowPlayingLaunchToken,
            song = song,
            origin = preciseOrigin?.bounds ?: fallbackOrigin,
            originKind = preciseOrigin?.kind ?: NowPlayingLaunchOriginKind.ARTWORK,
        )
    }

    LaunchedEffect(nowPlayingLaunchVisual?.token) {
        val visual = nowPlayingLaunchVisual
        if (visual == null) {
            nowPlayingLaunchProgress = 1f
            return@LaunchedEffect
        }
        if (visual.originKind == NowPlayingLaunchOriginKind.MINI_PLAYER) {
            // Measure once, then run one uninterrupted clock. Geometry/palette
            // updates must never cancel and restart the opening tween.
            withTimeoutOrNull(1200L) { androidx.compose.runtime.snapshotFlow {
                nowPlayingArtworkBounds != null && nowPlayingIdentityBounds != null &&
                    (listeningRoom?.host == false || (nowPlayingPlayPauseBounds != null && nowPlayingNextBounds != null)) &&
                    nowPlayingIdentityAppearance != null && nowPlayingArtworkReady
            }.first { ready -> ready } }
        }
        val token = visual.token
        val startProgress = nowPlayingLaunchProgress
        if (startProgress >= 1f) return@LaunchedEffect
        animate(
            initialValue = startProgress,
            targetValue = 1f,
            animationSpec = tween(((1f - startProgress) * 420).toInt().coerceAtLeast(1), easing = FastOutSlowInEasing),
        ) { value, _ -> nowPlayingLaunchProgress = value }
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
        prepareNowPlayingLaunch(song)
        showNowPlaying = true
        return playbackRequestGeneration
    }

    fun finishPlaybackPreparation(requestGeneration: Int) {
        if (requestGeneration != playbackRequestGeneration) return
    }

    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()
    val roomShuffleIds by com.music.orb.data.rooms.RoomRepository.shuffledRoomIds.collectAsStateWithLifecycle()
    val activeNetworkMetered by AppSettings.meteredConnection.collectAsStateWithLifecycle()

    // AutoPlay refill is owned by PlaybackService, not a lifecycle-aware UI effect.
    // This screen only records explicit listener decisions (remove/clear/new station).

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()

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
    // Public release introduction: on the first app entry after launch, open
    // Settings once and briefly emphasize the Premium card. A persistent flag
    // prevents the redirect from repeating on every future app launch.
    LaunchedEffect(signedIn, displayAccount?.email, firstAccountOnboardingStep) {
        if (!com.music.orb.data.settings.PremiumLaunch.isPublicLaunchTime()) return@LaunchedEffect
        if (firstAccountOnboardingStep != null || firstAccountOnboardingRequested) return@LaunchedEffect
        if (openNowPlayingRequest || showNowPlaying) return@LaunchedEffect
        if (AppSettings.isOwnerAccount.value) return@LaunchedEffect
        val prefs = context.getSharedPreferences("orb_premium_launch", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("intro_seen_2026_v1", false)) {
            // Persist before navigating so activity recreation cannot replay it.
            prefs.edit().putBoolean("intro_seen_2026_v1", true).apply()
            showOrbLockSettings = false
            showAccountIntegrations = false
            showSources = false
            showSettings = true
            settingsScrollState.scrollTo(0)
            premiumLaunchHighlight = true
        }
    }
    LaunchedEffect(premiumLaunchHighlight) {
        if (premiumLaunchHighlight) {
            kotlinx.coroutines.delay(4000L)
            premiumLaunchHighlight = false
        }
    }
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

    val tabs = remember(tabPlay, tabExplore, tabLibrary, tabFriends) {
        listOf(
            BottomTab(tabPlay, OrbIcons.Play),
            BottomTab(tabExplore, OrbIcons.Explore),
            BottomTab(tabLibrary, OrbIcons.Library),
            BottomTab(tabFriends, Icons.Rounded.BarChart),
        )
    }

    val scope = rememberCoroutineScope()

    suspend fun List<Song>.resolvedForQueue(): List<Song> = coroutineScope {
        map { async { YtMusicRepository.resolvePreferredPlaybackVersion(it) } }.awaitAll()
    }

    suspend fun preferredVersionForImmediateStart(song: Song): Song {
        // A user tap is authoritative. Verify that the catalogue video ID
        // belongs to the visible artist/title before opening its audio URL.
        // The bounded check is distinct from queued background metadata work.
        val lookup = scope.async {
            YtMusicRepository.resolveTrustedPlaybackVersion(song)
        }
        try {
            val immediate = withTimeoutOrNull(TRUSTED_FIRST_NOTE_BUDGET_MS) { lookup.await() }
            if (immediate != null) return immediate
        } finally {
            if (lookup.isActive) lookup.cancel()
        }
        // Do not block playback indefinitely during a metadata outage.
        return song
    }

    fun roomAllowsQueueReplacement(allowPersonalPlayback: Boolean = true): Boolean {
        if (com.music.orb.data.rooms.RoomRepository.playbackRoom.value == null) return true
        if (allowPersonalPlayback) return true
        com.music.orb.data.rooms.RoomRepository.viewPlayback()
        roomAction = "room"
        return false
    }

    suspend fun pauseRoomForPrivatePlayback(c: MediaController): Boolean {
        val activeRoom = com.music.orb.data.rooms.RoomRepository.playbackRoom.value ?: return true
        val wasPlaying = c.playWhenReady
        val index = c.currentMediaItemIndex.coerceAtLeast(0)
        val position = c.currentPosition.coerceAtLeast(0L)
        c.pause()
        return try {
            com.music.orb.data.rooms.RoomRepository.pauseForPersonalPlayback(index, position)
            if (!activeRoom.host) c.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            true
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (_: Exception) {
            if (com.music.orb.data.rooms.RoomRepository.playbackRoom.value?.id == activeRoom.id)
                c.playWhenReady = wasPlaying
            android.widget.Toast.makeText(context, R.string.rooms_error_network, android.widget.Toast.LENGTH_LONG).show()
            false
        }
    }

    val play: (List<Song>, Int) -> Unit = play@{ songs, index ->
        if (!roomAllowsQueueReplacement()) return@play
        if (index !in songs.indices) return@play
        val requested = songs[index]
        if (viewModel.shouldAvoidPlayback(requested)) return@play

        val queued = songs.filterNot(viewModel::shouldAvoidPlayback)
        if (queued.isEmpty()) return@play
        val eligibleIndex = songs.take(index).count { !viewModel.shouldAvoidPlayback(it) }
            .coerceIn(0, queued.lastIndex)

        val requestGeneration = beginPlaybackRequest(
            requested,
            showPreparingSet = false,
        )
        scope.launch {
            try {
                if (requestGeneration != playbackRequestGeneration) return@launch
                val preferredRequested = preferredVersionForImmediateStart(requested)
                if (viewModel.shouldAvoidPlayback(preferredRequested)) return@launch
                val originalRelease = requested.preserveAlbumRelease &&
                    requested.sourcePlaylistId == null &&
                    queued.all { it.preserveAlbumRelease && it.sourcePlaylistId == null && it.albumId == requested.albumId }
                // Preferred-version resolution may replace the first song's albumId,
                // but must not change the intent of Play from this release page.
                val firstTrack = if (originalRelease) preferredRequested.copy(
                    albumId = requested.albumId,
                    albumName = requested.albumName,
                    preserveAlbumRelease = true,
                ) else preferredRequested
                val playbackQueue = queued.toMutableList().apply {
                    this[eligibleIndex] = firstTrack
                }
                pendingNowPlayingSong = preferredRequested
                val c = controller ?: return@launch
                if (!pauseRoomForPrivatePlayback(c)) return@launch
                if (requestGeneration != playbackRequestGeneration) return@launch
                AutoplaySession.beginUserSelection()
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
                            albumId = if (previousItem.albumSequential || !AppSettings.prioritizeAlbumVersions.value) {
                                previousSong.albumId ?: resolved.albumId
                            } else {
                                resolved.albumId
                            },
                            albumName = if (previousItem.albumSequential || !AppSettings.prioritizeAlbumVersions.value) {
                                previousSong.albumName ?: resolved.albumName
                            } else {
                                resolved.albumName
                            },
                            preserveAlbumRelease = previousItem.albumSequential || previousSong.preserveAlbumRelease,
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
            } catch (mismatch: YtMusicRepository.WrongRecordingException) {
                Toast.makeText(context, R.string.orb_playback_wrong_recording, Toast.LENGTH_LONG).show()
            } finally {
                finishPlaybackPreparation(requestGeneration)
            }
        }
    }

    val playRadio: (Song) -> Unit = radio@{ song ->
        if (!roomAllowsQueueReplacement()) return@radio
        if (viewModel.shouldAvoidPlayback(song)) return@radio
        val requestGeneration = beginPlaybackRequest(song)
        scope.launch {
            try {
                if (requestGeneration != playbackRequestGeneration) return@launch
                val preferredSong = preferredVersionForImmediateStart(song)
                if (viewModel.shouldAvoidPlayback(preferredSong)) return@launch
                pendingNowPlayingSong = preferredSong
                val c = controller ?: return@launch
                if (!pauseRoomForPrivatePlayback(c)) return@launch
                if (requestGeneration != playbackRequestGeneration) return@launch
                AutoplaySession.beginUserSelection()
                c.playSongs(listOf(preferredSong), 0)
                pendingPlaybackCommitted = true

                if (!AppSettings.autoplay.value) {
                    AppSettings.setAutoplay(true)
                }

                // The service refills now (also with the app in background). Do not
                // generate an independent duplicate first station here.
            } catch (mismatch: YtMusicRepository.WrongRecordingException) {
                Toast.makeText(context, R.string.orb_playback_wrong_recording, Toast.LENGTH_LONG).show()
            } finally {
                finishPlaybackPreparation(requestGeneration)
            }
        }
    }

    val playSingle: (Song) -> Unit = single@{ song ->
        if (!roomAllowsQueueReplacement()) return@single
        if (viewModel.shouldAvoidPlayback(song)) return@single
        val requestGeneration = beginPlaybackRequest(song)
        scope.launch {
            try {
                if (requestGeneration != playbackRequestGeneration) return@launch
                val preferredSong = preferredVersionForImmediateStart(song)
                if (viewModel.shouldAvoidPlayback(preferredSong)) return@launch
                pendingNowPlayingSong = preferredSong
                val c = controller ?: return@launch
                if (!pauseRoomForPrivatePlayback(c)) return@launch
                if (requestGeneration != playbackRequestGeneration) return@launch
                AutoplaySession.beginUserSelection()
                c.playSongs(listOf(preferredSong), 0)
                pendingPlaybackCommitted = true
            } catch (mismatch: YtMusicRepository.WrongRecordingException) {
                Toast.makeText(context, R.string.orb_playback_wrong_recording, Toast.LENGTH_LONG).show()
            } finally {
                finishPlaybackPreparation(requestGeneration)
            }
        }
    }

    val playDirect: (Song) -> Unit = direct@{ song ->
        if (!roomAllowsQueueReplacement()) return@direct
        playSingle(song)
    }
    // Direct music.youtube.com/watch?v= links must work without a YouTube Music login.
    // The public watch queue supplies canonical title/artist where available, then the
    // ordinary Orb playback pipeline chooses a stream/source and presents Now Playing.
    LaunchedEffect(sharedYouTubeMusicVideoId, controller) {
        val id = sharedYouTubeMusicVideoId ?: return@LaunchedEffect
        if (controller == null) return@LaunchedEffect
        onSharedYouTubeMusicVideoConsumed()
        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            YtMusicRepository.trackLinks(id).getOrNull()
        }
        val song = resolved ?: Song(
            videoId = id,
            title = "YouTube Music",
            artist = "",
            thumbnailUrl = null,
        )
        playDirect(song)
        showNowPlaying = true
    }
    val addToQueue: (Song) -> Unit = add@{ song ->
        if (viewModel.shouldAvoidPlayback(song)) return@add
        scope.launch {
            val resolved = YtMusicRepository.resolvePreferredPlaybackVersion(song)
            if (viewModel.shouldAvoidPlayback(resolved)) return@launch
            controller?.let { controller ->
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
        if (!roomAllowsQueueReplacement(allowPersonalPlayback = false)) return@next
        if (viewModel.shouldAvoidPlayback(song)) return@next
        scope.launch {
            val resolved = YtMusicRepository.resolvePreferredPlaybackVersion(song)
            if (viewModel.shouldAvoidPlayback(resolved)) return@launch
            controller?.let { controller ->
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
    var downloadQualityPrompt by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedOfflineQuality by remember { mutableStateOf(OfflineDownloadQuality.HIGH) }
    // Download preferences govern whether Maximum may fall back to High AAC.
    // Read the latest preference for each queued track, including album/playlist batches.
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
                songs.forEach { Downloads.enqueue(context, it, selectedOfflineQuality, AppSettings.downloadAllowLowerQuality.value) }
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

    // The quality/fallback policy is shared by direct downloads and the picker.
    // Keeping one entry point also retains notification and storage-permission handling.
    val enqueueSelectedDownloads: (List<Song>) -> Unit = { songs ->
        if (songs.isNotEmpty()) {
            if (selectedOfflineQuality == OfflineDownloadQuality.MAXIMUM &&
                !AppSettings.premiumEntitled.value
            ) {
                Toast.makeText(
                    context,
                    context.getString(R.string.orb_download_quality_premium),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
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
                    songs.forEach {
                        Downloads.enqueue(context, it, selectedOfflineQuality, AppSettings.downloadAllowLowerQuality.value)
                    }
                    postDownloadNotice(songs)
                }
            }
        }
    }

    val startDownload: (List<Song>) -> Unit = { requested ->
        val saved = Downloads.saved.value
        val songs = requested.filter { it.videoId !in saved }
        if (songs.isNotEmpty()) {
            selectedOfflineQuality = runCatching {
                OfflineDownloadQuality.valueOf(AppSettings.downloadQuality.value)
            }.getOrDefault(OfflineDownloadQuality.HIGH)
            // Do not silently convert a saved Maximum preference into High
            // when the subscription is unavailable. enqueueSelectedDownloads
            // reports that Maximum needs Premium before any bytes are fetched.

            if (AppSettings.hasConfiguredDownloadQuality) {
                // Settings already contains the user's choice; download immediately.
                enqueueSelectedDownloads(songs)
            } else {
                // No explicit choice yet: keep the existing picker for this batch.
                downloadQualityPrompt = songs
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

    val confirmSelectedDownload: () -> Unit = {
        val songs = downloadQualityPrompt
        downloadQualityPrompt = emptyList()
        enqueueSelectedDownloads(songs)
    }

    if (downloadQualityPrompt.isNotEmpty()) {
        LaunchedEffect(downloadQualityPrompt) {
            if (!AppSettings.premiumEntitled.value && selectedOfflineQuality == OfflineDownloadQuality.MAXIMUM) {
                selectedOfflineQuality = OfflineDownloadQuality.HIGH
            }
        }
        val premiumGranted by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
        val choices = listOf(
            OfflineDownloadQuality.MEDIUM to R.string.orb_download_quality_medium,
            OfflineDownloadQuality.HIGH to R.string.orb_download_quality_high,
            OfflineDownloadQuality.MAXIMUM to R.string.orb_download_quality_maximum,
        )
        AlertDialog(
            onDismissRequest = { downloadQualityPrompt = emptyList() },
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.orb_download_quality_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (choice, stringId) ->
                        val locked = choice == OfflineDownloadQuality.MAXIMUM && !premiumGranted
                        val isSelected = selectedOfflineQuality == choice
                        val shape = RoundedCornerShape(20.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(shape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                                .clickable(enabled = !locked) { selectedOfflineQuality = choice }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(stringId),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (locked) {
                                    Text(
                                        stringResource(R.string.orb_download_quality_premium),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                when {
                                    locked -> "🔒"
                                    isSelected -> "✓"
                                    else -> ""
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (selectedOfflineQuality != OfflineDownloadQuality.MEDIUM) {
                        val allowLower by AppSettings.downloadAllowLowerQuality.collectAsStateWithLifecycle()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { AppSettings.setDownloadAllowLowerQuality(!allowLower) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(
                                        if (selectedOfflineQuality == OfflineDownloadQuality.MAXIMUM)
                                            R.string.settings_download_fallback_high_title
                                        else R.string.settings_download_fallback_lower_title,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        if (selectedOfflineQuality == OfflineDownloadQuality.MAXIMUM)
                                            R.string.settings_download_fallback_high_summary
                                        else R.string.settings_download_fallback_lower_summary,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = allowLower,
                                onCheckedChange = AppSettings::setDownloadAllowLowerQuality,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = confirmSelectedDownload,
                    enabled = selectedOfflineQuality != OfflineDownloadQuality.MAXIMUM || premiumGranted,
                ) { Text(stringResource(R.string.orb_download_quality_download)) }
            },
            dismissButton = {
                TextButton(onClick = { downloadQualityPrompt = emptyList() }) {
                    Text(stringResource(R.string.orb_download_quality_cancel))
                }
            },
        )
    }

    // During PREPARING, the receiver stays visually unchanged. Once StartReceiver
    // begins, the arriving track is laid out in a transparent MiniPlayer so the transfer
    // artwork can target its exact real-world coordinates. The MiniPlayer becomes visible
    // only after the cover has physically landed there.
    val incomingTransferPreparing =
        touchTransferVisual?.direction == TouchTransferDirection.INCOMING &&
            touchTransferVisual?.phase == TouchTransferPhase.PREPARING
    val miniPlayerSong = player.song.takeUnless {
        (incomingTransferPreparing && !touchTransferIncomingLanding) ||
            (transferredAwaySongId != null && it?.videoId == transferredAwaySongId)
    }

    val listBottomPadding = if (OrbFlavorUi.expressive) {
        if (miniPlayerSong != null) 228.dp else 154.dp
    } else {
        if (miniPlayerSong != null) 210.dp else 140.dp
    }
    val listPadding = PaddingValues(
        top = if (OrbFlavorUi.expressive) 104.dp else 96.dp,
        bottom = listBottomPadding,
    )
    // Accounts, Audio & Sources and Settings share the exact same visible
    // header. Avoid the old hardcoded 144dp inset that left the first items
    // floating far below the header compared with the Premium card.
    val settingsTopBarHeight = if (OrbFlavorUi.expressive) 60.dp else 52.dp
    val settingsListPadding = PaddingValues(
        top = WindowInsets.statusBarsIgnoringVisibility
            .asPaddingValues()
            .calculateTopPadding() + settingsTopBarHeight,
        bottom = listBottomPadding,
    )
    val topBarListPadding = settingsListPadding
    val homeListPadding = PaddingValues(
        top = if (OrbFlavorUi.expressive) 224.dp else 96.dp,
        bottom = listBottomPadding,
    )
    val exploreListPadding = PaddingValues(
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
    val detailSourcePalette = rememberArtworkPalette(
        detail?.thumbnailUrl,
        dark = darkTheme,
    )
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

    // Transfer overlays are siblings of the blurred application surface.
    OrbAppSurface(Modifier.fillMaxSize()) {
    OrbAppSurface(
        Modifier
            .fillMaxSize()
            .blur(
                radius = when {
                    touchTransferBlurActive -> 36.4.dp
                    openModalSheets > 0 || songActions != null ->
                        if (rootIsTabletLandscape) tabletSheetBlur else 52.dp
                    else -> 0.dp
                },
                edgeTreatment = if (openModalSheets > 0 || songActions != null) {
                    BlurredEdgeTreatment.Rectangle
                } else {
                    BlurredEdgeTreatment.Unbounded
                },
            )
            .onSizeChanged { appRootSize = it }
            .pointerInput(Unit) {
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
                        showPlaybackHistory ||
                        showAccountIntegrations ||
                        showSources ||
                        showSettings ||
                        detail != null
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
                showPlaybackHistory -> showPlaybackHistory = false
                showAccountIntegrations -> showAccountIntegrations = false
                showSources -> {
                    showSources = false
                    showSettings = true
                }
                showOrbLockSettings -> showOrbLockSettings = false
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

        var predictiveBackSwipeEdge by remember { mutableIntStateOf(0) }
        var predictiveBackTarget by remember { mutableStateOf<AppContentTarget?>(null) }

        fun getPreviousContentTarget(): AppContentTarget? {
            return when {
                activeUpdateDownload != null || showAutomaticUpdateDialog || showUpdateDialog || showListenBrainzLogin || showLastfmLogin -> null
                showPlaybackHistory -> AppContentTarget(
                    kind = if (publicProfileUserId != null) AppContentKind.PUBLIC_PROFILE
                    else if (showOrbProfile) AppContentKind.ORB_PROFILE
                    else if (showAccountIntegrations) AppContentKind.ACCOUNT
                    else if (showSources) AppContentKind.SOURCES
                    else if (showSettings) AppContentKind.SETTINGS
                    else if (detail != null) AppContentKind.DETAIL
                    else AppContentKind.TAB,
                    tab = selectedTab,
                    homeSection = homeSection,
                    detailDepth = detailStack.size,
                    detail = detail,
                    profileId = publicProfileUserId,
                )
                detail == null && selectedTab == TAB_FRIENDS && (friendsSearchOpen || artistRankingOpen || statsNotificationsOpen) -> AppContentTarget(
                    kind = AppContentKind.TAB,
                    tab = TAB_FRIENDS,
                    homeSection = homeSection,
                    detailDepth = 0,
                    statsOverview = true,
                )
                publicProfileUserId != null -> AppContentTarget(
                    kind = if (showOrbProfile) AppContentKind.ORB_PROFILE
                    else if (showAccountIntegrations) AppContentKind.ACCOUNT
                    else if (showSources) AppContentKind.SOURCES
                    else if (showSettings) AppContentKind.SETTINGS
                    else if (detail != null) AppContentKind.DETAIL
                    else AppContentKind.TAB,
                    tab = selectedTab,
                    homeSection = homeSection,
                    detailDepth = detailStack.size,
                    detail = detail,
                )
                showOrbProfile -> AppContentTarget(
                    kind = AppContentKind.ACCOUNT,
                    tab = selectedTab,
                    homeSection = homeSection,
                    detailDepth = 0,
                )
                showSources -> AppContentTarget(
                    kind = AppContentKind.SETTINGS,
                    tab = selectedTab,
                    homeSection = homeSection,
                    detailDepth = 0,
                )
                showSettings -> AppContentTarget(
                    kind = AppContentKind.TAB,
                    tab = selectedTab,
                    homeSection = homeSection,
                    detailDepth = 0,
                )
                showAccountIntegrations -> AppContentTarget(
                    kind = if (showSettings) AppContentKind.SETTINGS else AppContentKind.TAB,
                    tab = selectedTab,
                    homeSection = homeSection,
                    detailDepth = 0,
                )
                detail != null -> {
                    if (detailStack.size > 1) {
                        AppContentTarget(
                            kind = AppContentKind.DETAIL,
                            tab = selectedTab,
                            homeSection = homeSection,
                            detailDepth = detailStack.size - 1,
                            detail = detailStack[detailStack.size - 2],
                        )
                    } else {
                        AppContentTarget(
                            kind = AppContentKind.TAB,
                            tab = selectedTab,
                            homeSection = homeSection,
                            detailDepth = 0,
                        )
                    }
                }
                else -> null
            }
        }

        PredictiveBackHandler(enabled = canHandleAppBack) { progress ->
            predictiveBackTarget = getPreviousContentTarget()
            try {
                progress.collect { event ->
                    predictiveBackProgress = event.progress.coerceIn(0f, 1f)
                    predictiveBackSwipeEdge = event.swipeEdge
                }
                val start = if (predictiveBackSettling) predictiveBackProgress else predictiveBackFollow
                predictiveBackSettling = true
                predictiveBackProgress = start
                // Finish moving the whole outgoing page before changing the destination.
                androidx.compose.animation.core.animate(0f, 1f,
                    animationSpec = tween(260, easing = FastOutSlowInEasing)) { fraction, _ ->
                    predictiveBackExit = fraction
                    predictiveBackProgress = start + (1f - start) * fraction
                }
                navigateBackOneLevel()
            } catch (cancelled: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val start = if (predictiveBackSettling) predictiveBackProgress else predictiveBackFollow
                    predictiveBackSettling = true
                    predictiveBackProgress = start
                    androidx.compose.animation.core.animate(start, 0f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ ->
                        predictiveBackProgress = value
                    }
                }
                throw cancelled
            } finally {
                predictiveBackProgress = 0f
                predictiveBackExit = 0f
                predictiveBackSettling = false
                predictiveBackTarget = null
            }
        }

        if (showPlaybackHistory) {
            PlaybackHistorySheet(
                entries = playbackHistoryEntries,
                onSongClick = { song -> playDirect(song) },
                onDismiss = { showPlaybackHistory = false },
            )
        }

        @Composable
        fun RenderAppTargetContent(target: AppContentTarget, preview: Boolean = false) {
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
                    onAccountDeleted = {
                        showOrbProfile = false
                        showAccountIntegrations = false
                        publicProfileUserId = null
                        signOutAndStopPlayback()
                    },
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
                                    // A user who has only signed in to Orb
                                    // needs OAuth consent, not a Google login
                                    // inside an embedded WebView.
                                    val activity = context as? MainActivity
                                    if (activity != null) {
                                        activity.connectYoutubeMusic { result ->
                                            result.onSuccess {
                                                viewModel.onYoutubeIdentityChanged()
                                                scope.launch {
                                                    val channels = OrbGoogleAuth.availableYoutubeIdentities()
                                                    if (channels.isNotEmpty()) youtubeIdentityPicker = channels
                                                }
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    error.message ?: "YouTube authorization failed.",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onSignOut = { signOutAndStopPlayback() },
                    onOpenLastfmLogin = { showLastfmLogin = true },
                    contentPadding = topBarListPadding,
                    scrollState = accountScrollState,
                )
            } else if (target.kind == AppContentKind.SOURCES) {
                SourcesScreen(
                    contentPadding = topBarListPadding,
                    scrollState = sourcesScrollState,
                )
            } else if (target.kind == AppContentKind.SETTINGS) {
                if (showOrbLockSettings) OrbLockSettingsScreen(
                    contentPadding = settingsListPadding,
                ) else SettingsScreen(
                    onSources = {
                        showSettings = false
                        showSources = true
                    },
                    onLyricsSources = { showLyricsSources = true },
                    onOrbLockSettings = { showOrbLockSettings = true },
                    account = displayAccount,
                    contentPadding = settingsListPadding,
                    scrollState = if (OrbFlavorUi.expressive) settingsScrollState else null,
                    highlightPremium = premiumLaunchHighlight,
                )
            } else if (page != null && page.browseId.startsWith("local:")) {
                val localState = page.songs
                val localSongs = (localState as? UiState.Success)
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
                    emptyMessage = (localState as? UiState.Error)
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
                            // The user explicitly opened this release. Never let
                            // "Prioritize album versions" silently jump to a
                            // compilation/reissue (e.g. Bad -> HIStory).
                            preserveAlbumRelease = true,
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
                var offlineCollectionDownloadPending by remember(page.browseId) { mutableStateOf(false) }
                // A download from the album/playlist button is for the collection,
                // including subsequent continuation pages. Preserve that source's
                // track order regardless of when individual transfers finish.
                LaunchedEffect(page.browseId, page.songs, page.continuation, offlineCollectionDownloadPending) {
                    if (offlineCollectionDownloadPending &&
                        (page.type == BrowseType.PLAYLIST || page.type == BrowseType.ALBUM)
                    ) {
                        val loaded = (page.songs as? UiState.Success)?.data.orEmpty()
                        val previousIds = if (page.type == BrowseType.PLAYLIST) {
                            com.music.orb.download.OfflinePlaylistStore.playlists.value
                                .firstOrNull { it.browseId == page.browseId }?.trackIds.orEmpty()
                        } else {
                            com.music.orb.download.OfflineAlbumStore.albums.value
                                .firstOrNull { it.browseId == page.browseId }?.trackIds.orEmpty()
                        }
                        val recordedIds = previousIds.toSet()
                        val newlyLoaded = loaded.filter { it.videoId.isNotBlank() && it.videoId !in recordedIds }
                        if (newlyLoaded.isNotEmpty()) {
                            val contextual = loaded.map(withAlbum)
                            if (page.type == BrowseType.PLAYLIST) {
                                com.music.orb.download.OfflinePlaylistStore.save(
                                    context, page.browseId, page.title, page.thumbnailUrl, contextual,
                                )
                            } else {
                                com.music.orb.download.OfflineAlbumStore.save(
                                    context, page.browseId, page.title, page.thumbnailUrl, contextual,
                                )
                            }
                            startDownload(newlyLoaded.map(withAlbum))
                        }
                        if (page.continuation == null) offlineCollectionDownloadPending = false
                    }
                }
                DetailContentTheme(enabled = forceDarkDetail) {
                    DetailScreen(
                        page = page,
                        listState = detailListState,
                        onSongClick = playDetail,
                        onSongLongPress = { songActions = withAlbum(it) },
                        onSongSwipe = { song -> onSongSwipe(withAlbum(song)) },
                        onSongAddToQueue = { song -> addToQueue(withAlbum(song)) },
                        onSongPlayNext = { song -> playNext(withAlbum(song)) },
                        onCollectionPlayNext = { songs ->
                            if (roomAllowsQueueReplacement(allowPersonalPlayback = false)) {
                                scope.launch {
                                    val resolved = songs.map(withAlbum).filterNot(viewModel::shouldAvoidPlayback)
                                        .map { original ->
                                            runCatching { YtMusicRepository.resolvePreferredPlaybackVersion(original) }
                                                .getOrDefault(original)
                                        }.filterNot(viewModel::shouldAvoidPlayback)
                                    if (resolved.isNotEmpty()) {
                                        controller?.let { player ->
                                            player.addMediaItems(
                                                (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount),
                                                resolved.map { it.copy(queuePinned = true).toMediaItem() },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onCollectionAddToQueue = { songs ->
                            scope.launch {
                                val resolved = songs.map(withAlbum).filterNot(viewModel::shouldAvoidPlayback)
                                    .map { original ->
                                        runCatching { YtMusicRepository.resolvePreferredPlaybackVersion(original) }
                                            .getOrDefault(original)
                                    }.filterNot(viewModel::shouldAvoidPlayback)
                                if (resolved.isNotEmpty()) {
                                    controller?.let { player ->
                                        player.addMediaItems(
                                            player.autoplaySectionStart(),
                                            resolved.map { it.toMediaItem() },
                                        )
                                    }
                                }
                            }
                        },
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
                        onDownloadAll = { songs ->
                            val contextualSongs = songs.map(withAlbum)
                            if (page.type == BrowseType.ALBUM && !page.browseId.startsWith("local:")) {
                                com.music.orb.download.OfflineAlbumStore.save(
                                    context = context,
                                    browseId = page.browseId,
                                    title = page.title,
                                    artworkUrl = page.thumbnailUrl,
                                    songs = contextualSongs,
                                )
                            }
                            startDownload(contextualSongs)
                            if (page.type == BrowseType.ALBUM) {
                                offlineCollectionDownloadPending = page.continuation != null
                            }
                        },
                        onDownloadPlaylist = if (page.type == BrowseType.PLAYLIST && !page.browseId.startsWith("local:")) {
                            { songs ->
                                val contextualSongs = songs.map(withAlbum)
                                com.music.orb.download.OfflinePlaylistStore.save(
                                    context = context,
                                    browseId = page.browseId,
                                    title = page.title,
                                    artworkUrl = page.thumbnailUrl,
                                    songs = contextualSongs,
                                )
                                startDownload(contextualSongs)
                                offlineCollectionDownloadPending = page.continuation != null
                            }
                        } else null,
                        onArtistClick = { id, name ->
                            scope.launch {
                                val validated = withTimeoutOrNull(18_000L) {
                                    YtMusicRepository.resolveVerifiedArtistLink(name, id)
                                }
                                if (validated == null) {
                                    Toast.makeText(context, "Não foi possível confirmar o artista $name", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.openDetail(validated.artistId, validated.name, "Artist", null, BrowseType.ARTIST)
                                }
                            }
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
                        albumPlaybackBlocked = page.type == BrowseType.ALBUM &&
                                viewModel.isAlbumPlaybackBlocked(page.browseId),
                        onToggleAlbumPlaybackBlock = if (page.type == BrowseType.ALBUM) {
                            {
                                OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                                viewModel.setAlbumPlaybackBlocked(
                                    page.browseId,
                                    (page.songs as? UiState.Success)?.data.orEmpty(),
                                    blocked = !viewModel.isAlbumPlaybackBlocked(page.browseId),
                                )
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
                    showSpotifyLibrary = target.homeSection == 3,
                    state = when (target.homeSection) {
                        1 -> buildAlbumsState(
                            releasesHubState,
                            libraryState,
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
                            catalogueSections = distinctHomeShelves(
                                (homeState as? UiState.Success)?.data.orEmpty() +
                                    homeYouTubeSections,
                            ),
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
                    showSearchField = !OrbFlavorUi.expressive,
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
                            searchQuery = query.takeIf { item.type == BrowseType.ARTIST },
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
                            searchQuery = query.takeIf { item.type == BrowseType.ARTIST },
                        )
                    },
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
                        when {
                            !item.videoId.isNullOrBlank() -> {
                                // Reuse the same track sheet as Home and Explore.
                                songActions = item.asSong()
                            }
                            !item.browseId.isNullOrBlank() &&
                                (item.type == BrowseType.ARTIST || item.browseId.startsWith("local:")) -> {
                                viewModel.openDetail(
                                    browseId = requireNotNull(item.browseId),
                                    title = item.title,
                                    subtitle = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    type = item.type,
                                    isExplicit = item.isExplicit,
                                )
                            }
                            !item.browseId.isNullOrBlank() -> {
                                // Every album/playlist card opens the SAME collection
                                // sheet as the corresponding detail page from Home.
                                // Editing an owned playlist is an optional action there,
                                // not a separate long-press sheet with different motion.
                                libraryCollectionActions = item
                            }
                        }
                    },
                    onNewPlaylist = { creatingPlaylist = true },
                    onCreateRoom = { if (SocialRepository.currentUserId() == null) requestGoogleSignIn() else roomAction = "create" },
                    onJoinRoom = { if (SocialRepository.currentUserId() == null) requestGoogleSignIn() else roomAction = "join" },
                    onOpenRoom = { roomAction = "join-animation" },
                    onSignIn = { requestGoogleSignIn() },
                    onRetry = viewModel::loadLibrary,
                    refreshing = MainViewModel.Feed.LIBRARY in refreshing,
                    onRefresh = { viewModel.refresh(MainViewModel.Feed.LIBRARY) },
                    pullState = libraryPull,
                    contentPadding = listPadding,
                )
                TAB_FRIENDS -> FriendsScreen(
                    listState = if (preview) rememberLazyListState() else friendsListState,
                    previewOverview = target.statsOverview,
                    isBackPreview = preview,
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

        @Composable
        fun androidx.compose.foundation.layout.BoxScope.RenderAppTargetHeader(
            target: AppContentTarget,
            headerHazeState: HazeState,
        ) {
            val isTargetDetail = target.kind == AppContentKind.DETAIL
            val isTargetProfile = target.kind == AppContentKind.PUBLIC_PROFILE || target.kind == AppContentKind.ORB_PROFILE
            val topFadeBlurIntensity = when {
                isTargetProfile -> profileTopBlurIntensity
                isTargetDetail -> detailTopBlurIntensity
                else -> 0f
            }
            if (topFadeBlurIntensity > 0f) {
                TopFadeBlur(
                    hazeState = headerHazeState,
                    pageColor = if (isTargetProfile) profileTopBarInfo.pageColor else detailPalette.wash,
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
                    isTargetDetail -> MaterialTheme.colorScheme.copy(
                        primary = detailPalette.onBackground,
                        onBackground = detailPalette.onBackground,
                        onSurface = detailPalette.onBackground,
                        onSurfaceVariant = detailPalette.onBackgroundVariant,
                    )
                    isTargetProfile -> MaterialTheme.colorScheme.copy(
                        onBackground = profileTopBarInfo.contentColor,
                        onSurface = profileTopBarInfo.contentColor,
                        onSurfaceVariant = profileTopBarInfo.contentVariantColor,
                    )
                    else -> MaterialTheme.colorScheme
                },
            ) {
                val useExpandedHomeHeader = OrbFlavorUi.expressive && target.kind == AppContentKind.TAB && target.tab == TAB_HOME
                val useCompactExploreHeader = OrbFlavorUi.expressive && target.kind == AppContentKind.TAB && target.tab == TAB_EXPLORE
                val useLibraryHeader = OrbFlavorUi.expressive && target.kind == AppContentKind.TAB && target.tab == TAB_LIBRARY
                val useStatsHeader = OrbFlavorUi.expressive && target.kind == AppContentKind.TAB && target.tab == TAB_FRIENDS
                var homeGlassRecovering by remember { mutableStateOf(false) }
                LaunchedEffect(useExpandedHomeHeader) {
                    if (useExpandedHomeHeader) {
                        homeGlassRecovering = true
                        delay(300L)
                    }
                    homeGlassRecovering = false
                }

                // Preserve the same Home header instance across Settings/detail navigation.
                // The active header keeps its animated size, avatar and Haze nodes.
                if (OrbFlavorUi.expressive) HomeFrostedHeader(
                        account = displayAccount,
                        hazeState = headerHazeState,
                        scrolled = homeHeaderCollapsedDuringSectionTransition ?: (
                            homeListStates[target.homeSection.coerceIn(0, homeListStates.lastIndex)]
                                .let { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 24 }
                        ),
                        selectedSection = target.homeSection,
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

                                homeSectionTransitionGeneration += 1
                                val transitionGeneration = homeSectionTransitionGeneration
                                homeHeaderCollapsedDuringSectionTransition = keepCollapsed
                                homeSection = section

                                scope.launch {
                                    if (keepCollapsed && targetNeedsCollapsePosition) {
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
                        glassRecovering = homeGlassRecovering,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer {
                                // Retain this exact Compose node while inactive, but move it
                                // well outside the touch and render bounds of other pages.
                                translationX = if (useExpandedHomeHeader) 0f else -size.width * 2f
                            },
                    )
                if (!useExpandedHomeHeader) {
                if (useCompactExploreHeader) {
                    ExploreFrostedHeader(
                        account = displayAccount,
                        hazeState = headerHazeState,
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
                        onPeopleSearchClick = {
                            // Open Stats' user search directly from Explore. The music
                            // search field remains dedicated to songs, albums and artists.
                            if (artistRankingOpen) friendsViewModel.closeArtistRanking()
                            if (statsNotificationsOpen) friendsViewModel.closeNotifications()
                            friendsViewModel.openUserSearch()
                            if (selectedTab != TAB_FRIENDS) {
                                tabBackStack = (tabBackStack + selectedTab).takeLast(MAX_TAB_BACK_HISTORY)
                                selectedTab = TAB_FRIENDS
                            }
                        },
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
                        hazeState = headerHazeState,
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
                        hazeState = headerHazeState,
                        title = tabFriends,
                        searchTitle = when {
                            statsNotificationsOpen -> stringResource(R.string.stats_notifications_title)
                            artistRankingOpen -> stringResource(R.string.stats_artist_ranking_title)
                            else -> stringResource(R.string.stats_user_search_title)
                        },
                        searchMode = !target.statsOverview && (friendsSearchOpen || artistRankingOpen || statsNotificationsOpen),
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
                        showPeriodSelector = target.statsOverview || (!friendsSearchOpen && !statsNotificationsOpen),
                        onHistoryClick = { showPlaybackHistory = true },
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
                        title = when (target.kind) {
                            AppContentKind.ORB_PROFILE, AppContentKind.PUBLIC_PROFILE -> profileTopBarInfo.title.ifBlank { titleOrbProfileText }
                            AppContentKind.ACCOUNT -> titleAccountText
                            AppContentKind.SOURCES -> titleAudioSourcesText
                            AppContentKind.SETTINGS -> if (showOrbLockSettings) stringResource(R.string.settings_orb_lockscreen) else titleSettingsText
                            AppContentKind.DETAIL -> target.detail?.title ?: ""
                            else -> tabs[target.tab].let {
                                if (it.label == tabPlay) titleListenNow else it.label
                            }
                        },
                        hazeState = headerHazeState,
                        subtitle = if (isTargetProfile) profileTopBarInfo.subtitle else null,
                        ownBackdrop = !isTargetProfile && (target.detail == null || target.detail.browseId.startsWith("local:")),
                        alwaysShowTitle = isTargetDetail && target.detail?.browseId?.startsWith("local:") == true,
                        showBrandingWhenNoBack = !isTargetProfile && !(
                            isTargetDetail && target.detail?.type in setOf(
                                BrowseType.ALBUM, BrowseType.PLAYLIST, BrowseType.ARTIST,
                            )
                        ),
                        scrolled = when {
                            isTargetProfile -> profileScrolled
                            target.kind == AppContentKind.SETTINGS || target.kind == AppContentKind.SOURCES || target.kind == AppContentKind.ACCOUNT -> true
                            isTargetDetail -> detailScrolled
                            else -> scrolled
                        },
                        refreshing = when {
                            isTargetProfile -> false
                            isTargetDetail -> detailRefreshing
                            target.kind == AppContentKind.TAB && target.tab == TAB_FRIENDS -> friendsRefreshing
                            else -> currentFeed != null && currentFeed in refreshing
                        },
                        pullFraction = {
                            when {
                                isTargetProfile -> 0f
                                isTargetDetail -> detailPull.distanceFraction
                                target.kind == AppContentKind.TAB && target.tab == TAB_FRIENDS -> friendsPull.distanceFraction
                                else -> currentPull?.distanceFraction ?: 0f
                            }
                        },
                        onBack = when {
                            isTargetProfile -> null
                            target.kind == AppContentKind.ACCOUNT -> ({ showAccountIntegrations = false })
                            target.kind == AppContentKind.SOURCES -> ({ showSources = false; showSettings = true })
                            target.kind == AppContentKind.SETTINGS -> ({
                                if (showOrbLockSettings) showOrbLockSettings = false
                                else showSettings = false
                            })
                            isTargetDetail && target.detail?.type in setOf(
                                BrowseType.ALBUM, BrowseType.PLAYLIST, BrowseType.ARTIST,
                            ) -> null
                            isTargetDetail -> ({ viewModel.closeDetail(); Unit })
                            else -> null
                        },
                        plainBackButton = isTargetDetail,
                        modifier = Modifier.align(Alignment.TopCenter),
                        actions = {
                            if (target.kind == AppContentKind.TAB && target.tab == TAB_HOME) {
                                val readyUpdate = automaticUpdateReady
                                if (readyUpdate != null) {
                                    androidx.compose.material3.IconButton(
                                        onClick = { showAutomaticUpdateDialog = true },
                                    ) {
                                        Icon(
                                            Icons.Rounded.SystemUpdate,
                                            contentDescription = stringResource(
                                                R.string.beta_updates_in_app_icon_description,
                                                readyUpdate.displayName,
                                            ),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else if (automaticUpdateAvailable != null) {
                                    val availableUpdate = automaticUpdateAvailable!!
                                    androidx.compose.material3.IconButton(
                                        onClick = { showAutomaticUpdateDialog = true },
                                    ) {
                                        Icon(
                                            Icons.Rounded.SystemUpdate,
                                            contentDescription = stringResource(
                                                R.string.beta_updates_in_app_icon_description,
                                                availableUpdate.displayName,
                                            ),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    updateNotice?.let { update ->
                                        androidx.compose.material3.IconButton(
                                            onClick = { showUpdateDialog = true },
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
                            if (!OrbFlavorUi.expressive && target.kind == AppContentKind.TAB && target.tab == TAB_HOME) {
                                com.music.orb.ui.components.OrbCastButton()
                                androidx.compose.material3.IconButton(onClick = { showSettings = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = titleSettingsText,
                                    )
                                }
                            }
                            if (target.kind == AppContentKind.TAB) {
                                TopBarAccountButton(
                                    account = displayAccount,
                                    onClick = { showAccountIntegrations = true },
                                )
                            }
                        },
                    )
                }
                } // Other destinations render above the retained Home header.
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

        val isPredictiveBackActive = predictiveBackTarget != null

        val edgeDirection = if (predictiveBackSwipeEdge == 0) 1f else -1f
        val pageMotion = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = if (isPredictiveBackActive) predictiveBackVisual else 0f

                // IMPORTANT: do not scale the subtree that contains both hazeSource()
                // and hazeEffect(). Haze calculates capture/effect geometry from layout
                // coordinates, while scaleX/scaleY is a post-layout RenderNode transform.
                // During a continuously changing predictive-back scale that mismatch can
                // make the bottom blur re-sample narrow stale strips of the page, which
                // is exactly the visible "slicing" behind the navigation bar.
                //
                // Predictive back is preserved through horizontal translation, rounded
                // clipping, shadow/depth, and a full-size destination underneath.
                // The current Haze surface itself stays at 1:1 so source and effects always
                // share a stable coordinate space.
                scaleX = 1f
                scaleY = 1f
                val dragTravel = progress * 64.dp.toPx()
                translationX = (dragTravel + (size.width - dragTravel) * predictiveBackExit) * edgeDirection

                val radius = 28.dp * progress
                shape = RoundedCornerShape(radius)
                clip = progress > 0.01f
                shadowElevation = 16.dp.toPx() * progress

                // Let Haze record its source layers directly. A forced offscreen
                // buffer here isolates the live backdrop used by the glass overlays.
                compositingStrategy = CompositingStrategy.Auto
            }

        OrbAppSurface(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            OrbAppSurface(Modifier.fillMaxSize().hazeSource(fixedControlsHazeState)) {
                val bgTarget = predictiveBackTarget
                if (isPredictiveBackActive && bgTarget != null) {
                    val isBgDetail = bgTarget.kind == AppContentKind.DETAIL
                    val isBgProfile = bgTarget.kind == AppContentKind.PUBLIC_PROFILE || bgTarget.kind == AppContentKind.ORB_PROFILE
                    val bgPageColor = when {
                        isBgDetail -> detailPalette.background
                        isBgProfile -> profileTopBarInfo.pageColor
                        else -> MaterialTheme.colorScheme.background
                    }

                    OrbAppSurface(
                        Modifier
                            .fillMaxSize()
                            .background(bgPageColor)
                    ) {
                        OrbAppSurface(Modifier.fillMaxSize().hazeSource(predictiveBackPreviewHazeState)) {
                            RenderAppTargetContent(bgTarget, preview = true)
                        }
                        RenderAppTargetHeader(bgTarget, predictiveBackPreviewHazeState)
                    }
                }

                OrbAppSurface(pageMotion                ) {
                    AnimatedContent(
                        targetState = contentTarget,
                        // Every destination reuses a single full-screen Haze surface. Concurrent
                        // outgoing/incoming sources split blur sampling across page/header layers.
                        contentKey = { "app-page-surface" },
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(tween(180)) togetherWith fadeOut(tween(140))).using(null)
                        },
                        label = "content",
                    ) { target ->
                        val isTargetDetail = target.kind == AppContentKind.DETAIL
                        val isTargetProfile = target.kind == AppContentKind.PUBLIC_PROFILE || target.kind == AppContentKind.ORB_PROFILE
                        val targetPageColor = when {
                            isTargetDetail -> detailPalette.background
                            isTargetProfile -> profileTopBarInfo.pageColor
                            else -> MaterialTheme.colorScheme.background
                        }

                        OrbAppSurface(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            OrbAppSurface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hazeSource(hazeState)
                                    .background(targetPageColor)
                            ) {
                                CompositionLocalProvider(LocalOrbNavigationGlassHazeState provides hazeState) {
                                    RenderAppTargetContent(target)
                                }
                            }

                        }
                    }

                } // Moving page and header share the same transform.
            } // Raw page capture in fixed window coordinates, before header/bottom glass.

            OrbAppSurface(pageMotion) {
                CompositionLocalProvider(LocalOrbNavigationGlassHazeState provides hazeState) {
                    RenderAppTargetHeader(contentTarget, hazeState)
                }
            }

            // Rendered after the scrollable page AND the frozen header, outside
            // both layouts. Refresh remains handled by each PullToRefreshBox;
            // this camera-anchored overlay never moves or resizes the content.
            val overlayPullState = when (contentTarget.kind) {
                AppContentKind.DETAIL -> detailPull
                AppContentKind.TAB -> when (contentTarget.tab) {
                    TAB_HOME -> homePull
                    TAB_EXPLORE -> explorePull
                    TAB_LIBRARY -> libraryPull
                    TAB_FRIENDS -> friendsPull
                    else -> null
                }
                else -> null
            }
            val overlayRefreshing = when (contentTarget.kind) {
                AppContentKind.DETAIL -> detailRefreshing
                AppContentKind.TAB -> when (contentTarget.tab) {
                    TAB_HOME -> currentFeed != null && currentFeed in refreshing
                    TAB_EXPLORE -> if (query.isBlank()) {
                        MainViewModel.Feed.DISCOVER in refreshing
                    } else searchRefreshing
                    TAB_LIBRARY -> MainViewModel.Feed.LIBRARY in refreshing
                    TAB_FRIENDS -> friendsRefreshing
                    else -> false
                }
                else -> false
            }
            if (!showNowPlaying && !showPlaybackHistory && !isPredictiveBackActive && overlayPullState != null) {
                OrbRefreshDroplet(
                    refreshing = overlayRefreshing,
                    pullFraction = { overlayPullState.distanceFraction },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            BottomFadeBlur(
                hazeState = fixedControlsHazeState,
                withMiniPlayer = miniPlayerSong != null,
                pageColor = if (isDetailVisible) detailPalette.background else MaterialTheme.colorScheme.background,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Keep both controls on the same centered width in tablet landscape.
                    .fillMaxWidth(if (rootIsTabletLandscape) 0.82f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                miniPlayerSong?.let { song ->
                    MiniPlayer(
                        song = song,
                        isPlaying = player.isPlaying,
                        isLoading = player.isLoading || false,
                        hazeState = fixedControlsHazeState,
                        onPlayPause = {
                            if (!false) {
                                controller?.let {
                                    if (com.music.orb.data.rooms.RoomRepository.isGuest()) {
                                        if(com.music.orb.data.rooms.RoomSession.guestPaused.value) it.play() else it.pause()
                                    } else if (it.isPlaying) it.pause() else it.play()
                                }
                            }
                        },
                        onNext = {
                            if (!false && !com.music.orb.data.rooms.RoomRepository.isGuest()) controller?.seekToNextMediaItem()
                        },
                        onExpand = {
                            miniPlayerBounds?.let {
                                NowPlayingLaunchOriginRegistry.record(
                                    it,
                                    NowPlayingLaunchOriginKind.MINI_PLAYER,
                                )
                            }
                            touchTransferCollapseNowPlayingToken = 0
                            val expandingSong = nowPlayingSong ?: song
                            retainedNowPlayingSong = expandingSong
                            prepareNowPlayingLaunch(expandingSong)
                            showNowPlaying = true
                        },
                        onClearQueue = {
                            if (com.music.orb.data.rooms.RoomRepository.playbackRoom.value != null) {
                                com.music.orb.data.rooms.RoomRepository.viewPlayback()
                                roomAction = "room"
                            } else {
                                AutoplaySession.pauseAfterClearingQueue()
                                controller?.clearPlaybackQueue()
                                pendingNowPlayingSong = null
                                pendingPlaybackCommitted = false
                                retainedNowPlayingSong = null
                            }
                        },
                        controlsEnabled = !false && listeningRoom?.host != false,
                        controlsVisible = true,
                        onBoundsChanged = { miniPlayerBounds = it },
                        onArtworkBoundsChanged = { miniPlayerArtworkBounds = it },
                        onIdentityBoundsChanged = { miniPlayerIdentityBounds = it },
                        onPlayPauseBoundsChanged = { miniPlayerPlayPauseBounds = it },
                        onNextBoundsChanged = { miniPlayerNextBounds = it },
                        contentVisible = !showNowPlaying ||
                            (nowPlayingLaunchVisual?.originKind == NowPlayingLaunchOriginKind.MINI_PLAYER &&
                                (nowPlayingArtworkBounds == null || nowPlayingIdentityBounds == null ||
                                    (listeningRoom?.host != false && (nowPlayingPlayPauseBounds == null || nowPlayingNextBounds == null)) ||
                                    nowPlayingIdentityAppearance == null || !nowPlayingArtworkReady)),
                        artworkVisible = !(touchTransferVisual?.direction == TouchTransferDirection.OUTGOING &&
                            touchTransferVisual?.phase == TouchTransferPhase.TRANSFERRING &&
                            touchTransferVisual?.artworkOrigin != null),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // The shared host owns all visible pixels until dismissal.
                                alpha = if (showNowPlaying) {
                                    val expansion = if (nowPlayingLaunchVisual?.originKind ==
                                        NowPlayingLaunchOriginKind.MINI_PLAYER) nowPlayingLaunchProgress
                                        else 1f - nowPlayingSheetDismissProgress
                                    1f - ((expansion / 0.06f).coerceIn(0f, 1f))
                                } else 1f
                            }
                            .alpha(if (touchTransferIncomingLanding) 0f else 1f),
                    )
                }
                FloatingBottomBar(
                    tabs = tabs,
                    selectedIndex = selectedTab,
                    hazeState = fixedControlsHazeState,
                    notificationTabIndex = TAB_FRIENDS,
                    showNotificationDot = statsNotificationUnread > 0 && selectedTab != TAB_FRIENDS,
                    onTabSelected = { index ->
                        // Re-selecting the currently visible root destination behaves like
                        // modern tabbed apps: keep the same page and smoothly return its
                        // retained LazyListState to the top.
                        val reselectingVisibleRoot =
                            index == selectedTab &&
                                detail == null &&
                                !showSettings &&
                                !showSources &&
                                !showAccountIntegrations &&
                                !showOrbProfile &&
                                publicProfileUserId == null &&
                                !showPlaybackHistory &&
                                !(selectedTab == TAB_FRIENDS &&
                                    (friendsSearchOpen || artistRankingOpen || statsNotificationsOpen))

                        if (reselectingVisibleRoot) {
                            scope.launch {
                                val state = when (index) {
                                    TAB_HOME -> homeListState
                                    TAB_EXPLORE -> searchListState
                                    TAB_LIBRARY -> libraryListState
                                    TAB_FRIENDS -> friendsListState
                                    else -> null
                                }
                                if (
                                    state != null &&
                                    (state.firstVisibleItemIndex != 0 ||
                                        state.firstVisibleItemScrollOffset != 0)
                                ) {
                                    state.animateScrollToItem(0)
                                }
                            }
                        }

                        if (index != TAB_EXPLORE) {
                            searchFocusTrigger = 0
                        }
                        if (index != TAB_FRIENDS) {
                            if (friendsSearchOpen) friendsViewModel.closeUserSearch()
                            if (statsNotificationsOpen) friendsViewModel.closeNotifications()
                            if (artistRankingOpen) friendsViewModel.closeArtistRanking()
                        }

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
        }


        if (showNowPlaying) {
            var nowPlayingLyricsOpen by rememberSaveable { mutableStateOf(false) }
            var nowPlayingQueueOpen by rememberSaveable { mutableStateOf(false) }

            // The player remains in the activity coordinate space. A second modal
            // window would add its own slide animation and interrupt the shared path.
            var motionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            fun settlePlayer(hidden: Boolean, velocity: Float = 0f) {
                motionJob?.cancel()
                motionJob = scope.launch {
                    animate(
                        initialValue = nowPlayingSheetDismissProgress,
                        targetValue = if (hidden) 1f else 0f,
                        initialVelocity = velocity / appRootSize.height.coerceAtLeast(1),
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) { value, _ -> nowPlayingSheetDismissProgress = value }
                    if (hidden) {
                        showNowPlaying = false
                        nowPlayingSheetDismissProgress = 0f
                    }
                }
            }
            LaunchedEffect(touchTransferCollapseNowPlayingToken) {
                if (touchTransferCollapseNowPlayingToken > 0) {
                    settlePlayer(true)
                    touchTransferCollapseNowPlayingToken = 0
                }
            }
            val dismissNowPlayingToMini: () -> Unit = { settlePlayer(true) }
            BackHandler(enabled = songActions == null) {
                when {
                    nowPlayingLyricsOpen -> nowPlayingLyricsOpen = false
                    nowPlayingQueueOpen -> nowPlayingQueueOpen = false
                    else -> dismissNowPlayingToMini()
                }
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

                OrbAppSurface(
                    Modifier.fillMaxSize().zIndex(100f)
                        .draggable(
                            orientation = Orientation.Vertical,
                            enabled = !nowPlayingLyricsOpen && !nowPlayingQueueOpen &&
                                songActions == null && nowPlayingLaunchVisual == null,
                            state = rememberDraggableState { delta ->
                                nowPlayingSheetDismissProgress =
                                    (nowPlayingSheetDismissProgress +
                                        delta / (appRootSize.height * 0.72f).coerceAtLeast(1f))
                                        .coerceIn(0f, 1f)
                            },
                            onDragStarted = { motionJob?.cancel() },
                            onDragStopped = { velocity ->
                                settlePlayer(
                                    hidden = velocity > 1100f ||
                                        (velocity > -1100f && nowPlayingSheetDismissProgress > 0.38f),
                                    velocity = velocity,
                                )
                            },
                        ),
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
                    val actionSheetBackdropActive = songActions != null || openModalSheets > 0
                    val actionSheetBackdropBlur by animateDpAsState(
                        targetValue = if (touchTransferBlurActive) 36.4.dp
                            else if (actionSheetBackdropActive) { if (rootIsTabletLandscape) tabletSheetBlur else 52.dp } else 0.dp,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        label = "now-playing-action-sheet-blur",
                    )
                    // The fixed sheet host already paints the single 10% scrim
                    // across its entire exposed backdrop, including artwork and disc.
                    // Do not add another dark overlay to Now Playing here: a 50%
                    // layer combined with the sheet's scrim made the cover/title far
                    // darker than the rest of the screen.

                    OrbAppSurface(Modifier.fillMaxSize()) {
                        val collapseProgress = nowPlayingSheetDismissProgress.coerceIn(0f, 1f)
                        val miniLaunch = nowPlayingLaunchVisual?.originKind ==
                            NowPlayingLaunchOriginKind.MINI_PLAYER
                        val expansion = if (miniLaunch) nowPlayingLaunchProgress
                            else 1f - collapseProgress
                        val miniSurface = miniPlayerBounds
                        val motionActive = miniLaunch || collapseProgress > 0f
                        val motionDensity = LocalDensity.current
                        val cornerPx = with(motionDensity) { 26.dp.toPx() }
                        val surfaceClip = GenericShape { size, _ ->
                            val mini = miniSurface ?: Rect(0f, 0f, size.width, size.height)
                            val rect = Rect(
                                mini.left * (1f - expansion),
                                mini.top * (1f - expansion),
                                mini.right + (size.width - mini.right) * expansion,
                                mini.bottom + (size.height - mini.bottom) * expansion,
                            )
                            val radius = cornerPx * (1f - expansion)
                            addRoundRect(RoundRect(rect, CornerRadius(radius, radius)))
                        }
                        OrbAppSurface(
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (motionActive) (expansion / 0.06f).coerceIn(0f, 1f) else 1f
                                }
                                .clip(surfaceClip)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .blur(actionSheetBackdropBlur, BlurredEdgeTreatment.Rectangle),
                        ) {
                            NowPlayingScreen(
                                args = NowPlayingArguments(
                                albumSequential = player.albumSequential,
                                showTransportControls = true,
                                roomQueue = listeningRoom != null,
                                controlledByRoomTitle = listeningRoom?.takeIf { !it.host }?.title,
                                song = song,
                                transitionFromSong = visualFromSong,
                                trackTransitionProgress = automixVisualProgress,
                                isPlaying = player.isPlaying && pendingNowPlayingSong == null,
                                isLoading = player.isLoading || pendingNowPlayingSong != null || false,
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
                                        controller?.let {
                                    if (com.music.orb.data.rooms.RoomRepository.isGuest()) {
                                        if(com.music.orb.data.rooms.RoomSession.guestPaused.value) it.play() else it.pause()
                                    } else if (it.isPlaying) it.pause() else it.play()
                                }
                                    }
                                },
                                onNext = {
                                    if (pendingNowPlayingSong == null && !false && !com.music.orb.data.rooms.RoomRepository.isGuest()) {
                                        controller?.seekToNextMediaItem()
                                    }
                                },
                                onPrevious = {
                                    if (pendingNowPlayingSong == null && !false && !com.music.orb.data.rooms.RoomRepository.isGuest()) {
                                        controller?.seekToPrevious()
                                    }
                                },
                                onSeekFraction = { fraction ->
                                    if (pendingNowPlayingSong == null && !com.music.orb.data.rooms.RoomRepository.isGuest()) controller?.let { player ->
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
                                    if (pendingNowPlayingSong == null && !com.music.orb.data.rooms.RoomRepository.isGuest()) controller?.let { player ->
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
                                shuffleEnabled = if(listeningRoom != null) listeningRoom?.id?.let {it in roomShuffleIds} == true else shuffleEnabled,
                                autoplayEnabled = autoplay,
                                signedIn = signedIn,
                                likeStatus = viewModel.likeStatusOf(song, likeStatuses),
                                onToggleLike = {
                                    OrbHaptics.perform(context, OrbHaptics.Kind.LIKE)
                                    viewModel.toggleLike(song)
                                },
                                onToggleShuffle = {
                                    val activeRoom = com.music.orb.data.rooms.RoomRepository.playbackRoom.value
                                    if(activeRoom==null) controller?.let(QueueShuffle::toggle)
                                    else if(activeRoom.host) scope.launch {
                                        runCatching {
                                            com.music.orb.data.rooms.RoomRepository.shufflePlaybackRoom(activeRoom.id !in roomShuffleIds)
                                        }.onFailure { android.widget.Toast.makeText(context,R.string.rooms_error_network,android.widget.Toast.LENGTH_SHORT).show() }
                                    }
                                },
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
                                        AutoplaySession.beginUserSelection()
                                    } else {
                                        controller?.dropAutoplayTracks()
                                    }
                                },
                                onJumpTo = { controller?.seekToDefaultPosition(it) },
                                onRemoveFromQueue = { at ->
                                    val activeRoom=com.music.orb.data.rooms.RoomRepository.playbackRoom.value
                                    if(activeRoom==null) controller?.let { queue ->
                                        if (at in 0 until queue.mediaItemCount) {
                                            val removed = queue.getMediaItemAt(at)
                                            if (removed.fromAutoplay) AutoplaySession.reject(removed.toSong())
                                            queue.removeMediaItem(at)
                                        }
                                    }
                                    else if(activeRoom.host && at in activeRoom.queue.indices) {
                                        val songs=activeRoom.queue.filterIndexed {index,_->index!=at}
                                        scope.launch {
                                            runCatching {com.music.orb.data.rooms.RoomRepository.editPlaybackQueue(activeRoom.id,songs)}
                                                .onFailure {android.widget.Toast.makeText(context,R.string.rooms_error_network,android.widget.Toast.LENGTH_SHORT).show()}
                                        }
                                    }
                                },
                                onMoveInQueue = { from, to ->
                                    val activeRoom = com.music.orb.data.rooms.RoomRepository.playbackRoom.value
                                    controller?.let { c ->
                                        if(activeRoom==null) c.moveMediaItemByUser(from,to)
                                        else if(activeRoom.host && from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
                                            c.moveMediaItem(from,to)
                                            val songs=(0 until c.mediaItemCount).mapNotNull { index ->
                                                val id=c.getMediaItemAt(index).mediaId
                                                activeRoom.queue.firstOrNull {it.videoId==id}
                                            }
                                            scope.launch {
                                                runCatching {com.music.orb.data.rooms.RoomRepository.editPlaybackQueue(activeRoom.id,songs)}
                                                    .onFailure {android.widget.Toast.makeText(context,R.string.rooms_error_network,android.widget.Toast.LENGTH_SHORT).show()}
                                            }
                                        }
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
                                    scope.launch {
                                        val resolved = withTimeoutOrNull(18_000L) {
                                            YtMusicRepository.resolveVerifiedArtistLink(name, id)
                                        }
                                        if (resolved != null) {
                                            showNowPlaying = false
                                            viewModel.openDetail(
                                                resolved.artistId, resolved.name, "Artist", null,
                                                BrowseType.ARTIST,
                                            )
                                        } else {
                                            Toast.makeText(context, "Não foi possível confirmar o artista $name", Toast.LENGTH_SHORT).show()
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
                                    dismissNowPlayingToMini()
                                },
                                onArtworkBoundsChanged = { bounds ->
                                    // Ignore the temporary coordinates while ModalBottomSheet is
                                    // settling from its hidden anchor. Keeping only the final
                                    // geometry avoids driving extra root recompositions during the
                                    // incoming artwork animation and gives the launch bridge one
                                    // stable destination rect.
                                    if (
                                        nowPlayingSheetDismissProgress <= 0.01f &&
                                        bounds.width > 1f && bounds.height > 1f
                                    ) {
                                        nowPlayingArtworkBounds = bounds
                                    }
                                },
                                onIdentityBoundsChanged = { bounds ->
                                    if (
                                        nowPlayingSheetDismissProgress <= 0.01f &&
                                        bounds.width > 1f && bounds.height > 1f
                                    ) {
                                        nowPlayingIdentityBounds = bounds
                                    }
                                },
                                onArtworkReadyChanged = { nowPlayingArtworkReady = it },
                                onIdentityAppearanceChanged = { appearance ->
                                    nowPlayingIdentityAppearance = appearance
                                },
                                transportEnabled = listeningRoom?.host != false,
                                launchProgress = if (motionActive) expansion else if (nowPlayingLaunchVisual != null) {
                                    nowPlayingLaunchProgress
                                } else 1f,
                                collapseProgress = 0f,
                                collapseSheetOffsetPx = 0f,
                                miniArtworkBounds = miniPlayerArtworkBounds,
                                miniPlayPauseBounds = miniPlayerPlayPauseBounds,
                                miniNextBounds = miniPlayerNextBounds,
                                onPlayPauseBoundsChanged = { nowPlayingPlayPauseBounds = it },
                                onNextBoundsChanged = { nowPlayingNextBounds = it },
                                onClearQueue = {
                                    AutoplaySession.pauseAfterClearingQueue()
                                    controller?.clearQueueKeepingCurrent()
                                },
                                ),
                            )
                        }
                        if (collapseProgress > 0f && miniSurface != null) {
                            NowPlayingLaunchOverlay(
                                visual = NowPlayingLaunchVisual(
                                    token = nowPlayingLaunchToken,
                                    song = song,
                                    origin = miniSurface,
                                    originKind = NowPlayingLaunchOriginKind.MINI_PLAYER,
                                ),
                                launchProgress = expansion,
                                rootSize = appRootSize,
                                sourceArtworkBounds = miniPlayerArtworkBounds,
                                sourceIdentityBounds = miniPlayerIdentityBounds,
                                sourcePlayPauseBounds = miniPlayerPlayPauseBounds,
                                sourceNextBounds = miniPlayerNextBounds,
                                targetArtworkBounds = nowPlayingArtworkBounds,
                                targetIdentityBounds = nowPlayingIdentityBounds,
                                targetIdentityAppearance = nowPlayingIdentityAppearance,
                                bridgeReady = nowPlayingArtworkReady && nowPlayingIdentityAppearance != null &&
                                    nowPlayingArtworkBounds != null && nowPlayingIdentityBounds != null &&
                                    (listeningRoom?.host == false || (nowPlayingPlayPauseBounds != null && nowPlayingNextBounds != null)),
                                targetPlayPauseBounds = nowPlayingPlayPauseBounds,
                                targetNextBounds = nowPlayingNextBounds,
                                isPlaying = player.isPlaying,
                                isLoading = player.isLoading || pendingNowPlayingSong != null,
                                modifier = Modifier.fillMaxSize().zIndex(130f),
                            )
                        }
                        // When Now Playing is being opened from a transferred artwork, draw
                        // the launch bridge in the ModalBottomSheet window itself. The old
                        // implementation drew it in the activity window behind the modal, which
                        // produced a one-frame black/transparent blink during window handoff.
                        nowPlayingLaunchVisual?.let { visual ->
                            NowPlayingLaunchOverlay(
                                visual = visual,
                                launchProgress = nowPlayingLaunchProgress,
                                rootSize = appRootSize,
                                sourceArtworkBounds = miniPlayerArtworkBounds,
                                sourceIdentityBounds = miniPlayerIdentityBounds,
                                sourcePlayPauseBounds = miniPlayerPlayPauseBounds,
                                sourceNextBounds = miniPlayerNextBounds,
                                targetArtworkBounds = nowPlayingArtworkBounds,
                                targetIdentityBounds = nowPlayingIdentityBounds,
                                targetIdentityAppearance = nowPlayingIdentityAppearance,
                                bridgeReady = nowPlayingArtworkReady && nowPlayingIdentityAppearance != null &&
                                    nowPlayingArtworkBounds != null && nowPlayingIdentityBounds != null &&
                                    (listeningRoom?.host == false || (nowPlayingPlayPauseBounds != null && nowPlayingNextBounds != null)),
                                targetPlayPauseBounds = nowPlayingPlayPauseBounds,
                                targetNextBounds = nowPlayingNextBounds,
                                isPlaying = player.isPlaying,
                                isLoading = player.isLoading || pendingNowPlayingSong != null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = -nowPlayingSheetOffsetPx
                                    }
                                    .zIndex(130f),
                            )
                        }

                        OrbNoticeHost(Modifier.align(Alignment.TopCenter))

                    }
                }

            }
        }

        songActions?.let { originalSong ->
            var resolvedActionSong by remember(originalSong.videoId) { mutableStateOf<Song?>(null) }
            var actionLinksLoading by remember(originalSong.videoId) { mutableStateOf(false) }
            LaunchedEffect(originalSong.videoId, originalSong.albumId, originalSong.artistId) {
                if (originalSong.albumId != null && originalSong.artistId != null) return@LaunchedEffect
                actionLinksLoading = true
                try {
                    val extra = YtMusicRepository.trackLinks(originalSong.videoId).getOrNull()
                    val artistLinks = (originalSong.artistLinks + extra?.artistLinks.orEmpty())
                        .filter { it.artistId.isNotBlank() && it.name.isNotBlank() }
                        .distinctBy { it.artistId }
                    val artists = if (originalSong.artistId == null && extra?.artistId == null && artistLinks.isEmpty()) {
                        YtMusicRepository.resolveArtistLinks(originalSong.artist, artistLinks)
                    } else artistLinks
                    resolvedActionSong = originalSong.copy(
                        albumId = originalSong.albumId ?: extra?.albumId,
                        albumName = originalSong.albumName ?: extra?.albumName,
                        artistId = originalSong.artistId ?: extra?.artistId ?: artists.firstOrNull()?.artistId,
                        artistLinks = artists,
                        releaseYear = originalSong.releaseYear ?: extra?.releaseYear,
                    )
                } finally {
                    actionLinksLoading = false
                }
            }
            val song = resolvedActionSong ?: originalSong
            val fromPlayer = showNowPlaying
            var actionSheetClosing by remember(song.videoId) { mutableStateOf(false) }
            val dismissActionSheet: ((() -> Unit)?) -> Unit = { afterDismiss ->
                if (!actionSheetClosing) {
                    actionSheetClosing = true
                    scope.launch {
                        // Let the CD slide fully behind the cover before the modal itself
                        // starts leaving. This keeps the closing choreography physically coherent.
                        delay(680L)
                        if (playlistTarget?.videoId == song.videoId) {
                            playlistTarget = null
                        }
                        if (songActions?.videoId == song.videoId) {
                            songActions = null
                        }
                        afterDismiss?.invoke()
                    }
                }
            }
            val share: () -> Unit = {
                dismissActionSheet { songShareTarget = song }
            }
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                dismissActionSheet {
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
            }
            LaunchedEffect(song.videoId) { viewModel.loadSongMenu(song.videoId, song) }
            val editable = viewModel.editablePlaylist(detail?.browseId)
                ?.takeIf { !fromPlayer && song.setVideoId != null }

            // User-driven dismissal is intentionally limited to system Back and the
            // custom top handle. Back is registered inside the sheet's own dialog window;
            // Activity-level BackHandlers cannot receive it while that window is focused.
            com.music.orb.ui.components.RegisterSheetBackdrop()
            com.music.orb.ui.components.OrbFixedSheetHost(
                // No built-in M3 drag / nested scroll: only the handle drawn
                // by SongActionsSheet is allowed to move the action surface.
                containerColor = Color.Transparent,
                scrimColor = Color.Black.copy(alpha = 0.10f),
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
                    closing = actionSheetClosing,
                    onHandleDismissRequest = { dismissActionSheet(null) },
                    likeStatus = viewModel.likeStatusOf(song, likeStatuses),
                    inLibrary = songMenu?.inLibrary == true,
                    libraryActionAvailable = songMenu != null,
                    onToggleLibrary = {
                        viewModel.toggleSongLibrary(song)
                        dismissActionSheet(null)
                    },
                    onPlayNext = {
                        playNext(song)
                        dismissActionSheet(null)
                    },
                    onAddToQueue = {
                        addToQueue(song)
                        dismissActionSheet(null)
                    },
                    onDownload = {
                        startDownload(listOf(song))
                        dismissActionSheet(null)
                    },
                    onToggleLike = {
                        viewModel.toggleLike(song)
                    },
                    onToggleDislike = {
                        viewModel.toggleDislike(song)
                    },
                    onAddToPlaylist = {
                        // The picker is a sub-page of this same modal. The action list
                        // leaves, but the cover/CD hero remains mounted at the top.
                        viewModel.loadPlaylists()
                        playlistTarget = song
                    },
                    playlistPickerVisible = playlistTarget?.videoId == song.videoId,
                    playlists = playlists,
                    playlistsLoading = playlistsLoading,
                    onPlaylistPick = { playlist ->
                        viewModel.addToPlaylist(playlist, song)
                        // Return to the original action page immediately after adding;
                        // the hero never leaves because the ModalBottomSheet never changed.
                        playlistTarget = null
                    },
                    onPlaylistCreate = { title, privacy ->
                        viewModel.createPlaylist(title, privacy, song)
                        playlistTarget = null
                    },
                    onPlaylistBack = {
                        playlistTarget = null
                    },
                    onRemoveFromPlaylist = editable?.let {
                        {
                            viewModel.removeFromPlaylist(it.browseId, song)
                            dismissActionSheet(null)
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
                        // The song credit may list a duet. Use the name attached
                        // to the clicked endpoint, then verify its actual page.
                        val visibleCredit = song.artist.trim()
                        val linkedName = song.artistLinks.firstOrNull { it.artistId == id }
                            ?.name?.trim().orEmpty()
                        // Do not replace the visible artist (Vanessa da Mata)
                        // with a collaborator (Ben Harper) solely because a
                        // broken metadata endpoint points to that collaborator.
                        val selectedName = linkedName.takeIf { linked ->
                            linked.isNotBlank() && visibleCredit.contains(linked, ignoreCase = true)
                        } ?: visibleCredit
                        dismissActionSheet {
                            scope.launch {
                                val validated = withTimeoutOrNull(18_000L) {
                                    YtMusicRepository.resolveVerifiedArtistLink(selectedName, id)
                                }
                                if (validated == null) {
                                    Toast.makeText(context, "Não foi possível confirmar o artista $selectedName", Toast.LENGTH_SHORT).show()
                                } else {
                                    showNowPlaying = false
                                    viewModel.openDetail(validated.artistId, validated.name, "Artist", null, BrowseType.ARTIST)
                                }
                            }
                        }
                    },
                    allowOfflineDownloadRemoval = !fromPlayer && detail?.browseId == "local:downloads",
                    onDownloadRemoved = if (!fromPlayer && detail?.browseId == "local:downloads") {
                        {
                            viewModel.reloadLocalDetail("local:downloads")
                            dismissActionSheet(null)
                        }
                    } else {
                        null
                    },
                    resolvingLinks = actionLinksLoading || (fromPlayer && linksLoading),
                    showSleepTimer = fromPlayer,
                    onShare = share,
                    onCopyLog = if (fromPlayer && BuildConfig.IS_BETA) {
                        {
                            dismissActionSheet {
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
                        }
                    } else {
                        null
                    },
                )
            }
        }

        roomAction?.let { action ->
            com.music.orb.ui.screens.RoomFlowScreen(
                initialAction = action,
                transferActive = touchTransferBlurActive,
                controller = controller,
                onDismiss = { roomAction = null },
            )
        }

        songShareTarget?.let { song ->
            com.music.orb.ui.components.SongShareSheet(song = song, onDismiss = { songShareTarget = null })
        }

        if (creatingPlaylist || (playlistTarget != null && songActions == null)) {
            val target = playlistTarget
            val dismiss = {
                playlistTarget = null
                creatingPlaylist = false
            }
            com.music.orb.ui.components.RegisterSheetBackdrop()
            val sheetGlassColor = orbOverlayGlassBaseColor()
            com.music.orb.ui.components.OrbModalBottomSheet(
                floatingTitle = stringResource(R.string.orb_sheet_add_to_playlist),
                onDismissRequest = dismiss,
                containerColor = Color.Transparent,
                scrimColor = Color.Black.copy(alpha = 0.10f),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                navigationBarInsetInContent = true,
                dragHandle = null,
            ) {
                com.music.orb.ui.components.OrbGlassSheetSurface(sheetGlassColor, 32.dp) {
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
        }

        libraryCollectionActions?.let { collection ->
            val editableLibraryPlaylist = if (useYouTubeLibrary) {
                viewModel.editablePlaylist(collection.browseId)
            } else null
            com.music.orb.ui.components.LibraryCollectionActionsSheet(
                item = collection,
                onDismiss = { libraryCollectionActions = null },
                onManagePlaylist = editableLibraryPlaylist?.let { editable ->
                    {
                        libraryCollectionActions = null
                        playlistActions = editable
                    }
                },
                onOpenArtist = collection.artistId?.let { artistId ->
                    {
                        libraryCollectionActions = null
                        scope.launch {
                            val selectedName = collection.subtitle
                            val validated = withTimeoutOrNull(18_000L) {
                                YtMusicRepository.resolveVerifiedArtistLink(selectedName, artistId)
                            }
                            if (validated != null) {
                                viewModel.openDetail(
                                    browseId = validated.artistId,
                                    title = validated.name,
                                    subtitle = "Artist",
                                    thumbnailUrl = null,
                                    type = BrowseType.ARTIST,
                                )
                            } else {
                                Toast.makeText(context, "Não foi possível confirmar o artista $selectedName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onDownload = {
                    libraryCollectionActions = null
                    collection.browseId?.let { id ->
                        scope.launch {
                            val tracks = YtMusicRepository.browseSongsForeground(id)
                                .getOrNull()?.songs.orEmpty()
                                .filterNot(viewModel::shouldAvoidPlayback)
                            if (tracks.isNotEmpty()) {
                                val contextual = tracks.map { song ->
                                    if (collection.type == BrowseType.ALBUM ||
                                        id.startsWith("MPRE", ignoreCase = true)
                                    ) song.copy(
                                        albumId = id,
                                        albumName = collection.title,
                                        preserveAlbumRelease = true,
                                    ) else song.copy(
                                        sourcePlaylistId = id,
                                        sourcePlaylistTitle = collection.title,
                                    )
                                }
                                startDownload(contextual)
                            }
                        }
                    }
                },
                onOpen = {
                    libraryCollectionActions = null
                    val id = collection.browseId ?: return@LibraryCollectionActionsSheet
                    viewModel.openDetail(
                        browseId = id,
                        title = collection.title,
                        subtitle = collection.subtitle,
                        thumbnailUrl = collection.thumbnailUrl,
                        type = collection.type,
                        isExplicit = collection.isExplicit,
                    )
                },
                onShare = {
                    libraryCollectionActions = null
                    libraryCollectionShare = collection
                },
                onPlayNext = {
                    libraryCollectionActions = null
                    val id = collection.browseId
                    if (id != null && roomAllowsQueueReplacement(allowPersonalPlayback = false)) {
                        scope.launch {
                            val tracks = YtMusicRepository.browseSongsForeground(id)
                                .getOrNull()?.songs.orEmpty()
                                .filterNot(viewModel::shouldAvoidPlayback)
                            if (tracks.isNotEmpty()) controller?.let { player ->
                                player.addMediaItems(
                                    (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount),
                                    tracks.map { it.copy(queuePinned = true).toMediaItem() },
                                )
                            }
                        }
                    }
                },
                onAddToQueue = {
                    libraryCollectionActions = null
                    val id = collection.browseId
                    if (id != null && roomAllowsQueueReplacement(allowPersonalPlayback = false)) {
                        scope.launch {
                            val tracks = YtMusicRepository.browseSongsForeground(id)
                                .getOrNull()?.songs.orEmpty()
                                .filterNot(viewModel::shouldAvoidPlayback)
                            if (tracks.isNotEmpty()) controller?.let { player ->
                                player.addMediaItems(
                                    player.autoplaySectionStart(),
                                    tracks.map { it.copy(queuePinned = true).toMediaItem() },
                                )
                            }
                        }
                    }
                },
            )
        }
        libraryCollectionShare?.let { collection ->
            val browseId = collection.browseId ?: return@let
            val isPlaylist = collection.type == BrowseType.PLAYLIST ||
                browseId.startsWith("PL") || browseId.startsWith("VL")
            com.music.orb.ui.components.SongShareSheet(
                song = Song(
                    videoId = browseId,
                    title = collection.title,
                    artist = collection.subtitle,
                    thumbnailUrl = collection.thumbnailUrl,
                    isExplicit = collection.isExplicit,
                ),
                shareLinkOverride = if (isPlaylist)
                    "https://music.youtube.com/playlist?list=${android.net.Uri.encode(browseId.removePrefix("VL"))}"
                else "https://music.youtube.com/browse/${android.net.Uri.encode(browseId)}",
                floatingTitleOverride = stringResource(
                    if (isPlaylist) R.string.collection_share_playlist
                    else R.string.collection_share_album,
                ),
                onDismiss = { libraryCollectionShare = null },
            )
        }

        playlistActions?.let { playlist ->
            com.music.orb.ui.components.RegisterSheetBackdrop()
            val sheetGlassColor = orbOverlayGlassBaseColor()
            com.music.orb.ui.components.OrbModalBottomSheet(
                floatingTitle = playlist.title,
                onDismissRequest = { playlistActions = null },
                containerColor = Color.Transparent,
                shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
                navigationBarInsetInContent = true,
                dragHandle = null,
            ) {
                com.music.orb.ui.components.OrbGlassSheetSurface(sheetGlassColor, 36.dp) {
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
                            Toast.makeText(
                                context,
                                context.getString(R.string.first_account_youtube_error),
                                Toast.LENGTH_LONG,
                            ).show()
                            advanceFirstAccountOnboardingToUsername()
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
                            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
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
                                contentDescription = stringResource(R.string.share_close),
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
                    (context as? MainActivity)?.connectYoutubeMusic { result ->
                        result.onSuccess {
                            viewModel.onYoutubeIdentityChanged()
                            scope.launch {
                                val channels = OrbGoogleAuth.availableYoutubeIdentities()
                                if (channels.isNotEmpty()) youtubeIdentityPicker = channels
                            }
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "YouTube authorization failed.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
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
            var lastfmError by remember { mutableStateOf<String?>(null) }
            var lastfmLoading by remember { mutableStateOf(false) }
            LastfmLoginAlert(
                hazeState = hazeState,
                waitingForApproval = lastfmPendingToken.isNotBlank(),
                error = lastfmError,
                loading = lastfmLoading,
                onAuthorize = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            val authorization = LastFM.startAuthorization()
                            lastfmPendingToken = authorization.token
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorization.url)))
                        } catch (error: Exception) {
                            lastfmError = error.message ?: "Não foi possível abrir o Last.fm"
                        } finally {
                            lastfmLoading = false
                        }
                    }
                },
                onComplete = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            val auth = LastFM.completeAuthorization(lastfmPendingToken)
                            AppSettings.setLastfmSessionKey(auth.sessionKey)
                            AppSettings.setLastfmUsername(auth.username)
                            AppSettings.setLastfmScrobbleEnabled(true)
                            AppSettings.setLastfmNowPlaying(true)
                            AppSettings.setLastfmEnabled(true)
                            lastfmPendingToken = ""
                            showLastfmLogin = false
                            com.music.orb.data.scrobbling.LastfmScrobbleQueue.scheduleIfPending(context)
                        } catch (error: Exception) {
                            lastfmError = error.message ?: "Autorize o acesso no navegador primeiro"
                        } finally {
                            lastfmLoading = false
                        }
                    }
                },
                onDismiss = { if (!lastfmLoading) { showLastfmLogin = false; lastfmPendingToken = "" } },
            )
        }

    } // End of the blurred application surface.

        // Update dialogs are OVER the blurred app surface, not inside it.
        // UpdateAvailableDialog registers a backdrop while it is visible,
        // so only the content behind the popup is blurred.
        val currentUpdateDownload = activeUpdateDownload
        if (currentUpdateDownload != null) {
            val active = currentUpdateDownload
            UpdateAvailableDialog(
                version = active.release.tagName.removePrefix("v"),
                hazeState = hazeState,
                modifier = Modifier.zIndex(160f),
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
                    modifier = Modifier.zIndex(160f),
                    mode = SoftwareUpdateDialogMode.READY,
                    releaseNotes = readyUpdate.releaseNotes,
                    heroImageUrl = readyUpdate.heroImageUrl,
                    primaryActionLabel = stringResource(R.string.beta_updates_install_now),
                    onDismiss = { showAutomaticUpdateDialog = false },
                    onSecondaryAction = {
                        AppSettings.remindUpdateLater()
                        showAutomaticUpdateDialog = false
                    },
                    onUpdate = {
                        UpdateReadyStore.onInstallRequested(context)
                        context.startActivity(UpdateReadyStore.installIntent(context, readyUpdate))
                        showAutomaticUpdateDialog = false
                    },
                )
                availableUpdate != null -> UpdateAvailableDialog(
                    version = availableUpdate.tagName.removePrefix("v"),
                    hazeState = hazeState,
                    modifier = Modifier.zIndex(160f),
                    mode = SoftwareUpdateDialogMode.AVAILABLE,
                    releaseNotes = availableUpdate.releaseNotes,
                    heroImageUrl = availableUpdate.heroImageUrl,
                    onDismiss = { showAutomaticUpdateDialog = false },
                    onSecondaryAction = {
                        AppSettings.remindUpdateLater()
                        showAutomaticUpdateDialog = false
                    },
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
                    modifier = Modifier.zIndex(160f),
                    mode = SoftwareUpdateDialogMode.AVAILABLE,
                    releaseNotes = update.release.releaseNotes,
                    heroImageUrl = update.release.heroImageUrl,
                    onDismiss = { showUpdateDialog = false },
                    onSecondaryAction = {
                        AppSettings.remindUpdateLater()
                        showUpdateDialog = false
                    },
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


        if (!showNowPlaying) {
            nowPlayingLaunchVisual?.let { visual ->
                NowPlayingLaunchOverlay(
                    visual = visual,
                    launchProgress = nowPlayingLaunchProgress,
                    rootSize = appRootSize,
                    sourceArtworkBounds = miniPlayerArtworkBounds,
                    sourceIdentityBounds = miniPlayerIdentityBounds,
                    sourcePlayPauseBounds = miniPlayerPlayPauseBounds,
                    sourceNextBounds = miniPlayerNextBounds,
                    targetArtworkBounds = nowPlayingArtworkBounds,
                    targetIdentityBounds = nowPlayingIdentityBounds,
                    targetIdentityAppearance = nowPlayingIdentityAppearance,
                    bridgeReady = nowPlayingArtworkReady && nowPlayingIdentityAppearance != null &&
                        nowPlayingArtworkBounds != null && nowPlayingIdentityBounds != null &&
                        (listeningRoom?.host == false || (nowPlayingPlayPauseBounds != null && nowPlayingNextBounds != null)),
                    targetPlayPauseBounds = nowPlayingPlayPauseBounds,
                    targetNextBounds = nowPlayingNextBounds,
                    isPlaying = player.isPlaying,
                    isLoading = player.isLoading || pendingNowPlayingSong != null,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(80f),
                )
            }
        }

        touchTransferVisual?.let { visual ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {},
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                    dismissOnBackPress = false, dismissOnClickOutside = false),
            ) {
                val transferWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                val transferDensity = LocalDensity.current
                androidx.compose.runtime.SideEffect {
                    transferWindow?.let { window ->
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        window.setDimAmount(0f)
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                            val attributes = window.attributes
                            attributes.setBlurBehindRadius(if(touchTransferBlurActive) with(transferDensity) { 36.4.dp.roundToPx() } else 0)
                            window.attributes = attributes
                        }
                    }
                }
                TouchTransferOverlay(
                    visual = visual,
                    rootSize = appRootSize,
                    incomingArtworkDestination = miniPlayerArtworkBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(95f),
                    onCancel = {
                        scope.launch { OrbTouchTransfer.cancel(visual.handoffId) }
                        OrbHaptics.stopTransfer(context)
                        if (touchTransferVisual?.token == visual.token) {
                            touchTransferVisual = null
                        }
                    },
                    onFinished = { completed ->
                        if (!completed) {
                            scope.launch { OrbTouchTransfer.cancel(visual.handoffId) }
                            OrbHaptics.stopTransfer(context)
                            if (touchTransferVisual?.token == visual.token) touchTransferVisual = null
                        } else if (touchTransferVisual?.token == visual.token) {
                            val incomingFinished =
                                visual.direction == TouchTransferDirection.INCOMING &&
                                    visual.phase == TouchTransferPhase.TRANSFERRING

                            if (incomingFinished && miniPlayerArtworkBounds != null &&
                                OrbTouchTransfer.isUiActive() && OrbTouchTransfer.canStartReceiver(visual.handoffId)) {
                                touchTransferLandedId = visual.handoffId
                                OrbHaptics.completeTransfer(context)
                                touchTransferIncomingLanding = false
                                scope.launch {
                                    // Keep the background blurred while the sharp cover settles.
                                    delay(180L)
                                    if (touchTransferVisual?.token == visual.token) {
                                        touchTransferSettledId = visual.handoffId
                                        // Retain the cover while backdrop layers clear, then hand it off.
                                        delay(240L)
                                        if (touchTransferVisual?.token == visual.token) touchTransferVisual = null
                                    }
                                }
                            }
                            if (visual.phase == TouchTransferPhase.PREPARING) {
                                scope.launch { OrbTouchTransfer.cancel(visual.handoffId) }
                                OrbHaptics.stopTransfer(context)
                                touchTransferVisual = null
                            } else if (!incomingFinished) {
                                if (OrbTouchTransfer.isUiActive()) touchTransferDepartedId = visual.handoffId
                                transferredAwaySongId = visual.song.videoId
                                touchTransferVisual = null
                            }
                        }
                    },
                )
            }
        }

        if (!showNowPlaying) {
            OrbNoticeHost(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun TouchTransferOverlay(
    visual: TouchTransferVisual,
    rootSize: IntSize,
    incomingArtworkDestination: Rect? = null,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onFinished: (Boolean) -> Unit,
) {
    if (rootSize.width <= 0 || rootSize.height <= 0) return

    var artworkReady by remember(visual.token) { mutableStateOf(false) }
    val pulse = remember(visual.token) { Animatable(0f) }
    val transfer = remember(visual.token) { Animatable(0f) }
    val lightExpansion = remember(visual.handoffId) { Animatable(0f) }
    val glowMotion = remember(visual.handoffId) { Animatable(0f) }
    LaunchedEffect(visual.handoffId) {
        while (true) {
            glowMotion.snapTo(0f)
            glowMotion.animateTo(1f, tween(2600, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    LaunchedEffect(visual.handoffId) {
        lightExpansion.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
    }
    val configuration = LocalConfiguration.current
    val compactGlow = configuration.smallestScreenWidthDp >= 600 &&
        configuration.screenWidthDp > configuration.screenHeightDp
    val density = LocalDensity.current

    LaunchedEffect(visual.token, visual.phase) {
        pulse.snapTo(0f)
        transfer.snapTo(0f)
        if (visual.phase == TouchTransferPhase.PREPARING) {
            // PREPARING is glow-only and persists on both displays until motion begins.
            // Keep a TTL fallback so an abandoned/expired offer can never leave a glow stuck.
            val glowStartedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - glowStartedAt < 18_000L) {
                pulse.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
                pulse.animateTo(0f, tween(1_150, easing = FastOutSlowInEasing))
            }
            onFinished(false)
        } else {
            transfer.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    if (visual.direction == TouchTransferDirection.INCOMING) 1_250 else 1_700,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
            onFinished(artworkReady)
        }
    }

    val incoming = visual.direction == TouchTransferDirection.INCOMING
    val preparing = visual.phase == TouchTransferPhase.PREPARING
    val p = transfer.value.coerceIn(0f, 1f)
    val breath = pulse.value.coerceIn(0f, 1f)
    val rootWidth = rootSize.width.toFloat().coerceAtLeast(1f)
    val rootHeight = rootSize.height.toFloat().coerceAtLeast(1f)
    val statusBarInsetPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
    // Keep the portal and the strongest part of the light *inside* the status-bar band so
    // the glow reads as part of the system chrome instead of starting below it.
    val topPortalY = (statusBarInsetPx * 0.46f).coerceAtLeast(with(density) { 6.dp.toPx() })

    // Each leg has its own easing: sender lift, disc reveal, portal departure;
    // receiver arrival, brief center pause, then a fast landing in the real mini player.
    fun leg(start: Float, end: Float): Float = FastOutSlowInEasing.transform(
        ((p - start) / (end - start)).coerceIn(0f, 1f),
    )
    fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount
    val centerY = rootHeight * 0.45f
    val centerX = rootWidth * 0.5f
    val portalY = topPortalY - with(density) { 28.dp.toPx() }
    val origin = visual.artworkOrigin.takeIf { !incoming }
    val incomingTarget = incomingArtworkDestination.takeIf { incoming }
    val incomingTargetX = incomingTarget?.center?.x ?: (rootWidth * 0.12f)
    val incomingTargetY = incomingTarget?.center?.y ?: (rootHeight * 0.88f)
    val departureY = origin?.center?.y ?: (rootHeight * 0.88f)
    val departureX = origin?.center?.x ?: (rootWidth * 0.12f)
    val departureSizeDp = origin?.let { with(density) { it.width.toDp().value } } ?: 64f
    val incomingTargetSizeDp = incomingTarget
        ?.let { with(density) { it.width.toDp().value } }?.coerceAtLeast(1f) ?: 64f
    val heroSizeDp = with(density) { (minOf(rootWidth * 0.58f, rootHeight * 0.36f)).toDp().value }
    val lift = leg(0f, 0.32f)
    val depart = leg(0.56f, 1f)
    val arrive = leg(0f, 0.68f)
    val land = leg(0.76f, 1f)
    val artworkCenterY = if (incoming) {
        if (p < 0.76f) mix(portalY, centerY, arrive) else mix(centerY, incomingTargetY, land)
    } else mix(mix(departureY, centerY, lift), portalY, depart)
    val artworkCenterX = if (incoming) {
        mix(centerX, incomingTargetX, land)
    } else mix(departureX, centerX, lift)
    val artworkSizeDp = if (incoming) {
        mix(mix(28f, heroSizeDp, arrive), incomingTargetSizeDp, land).dp
    } else mix(mix(departureSizeDp, heroSizeDp, lift), 24f, depart).dp
    val discReveal = if (incoming) 0f else leg(0.32f, 0.32f + (0.53f - 0.32f) / 0.90f)
    // Decode once for the largest animation size, not the initial mini-player bounds.
    val transferArtworkPx = kotlin.math.ceil(with(density) {
        maxOf(heroSizeDp, departureSizeDp, incomingTargetSizeDp).dp.toPx()
    }).toInt().coerceAtLeast(HEADER_ART_PX)
    val transferArtworkContext = LocalContext.current
    val transferArtworkRequest = remember(visual.song.thumbnailUrl, transferArtworkPx, transferArtworkContext) {
        coil3.request.ImageRequest.Builder(transferArtworkContext)
            .data(visual.song.artworkAt(transferArtworkPx))
            .size(transferArtworkPx, transferArtworkPx)
            .build()
    }
    val artworkAlpha = when {
        preparing -> 0.88f + 0.12f * breath
        incoming -> (p / 0.12f).coerceIn(0f, 1f)
        else -> if (p < 0.86f) 1f else (1f - (p - 0.86f) / 0.14f).coerceIn(0f, 1f)
    }
    val artworkRotation = when {
        preparing -> breath * 1.2f - 0.6f
        incoming -> -5f * (1f - p)
        else -> 5f * p
    }
    val portalFlash = when {
        preparing -> 0.42f + 0.40f * breath
        incoming -> (1f - (p / 0.48f).coerceIn(0f, 1f))
        else -> ((p - 0.48f) / 0.52f).coerceIn(0f, 1f)
    }
    val backdropAlpha = when {
        preparing -> 0f
        incoming -> (1f - p) * 0.14f
        else -> p * 0.14f
    }
    val lightTint = MaterialTheme.colorScheme.primary
    val secondaryLightTint = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = modifier
            .pointerInput(visual.handoffId) {
                // Keep the transfer overlay modal, but let overlay controls (Cancel)
                // receive the gesture first. The full-screen Dialog already blocks
                // touches from reaching the app underneath.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                    }
                }
            }
            .background(Color.Black.copy(alpha = backdropAlpha)),
    ) {
        // Radial light fields expand into the screen, rather than drawing a
        // blurred capsule. Cache the brushes; only transforms change per frame.
        Box(
            Modifier
                .fillMaxSize()
                // DstIn below must be isolated from the app underneath; otherwise the fade mask
                // would also punch through the real UI. The offscreen layer contains only light.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                // Reuse the inset measured in the composable scope. Reading WindowInsets here
                // would invoke a composable API from DrawCacheScope and fail Kotlin compilation.
                val statusBand = statusBarInsetPx.coerceAtLeast(1f)
                // Landscape tablet width must not determine the glow's depth.
                val glowSpan = if (compactGlow) minOf(size.width * 0.42f, size.height * 0.80f) else size.width
                val anchor = Offset(size.width * 0.5f, -statusBand * 0.36f)
                val halo = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.96f),
                    0.14f to lightTint.copy(alpha = 0.82f),
                    0.42f to lightTint.copy(alpha = 0.42f),
                    1f to Color.Transparent,
                    center = anchor,
                    radius = glowSpan * 1.08f,
                )
                val warmHalo = Brush.radialGradient(
                    0f to secondaryLightTint.copy(alpha = 0.60f),
                    0.36f to secondaryLightTint.copy(alpha = 0.28f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.5f + glowSpan * 0.16f, anchor.y),
                    radius = glowSpan * 0.80f,
                )
                val core = Brush.radialGradient(
                    0f to Color.White,
                    0.18f to Color.White.copy(alpha = 0.98f),
                    0.48f to Color.White.copy(alpha = 0.52f),
                    1f to Color.Transparent,
                    center = anchor,
                    radius = glowSpan * 0.68f,
                )
                // Continuous wash crosses the status-bar/content boundary before the radial
                // other light fields are added. Its depth follows the halo during the pulse.
                val topWash = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.72f),
                    0.12f to lightTint.copy(alpha = 0.62f),
                    0.34f to lightTint.copy(alpha = 0.38f),
                    0.62f to lightTint.copy(alpha = 0.14f),
                    1f to Color.Transparent,
                    startY = -statusBand,
                    endY = if (compactGlow) minOf(size.height * 0.28f, maxOf(statusBand * 2f, glowSpan * 0.46f))
                        else maxOf(statusBand * 5.2f, size.width * 0.46f),
                )
                // The radial fields are transformed vertically. Without an explicit alpha falloff,
                // their finite drawing layer can reveal a straight horizontal edge on some GPUs.
                // This mask keeps the very top fully opaque, covers the whole status bar, and then
                // dissolves gradually so there is no dividing line between the chrome and the page.
                val glowFadeEndY = (if (compactGlow) size.height * 0.32f
                    else minOf(size.height * 0.80f, size.width * 1.72f))
                    .coerceAtLeast(statusBand + 1f)
                val glowVerticalMask = Brush.verticalGradient(
                    0f to Color.White,
                    0.44f to Color.White,
                    0.72f to Color.White.copy(alpha = 0.96f),
                    0.90f to Color.White.copy(alpha = 0.30f),
                    1f to Color.Transparent,
                    startY = -statusBand,
                    endY = glowFadeEndY,
                )
                onDrawBehind {
                    val expansion = lightExpansion.value
                    val wave = glowMotion.value * (kotlin.math.PI.toFloat() * 2f)
                    val breathing = 0.5f + 0.5f * kotlin.math.sin(wave)
                    val motion = transfer.value
                    val fade = if (visual.phase == TouchTransferPhase.PREPARING) 1f
                        else if (visual.direction == TouchTransferDirection.OUTGOING)
                            (1f - ((motion - 0.90f) / 0.10f).coerceIn(0f, 1f))
                        else (1f - ((motion - 0.68f) / 0.32f).coerceIn(0f, 1f))
                    val strength = expansion * fade
                    val depth = (0.42f + expansion * 0.52f + 0.08f) * (0.74f + breathing * 0.52f)

                    // Paint one continuous luminous surface first. This spans the entire
                    // status bar and the first content band, hiding any system/app boundary.
                    // All color fields brighten the backdrop and share the same breathing depth.
                    // A colored SrcOver wash could darken the moving halo into a visible band.
                    withTransform({ scale(scaleX = 1f, scaleY = depth, pivot = anchor) }) {
                        drawRect(
                            brush = topWash,
                            alpha = strength * (0.82f + breathing * 0.18f),
                            blendMode = BlendMode.Screen,
                        )
                    }

                    withTransform({
                        translate(left = 0f,
                            top = -statusBand * 0.48f)
                        scale(scaleX = 0.82f + expansion * 0.62f,
                            scaleY = depth, pivot = anchor)
                    }) {
                        drawRect(halo, alpha = strength * (0.96f + breathing * 0.18f), blendMode = BlendMode.Screen)
                        withTransform({
                            scale(scaleX = 1f, scaleY = 0.94f + breathing * 0.12f, pivot = anchor)
                        }) {
                            drawRect(warmHalo, alpha = strength * (0.80f + breathing * 0.20f), blendMode = BlendMode.Screen)
                        }
                    }
                    withTransform({
                        translate(left = 0f,
                            top = -statusBand * 0.18f)
                        scale(
                            scaleX = 0.74f + expansion * 0.84f,
                            scaleY = (0.24f + expansion * 0.26f + 0.03f) * (0.80f + breathing * 0.40f),
                            pivot = anchor,
                        )
                    }) {
                        drawRect(core, alpha = strength * (0.96f + breathing * 0.08f), blendMode = BlendMode.Screen)
                    }
                    // Multiply all light fields by the same soft vertical envelope. DstIn keeps
                    // their RGB intact while forcing alpha to zero only well after the status bar.
                    withTransform({ scale(scaleX = 1f, scaleY = depth, pivot = anchor) }) {
                        // Cover the complete layer even during contraction so no unmasked strip remains.
                        drawRect(glowVerticalMask,
                            size = androidx.compose.ui.geometry.Size(size.width, size.height * (1f + 1f / depth)),
                            blendMode = BlendMode.DstIn)
                    }
                }
            },
        )

        // The same realistic PNG used by the action sheet arrives from the
        // side, settles behind the sleeve, and travels with it into the glow.
        if (!preparing && !incoming && p >= 0.32f) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.action_button_disc_realistic),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(artworkSizeDp * 0.94f).graphicsLayer {
                    val coverPx = with(density) { artworkSizeDp.toPx() }
                    val discPx = coverPx * 0.94f
                    val sideOffset = mix(rootWidth * 0.65f, 0f, discReveal)
                    translationX = artworkCenterX + sideOffset - discPx / 2f
                    translationY = artworkCenterY - discPx / 2f
                    rotationZ = 24f * discReveal + 70f * depart
                    alpha = artworkAlpha * (discReveal / 0.15f).coerceIn(0f, 1f)
                },
            )
        }

        // No cover thumbnail is rendered while PREPARING. The artwork only appears
        // when TRANSFERRING begins: leaving the sender or arriving on the receiver.
        if (!preparing) {
            Surface(
                modifier = Modifier
                    .size(artworkSizeDp)
                    .graphicsLayer {
                        val sizePx = with(density) { artworkSizeDp.toPx() }
                        translationX = artworkCenterX - sizePx / 2f
                        translationY = artworkCenterY - sizePx / 2f
                        rotationZ = artworkRotation
                        alpha = artworkAlpha
                        shadowElevation = with(density) {
                            (18f + 18f * portalFlash).dp.toPx()
                        }
                    },
                shape = RoundedCornerShape(
                    // Mini-player artwork uses 34% corners. Morph continuously
                    // into a sleeve at center, then back into the landing shape.
                    percent = if (incoming) (4f + 30f * land).toInt()
                        else (34f - 30f * lift).toInt(),
                ),
                color = Color.Black,
                shadowElevation = 22.dp,
            ) {
                AsyncImage(
                    model = transferArtworkRequest,
                    onSuccess = { artworkReady = true },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val cancelSurface = MaterialTheme.colorScheme.onBackground
        val cancelContent = MaterialTheme.colorScheme.background
        Surface(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .height(50.dp),
            shape = RoundedCornerShape(percent = 50),
            color = cancelSurface,
            contentColor = cancelContent,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 30.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = cancelContent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.titleMedium,
                    color = cancelContent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingLaunchOverlay(
    visual: NowPlayingLaunchVisual,
    launchProgress: Float,
    rootSize: IntSize,
    sourceArtworkBounds: Rect?,
    sourceIdentityBounds: Rect?,
    sourcePlayPauseBounds: Rect?,
    sourceNextBounds: Rect?,
    targetArtworkBounds: Rect?,
    targetIdentityBounds: Rect?,
    targetIdentityAppearance: PlayerIdentityAppearance?,
    bridgeReady: Boolean,
    targetPlayPauseBounds: Rect?,
    targetNextBounds: Rect?,
    isPlaying: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (rootSize.width <= 0 || rootSize.height <= 0) return

    val density = LocalDensity.current
    val p = launchProgress.coerceIn(0f, 1f)
    fun stage(start: Float, end: Float): Float {
        if (end <= start) return if (p >= end) 1f else 0f
        val raw = ((p - start) / (end - start)).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }
    fun rectLerp(source: Rect, target: Rect, amount: Float): Rect = Rect(
        left = source.left + (target.left - source.left) * amount,
        top = source.top + (target.top - source.top) * amount,
        right = source.right + (target.right - source.right) * amount,
        bottom = source.bottom + (target.bottom - source.bottom) * amount,
    )

    val rootWidthPx = rootSize.width.toFloat()
    val rootHeightPx = rootSize.height.toFloat()
    val rootWidthDp = with(density) { rootWidthPx.toDp() }
    val rootHeightDp = with(density) { rootHeightPx.toDp() }
    val artworkFade = 1f - stage(0.94f, 1f)
    val sharedAlpha = if (p >= 1f) 0f else 1f

    Box(modifier = modifier.graphicsLayer {
        alpha = if (visual.originKind == NowPlayingLaunchOriginKind.MINI_PLAYER && !bridgeReady) 0f else 1f
    }) {
        if (visual.originKind == NowPlayingLaunchOriginKind.MINI_PLAYER) {
            // The host clips one real background. This layer owns only shared
            // elements, using the same progress in either direction.
            val sharedP = p
            val fallbackSourceArt = run {
                val insetPx = with(density) { (if (OrbFlavorUi.expressive) 8.dp else 5.dp).toPx() }
                val side = with(density) { (if (OrbFlavorUi.expressive) 50.dp else 40.dp).toPx() }
                Rect(
                    visual.origin.left + insetPx,
                    visual.origin.top + insetPx,
                    visual.origin.left + insetPx + side,
                    visual.origin.top + insetPx + side,
                )
            }
            val sourceArt = sourceArtworkBounds ?: fallbackSourceArt
            val destinationArt = targetArtworkBounds ?: Rect(0f, 0f, rootWidthPx, rootWidthPx)
            val artRect = rectLerp(sourceArt, destinationArt, sharedP)
            val artWidthDp = with(density) { destinationArt.width.coerceAtLeast(1f).toDp() }
            val artHeightDp = with(density) { destinationArt.height.coerceAtLeast(1f).toDp() }
            val artBaseCenterX = destinationArt.width / 2f
            val artBaseCenterY = destinationArt.height / 2f
            // Interpolate the corner *fraction*, so rounding is visible while the
            // artwork is still large and ends at the exact compact cover shape.
            val cornerFraction = (if (OrbFlavorUi.expressive) MINI_ARTWORK_CORNER_PERCENT / 100f
                else 7f / 40f) * (1f - sharedP)
            val artworkCornerPx = minOf(artRect.width, artRect.height) * cornerFraction

            AsyncImage(
                model = coil3.request.ImageRequest.Builder(LocalContext.current)
                    .data(visual.song.artworkAt(1200)).size(1200).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(artWidthDp, artHeightDp)
                    .graphicsLayer {
                        val width = artRect.width.coerceAtLeast(1f)
                        val height = artRect.height.coerceAtLeast(1f)
                        scaleX = width / destinationArt.width.coerceAtLeast(1f)
                        scaleY = height / destinationArt.height.coerceAtLeast(1f)
                        translationX = artRect.left + width / 2f - artBaseCenterX
                        translationY = artRect.top + height / 2f - artBaseCenterY
                        alpha = artworkFade * sharedAlpha
                        val radiusX = artworkCornerPx / scaleX.coerceAtLeast(0.001f)
                        val radiusY = artworkCornerPx / scaleY.coerceAtLeast(0.001f)
                        shape = GenericShape { size, _ ->
                            addRoundRect(RoundRect(
                                Rect(0f, 0f, size.width, size.height),
                                CornerRadius(radiusX, radiusY),
                            ))
                        }
                        clip = true
                    },
            )

            if (sourceIdentityBounds != null && targetIdentityBounds != null) {
                val identityRect = rectLerp(sourceIdentityBounds, targetIdentityBounds, sharedP)
                Column(
                    modifier = Modifier
                        .width(with(density) { identityRect.width.coerceAtLeast(1f).toDp() })
                        .graphicsLayer {
                            translationX = identityRect.left
                            translationY = identityRect.top
                            alpha = sharedAlpha
                        },
                ) {
                    val titleStyle = androidx.compose.ui.text.lerp(
                        MaterialTheme.typography.titleMedium,
                        targetIdentityAppearance?.titleStyle ?: MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                        p,
                    )
                    val artistStyle = androidx.compose.ui.text.lerp(
                        MaterialTheme.typography.bodyMedium,
                        targetIdentityAppearance?.artistStyle ?: MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                        p,
                    )
                    val targetColor = targetIdentityAppearance?.color ?: Color.White
                    ExplicitTitle(
                        text = visual.song.title,
                        isExplicit = visual.song.isExplicit,
                        style = titleStyle,
                        color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurface, targetColor, p),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        badgeSpacing = lerp(4.dp, 5.dp, p),
                        keepBadgeVisible = false,
                    )
                    Text(
                        text = visual.song.artist,
                        style = artistStyle,
                        color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurfaceVariant, targetColor, p),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            @Composable
            fun sharedControl(source: Rect?, target: Rect?, playPause: Boolean) {
                if (source == null || target == null) return
                val rect = rectLerp(source, target, sharedP)
                val baseWidth = target.width.coerceAtLeast(1f)
                val baseHeight = target.height.coerceAtLeast(1f)
                Box(
                    modifier = Modifier
                        .size(
                            with(density) { baseWidth.toDp() },
                            with(density) { baseHeight.toDp() },
                        )
                        .graphicsLayer {
                            scaleX = rect.width.coerceAtLeast(1f) / baseWidth
                            scaleY = rect.height.coerceAtLeast(1f) / baseHeight
                            translationX = rect.left + rect.width / 2f - baseWidth / 2f
                            translationY = rect.top + rect.height / 2f - baseHeight / 2f
                            alpha = sharedAlpha
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (OrbFlavorUi.expressive) {
                        val sharedSlotSize = with(density) { baseWidth.toDp() }
                        val sharedPlayPauseCorner = if (playPause) {
                            if (isPlaying) sharedSlotSize * 0.26f else sharedSlotSize / 2f
                        } else null
                        PlayerTransportVisual(
                            icon = if (playPause) {
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                            } else Icons.Rounded.SkipNext,
                            slotSize = sharedSlotSize,
                            selected = playPause,
                            isLoading = playPause && isLoading,
                            foregroundColor = androidx.compose.ui.graphics.lerp(
                                if (playPause) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                targetIdentityAppearance?.transportColor ?: Color.White, p,
                            ),
                            containerColor = androidx.compose.ui.graphics.lerp(
                                MaterialTheme.colorScheme.primaryContainer,
                                targetIdentityAppearance?.transportContainerColor ?: Color.White.copy(alpha = 0.18f), p,
                            ),
                            containerCornerRadius = sharedPlayPauseCorner,
                        )
                    } else {
                        Icon(
                            imageVector = if (playPause) {
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                            } else Icons.Rounded.FastForward,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.lerp(
                                MaterialTheme.colorScheme.onBackground,
                                targetIdentityAppearance?.color ?: Color.White, p,
                            ),
                            modifier = Modifier.fillMaxSize(0.56f),
                        )
                    }
                }
            }

            sharedControl(sourcePlayPauseBounds, targetPlayPauseBounds, playPause = true)
            if (!com.music.orb.data.rooms.RoomRepository.isGuest()) {
                sharedControl(sourceNextBounds, targetNextBounds, playPause = false)
            }
        } else {
            val backdropIn = (p / 0.58f).coerceIn(0f, 1f)
            val backdropOut = if (p < 0.82f) 1f else (1f - (p - 0.82f) / 0.18f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = backdropIn * backdropOut)),
            )

            val destination = visual.destination?.takeIf {
                it.width > 1f && it.height > 1f
            } ?: targetArtworkBounds?.takeIf { it.width > 1f && it.height > 1f } ?: Rect(
                left = 0f,
                top = 0f,
                right = rootWidthPx,
                bottom = rootWidthPx,
            )
            val destinationWidthPx = destination.width.coerceAtLeast(1f)
            val destinationHeightPx = destination.height.coerceAtLeast(1f)
            val origin = visual.origin
            val currentWidth = origin.width + (destinationWidthPx - origin.width) * p
            val currentHeight = origin.height + (destinationHeightPx - origin.height) * p
            val originCenterX = origin.left + origin.width / 2f
            val originCenterY = origin.top + origin.height / 2f
            val destinationCenterX = destination.left + destinationWidthPx / 2f
            val destinationCenterY = destination.top + destinationHeightPx / 2f
            val currentCenterX = originCenterX + (destinationCenterX - originCenterX) * p
            val currentCenterY = originCenterY + (destinationCenterY - originCenterY) * p
            val destinationWidthDp = with(density) { destinationWidthPx.toDp() }
            val destinationHeightDp = with(density) { destinationHeightPx.toDp() }
            val baseCenterX = destinationWidthPx / 2f
            val baseCenterY = destinationHeightPx / 2f
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
                        scaleX = currentWidth / destinationWidthPx
                        scaleY = currentHeight / destinationHeightPx
                        translationX = currentCenterX - baseCenterX
                        translationY = currentCenterY - baseCenterY
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
        modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp),
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
            TextButton(onClick = onReconnect) {
                Text(stringResource(R.string.youtube_channel_picker_reconnect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.youtube_channel_picker_cancel))
            }
        },
    )
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

private const val SEEK_END_GUARD_MS = 1_000L
private const val ALBUM_VERSION_FIRST_NOTE_BUDGET_MS = 850L
private const val TRUSTED_FIRST_NOTE_BUDGET_MS = 7_000L
private const val ALBUM_VERSION_BACKGROUND_WARM_DELAY_MS = 7_000L
private const val RECENTLY_PLAYED_REFRESH_MS = 4L * 60L * 1_000L

private val DETAIL_TITLE_DROP = 320.dp

private const val TAB_HOME = 0
private const val TAB_EXPLORE = 1
private const val TAB_LIBRARY = 2
private const val TAB_FRIENDS = 3
private const val MAX_TAB_BACK_HISTORY = 16
