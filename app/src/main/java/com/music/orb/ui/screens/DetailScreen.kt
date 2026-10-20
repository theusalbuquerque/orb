@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.screens

import java.util.Locale
import com.music.orb.data.settings.AlbumRatingStore
import com.music.orb.data.social.CommunityRatingRepository
import com.music.orb.data.spotify.LinkedLikesSync
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreVert
import com.music.orb.ui.components.CollectionActionsSheet
import com.music.orb.ui.components.SongShareSheet
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.music.orb.R
import com.music.orb.data.WikipediaArtistInfoRepository
import com.music.orb.data.canvas.CanvasArtwork
import com.music.orb.data.canvas.CanvasRepository
import com.music.orb.data.model.ArtistLink
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.DetailPage
import com.music.orb.data.model.CARD_ART_PX
import com.music.orb.data.model.HEADER_ART_PX
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.Song
import com.music.orb.data.model.UiState
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.AppSettings
import com.music.orb.download.DownloadState
import com.music.orb.download.Downloads
import com.music.orb.ui.components.ExplicitTitle
import com.music.orb.ui.components.DownloadStatusGlyph
import com.music.orb.ui.components.ArtworkBottomEdgeField
import com.music.orb.ui.components.artworkSurfaceForegrounds
import com.music.orb.ui.components.ArtworkMergeBand
import com.music.orb.ui.components.ARTWORK_LANDSCAPE_FLOW_WIDTH
import com.music.orb.ui.components.ARTWORK_LANDSCAPE_FLOW_OVERLAP
import com.music.orb.ui.components.artworkLandscapeFlowMask
import com.music.orb.ui.components.landscapeArtworkFade
import com.music.orb.ui.components.ARTWORK_MERGE_BAND
import com.music.orb.ui.components.OrbHaptics
import com.music.orb.ui.components.bestForegroundForArtworkSurface
import com.music.orb.ui.components.rememberArtworkBottomEdgeField
import com.music.orb.ui.components.rememberArtworkRightEdgeField
import com.music.orb.ui.components.ReleaseBadges
import com.music.orb.ui.components.AlbumRatingBadge
import com.music.orb.ui.components.rememberCollectionBadges
import com.music.orb.ui.components.rememberTrackLosslessBadgeState
import com.music.orb.ui.components.CompactMediaBadges
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.ROW_DIVIDER_INSET
import com.music.orb.ui.components.SHELF_CARD_WIDTH
import com.music.orb.ui.components.ShimmerBox
import com.music.orb.ui.components.SongRow
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.components.detailSkeleton
import com.music.orb.ui.components.orbBadgeGlassSurface
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.orbNowPlayingStyleGlass
import com.music.orb.ui.components.orbExpressiveGlassContentColor
import com.music.orb.ui.icons.OrbIcons
import com.music.orb.ui.player.CanvasArtworkPlayer
import com.music.orb.ui.player.NowPlayingLaunchOriginRegistry
import com.music.orb.ui.theme.ArtworkPalette
import com.music.orb.ui.theme.SystemBarIcons
import com.music.orb.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class CollectionDownloadConfirm { CANCEL, REMOVE }

private const val MAX_ARTIST_SONGS = 20
private const val SONGS_PER_COLUMN = 4
private const val ARTIST_PHOTO_RATIO = 0.95f
private const val SLEEVE_RATIO = 0.92f
private const val SLEEVE_FRACTION = 0.80f

private val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val PILL_SHAPE = RoundedCornerShape(12.dp)
private val HEADER_GUTTER = PAGE_GUTTER + 14.dp
private val RELEASE_BADGE_FOOTPRINT = 32.dp
private val RELEASE_BADGE_HEIGHT = 14.5.dp

// Album / playlist header spacing approved for release pages.
private val HEADER_DROP = 150.dp

// Artist pages use a full-width hero photo and a separate action row. Reusing
// the larger release-page drop pushes the artist name, buttons and first
// section too far down the screen, so keep the artist's original spacing.
private val ARTIST_HEADER_DROP = 75.dp

// Album / playlist / artist artwork-to-content transition. These values mirror
// the Now Playing treatment: only the colours touching the artwork's lower edge
// are continued downward, then progressively blurred into one solid surface.
// No enlarged/duplicated artwork is drawn behind the page content.
private val DETAIL_MERGE_BAND = ARTWORK_MERGE_BAND
private val DETAIL_COLOR_FLOW_HEIGHT = 250.dp
private val DETAIL_COLOR_FLOW_OVERLAP = 76.dp

// Large-screen detail pages follow the same spatial rule as tablet Now Playing:
// complete artwork on one side, content on the other, never a giant phone page
// whose square cover extends below the viewport.
private const val DETAIL_TABLET_TWO_PANE_MIN_WIDTH_DP = 800
private val DETAIL_TABLET_ARTWORK_MAX = 760.dp
private val DETAIL_TABLET_ARTWORK_MIN = 320.dp
private val DETAIL_TABLET_CONTENT_MIN = 360.dp
private val DETAIL_TABLET_GAP = 32.dp
private val DETAIL_TABLET_END_GUTTER = 20.dp
private val DETAIL_TABLET_COLOR_FLOW_WIDTH = ARTWORK_LANDSCAPE_FLOW_WIDTH
private val DETAIL_TABLET_COLOR_FLOW_OVERLAP = ARTWORK_LANDSCAPE_FLOW_OVERLAP
private val DETAIL_TABLET_MERGE_OVERLAP = DETAIL_MERGE_BAND * 0.46f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    page: DetailPage,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onSongAddToQueue: (Song) -> Unit = onSongSwipe,
    onSongPlayNext: (Song) -> Unit = onSongSwipe,
    onCollectionPlayNext: (List<Song>) -> Unit = { tracks -> tracks.asReversed().forEach(onSongPlayNext) },
    onCollectionAddToQueue: (List<Song>) -> Unit = { tracks -> tracks.forEach(onSongAddToQueue) },
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onDownloadAll: (List<Song>) -> Unit,
    onDownloadPlaylist: ((List<Song>) -> Unit)? = null,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    onLoadMore: () -> Unit = {},
    onToggleLibrary: (() -> Unit)? = null,
    albumPlaybackBlocked: Boolean = false,
    onToggleAlbumPlaybackBlock: (() -> Unit)? = null,
    artistLiked: Boolean = false,
    artistBlocked: Boolean = false,
    onToggleArtistLike: (() -> Unit)? = null,
    onToggleArtistBlock: (() -> Unit)? = null,
) {
    val songs = (page.songs as? UiState.Success)?.data.orEmpty()
    var albumInfoExpanded by remember(page.browseId) { mutableStateOf(false) }
    var albumRateRequest by remember(page.browseId) { mutableIntStateOf(0) }
    // The community score belongs to the screen, never to a LazyColumn item:
    // scrolling the release header off-screen must not discard the fetched score
    // or trigger a fresh server request when that header becomes visible again.
    val albumRatingRevision by AlbumRatingStore.revision.collectAsStateWithLifecycle()
    var albumCommunityRating by remember(page.browseId, page.type) { mutableStateOf<Float?>(null) }
    LaunchedEffect(page.browseId, page.type, albumRatingRevision) {
        if (page.type != BrowseType.ALBUM) return@LaunchedEffect
        try {
            val updated = CommunityRatingRepository.albumAverage(page.browseId)
            // Keep the last displayed score during transient offline/RPC failures.
            // In particular, never blank the badge while a background refresh runs.
            if (updated != null || albumCommunityRating == null) {
                albumCommunityRating = updated
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Ratings are saved separately; a public-average fetch failure does
            // not mean the user's personal album rating failed to persist.
        }
    }
    // Window coordinates of the visible star in the release badge. The rating
    // sheet needs this anchor even when the expandable album info is collapsed.
    var releaseRatingStarBoundsOnScreen by remember(page.browseId) { mutableStateOf<Rect?>(null) }
    val isArtist = page.type == BrowseType.ARTIST
    val directionalQueueSwipe = page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST

    // Album/playlist continuations are appended by MainViewModel after the
    // first page is already visible. DetailScreen only renders the state it has;
    // scrolling never has to trigger network work or wait for the next batch.

    // Use one universal artwork-colour system for album, playlist and artist
    // pages. Albums/playlists prefer the playable-media artwork so their palette
    // matches Now Playing even when YouTube Music exposes a different crop/URL.
    // Artist pages use the artist hero image as their artwork source, but consume
    // the exact same player fields and rendering rules.
    val paletteArtworkUrl = when (page.type) {
        // A playlist is its own visual object. Its first track can be from an
        // entirely different release, so deriving the page palette from that
        // row makes the background jump to the song artwork instead of matching
        // the playlist cover the user actually opened.
        BrowseType.PLAYLIST -> page.thumbnailUrl
        BrowseType.ARTIST -> page.thumbnailUrl
        // Album tracks normally share the release artwork; preferring the first
        // playable row keeps album detail and Now Playing on the same palette.
        BrowseType.ALBUM -> songs.firstOrNull()?.thumbnailUrl ?: page.thumbnailUrl
        else -> page.thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl
    }
    val sourcePalette = rememberArtworkPalette(paletteArtworkUrl)
    val detailArtworkUrl = page.thumbnailUrl.artworkAt(HEADER_ART_PX)
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isTabletLandscape =
        isTablet &&
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.screenWidthDp >= DETAIL_TABLET_TWO_PANE_MIN_WIDTH_DP
    val isTabletPortrait = isTablet && !isTabletLandscape
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val tabletArtworkSize = if (isTabletLandscape) {
        val maxArtworkLeavingContent = (
            screenWidth - DETAIL_TABLET_GAP - DETAIL_TABLET_END_GUTTER -
                DETAIL_TABLET_CONTENT_MIN
            ).coerceAtLeast(DETAIL_TABLET_ARTWORK_MIN)
        minOf(
            screenHeight,
            screenWidth * 0.54f,
            DETAIL_TABLET_ARTWORK_MAX,
            maxArtworkLeavingContent,
        ).coerceAtLeast(DETAIL_TABLET_ARTWORK_MIN)
    } else {
        0.dp
    }
    val tabletContentStart = if (isTabletLandscape) {
        tabletArtworkSize + DETAIL_TABLET_GAP
    } else {
        0.dp
    }
    val detailBottomEdgeField = if (isTabletLandscape) {
        rememberArtworkRightEdgeField(
            image = detailArtworkUrl,
            fallback = sourcePalette.playerBackground,
        )
    } else {
        rememberArtworkBottomEdgeField(
            image = detailArtworkUrl,
            fallback = sourcePalette.playerBackground,
        )
    }
    val detailSurfaceColor = detailBottomEdgeField.solidColor
    val isReleaseCollection = page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST
    val absoluteContentColor = bestForegroundForArtworkSurface(detailSurfaceColor)
    val releaseForegrounds = if (isReleaseCollection || page.type == BrowseType.ARTIST) {
        artworkSurfaceForegrounds(detailSurfaceColor)
    } else {
        null
    }
    val detailContentColor = releaseForegrounds?.primary ?: absoluteContentColor
    val detailSecondaryColor = releaseForegrounds?.secondary ?: detailContentColor.copy(
        alpha = if (detailContentColor == Color.White) 0.78f else 0.72f,
    )
    val lightForeground = bestForegroundForArtworkSurface(detailContentColor) == Color.Black

    // Album/playlist typography keeps the strongest accessible relationship to
    // the exact lower-edge colour of the cover. Where the contrast budget lets
    // us, the foreground retains a trace of the artwork hue instead of snapping
    // to sterile pure black/white. Artist pages keep their existing treatment.
    val palette = sourcePalette.copy(
        background = detailSurfaceColor,
        wash = detailSurfaceColor,
        elevated = detailContentColor.copy(alpha = if (lightForeground) 0.14f else 0.10f),
        accent = detailContentColor,
        onBackground = detailContentColor,
        onBackgroundVariant = detailSecondaryColor,
        divider = detailContentColor.copy(alpha = if (lightForeground) 0.12f else 0.10f),
    )

    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()

    val songsCountText = if (songs.isNotEmpty()) {
        if (songs.size == 1) {
            stringResource(R.string.album_song_singular)
        } else {
            stringResource(R.string.album_songs_plural, songs.size)
        }
    } else null

    val albumLabel = stringResource(R.string.type_album)
    val playlistLabel = stringResource(R.string.type_playlist)
    val artistLabel = stringResource(R.string.type_artist)

    val credit = remember(page.subtitle, songs, songsCountText, albumLabel, playlistLabel, artistLabel) {
        page.headerLines(songs.size, songsCountText, albumLabel, playlistLabel, artistLabel).first.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, page.title, credit, canvasEnabled) {
        if (!canvasEnabled || page.type != BrowseType.ALBUM) {
            canvas = null
            return@LaunchedEffect
        }
        canvas = CanvasRepository.canvasForAlbum(page.title, credit) ?: canvas
    }

    val pageHaze = remember { HazeState() }
    // Portrait keeps the approved release geometry. On a wide tablet the art is
    // a fixed, maximised left pane, so the scrolling content no longer reserves
    // a screen-wide artwork footprint above itself.
    val artHeight = when {
        isTabletLandscape -> 0.dp
        // On portrait tablets/foldables the artwork owns the complete width,
        // with no extra sleeve inset that makes the image look cut away from
        // the edges. Phones keep the v1.5.1-derived spacing unchanged.
        isTabletPortrait -> screenWidth
        else -> screenWidth / if (isArtist) ARTIST_PHOTO_RATIO else SLEEVE_RATIO
    }
    val artworkHeight = when {
        isTabletLandscape -> tabletArtworkSize
        isTabletPortrait -> screenWidth
        isArtist -> artHeight
        else -> screenWidth
    }

    // Edge-to-edge detail pages put the system status bar directly over the
    // source artwork. Follow the actual vertical band that is underneath the
    // icons instead of guessing from the generated page background colour.
    val density = LocalDensity.current
    val artHeightPx = with(density) { artworkHeight.toPx() }
    val statusBarHeightPx = with(density) {
        WindowInsets.statusBarsIgnoringVisibility
            .asPaddingValues()
            .calculateTopPadding()
            .toPx()
    }
    val artworkStatusFraction by remember(listState, artHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex != 0 || artHeightPx <= 0f) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset.toFloat() / artHeightPx)
                    .coerceIn(0f, 0.9999f)
            }
        }
    }
    val artworkUnderStatusBar by remember(listState, artHeightPx, statusBarHeightPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset.toFloat() <
                    (artHeightPx - statusBarHeightPx).coerceAtLeast(0f)
        }
    }

    val statusBarDarkIcons = if (artworkUnderStatusBar) {
        sourcePalette.statusBarProfile.darkIconsAt(artworkStatusFraction)
    } else {
        palette.onBackground == Color.Black
    }
    val navigationBarDarkIcons = palette.onBackground == Color.Black

    SystemBarIcons(
        dark = statusBarDarkIcons,
        navigationDark = navigationBarDarkIcons,
    )

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        pageColor = detailSurfaceColor,
        indicatorColor = palette.accent,
        modifier = modifier.fillMaxSize(),
    ) {
    Box(Modifier.fillMaxSize()) {
        PageBackground(
            page = page,
            palette = palette,
            canvas = canvas,
            artHeight = artHeight,
            artworkHeight = artworkHeight,
            listState = listState,
            hazeState = pageHaze,
            bottomEdgeField = detailBottomEdgeField,
            isTabletLandscape = isTabletLandscape,
            isTabletPortrait = isTabletPortrait,
            tabletArtworkSize = tabletArtworkSize,
            modifier = Modifier.matchParentSize(),
        )

        MergeBand(
            surfaceColor = detailSurfaceColor,
            artHeight = artHeight,
            artworkHeight = artworkHeight,
            listState = listState,
            hazeState = pageHaze,
            isTabletLandscape = isTabletLandscape,
            isTabletPortrait = isTabletPortrait,
            tabletArtworkSize = tabletArtworkSize,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = tabletContentStart,
                    end = if (isTabletLandscape) DETAIL_TABLET_END_GUTTER else 0.dp,
                ),
            contentPadding = PaddingValues(
                top = if (isTabletLandscape) WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 88.dp else 0.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(
                        page = page,
                        palette = palette,
                        artHeight = artHeight,
                        listState = listState,
                    )
                } else {
                    ReleaseHeader(
                        page = page,
                        palette = palette,
                        artHeight = artHeight,
                        trackCount = songs.size,
                        songs = songs,
                        songsCountText = songsCountText,
                        albumLabel = albumLabel,
                        playlistLabel = playlistLabel,
                        artistLabel = artistLabel,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                        onDownload = onDownloadAll.takeUnless { page.browseId.startsWith("local:") },
                        onDownloadPlaylist = onDownloadPlaylist.takeIf { page.type == BrowseType.PLAYLIST },
                        onArtistClick = onArtistClick,
                        onToggleLibrary = onToggleLibrary,
                        albumPlaybackBlocked = albumPlaybackBlocked,
                        onToggleAlbumPlaybackBlock = onToggleAlbumPlaybackBlock,
                        onAlbumInfo = { albumInfoExpanded = !albumInfoExpanded },
                        onRateAlbum = { albumRateRequest++ },
                        onRatingBadgePosition = { releaseRatingStarBoundsOnScreen = it },
                        albumRating = albumCommunityRating,
                        onCollectionPlayNext = onCollectionPlayNext,
                        onCollectionAddToQueue = onCollectionAddToQueue,
                        listState = listState,
                    )
                }
            }

            if (page.type == BrowseType.ALBUM) {
                item(key = "album_info") {
                    AnimatedVisibility(
                        visible = albumInfoExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        AlbumInfoPanel(
                            page = page,
                            artist = page.headerArtists.firstOrNull()?.name ?: songs.firstOrNull()?.artist.orEmpty(),
                            palette = palette,
                            blocked = albumPlaybackBlocked,
                            onToggleBlock = onToggleAlbumPlaybackBlock,
                        )
                    }
                }
            }

            if (isArtist) {
                // Keep this keyed item present while the page is loading.
                // Removing/inserting it after the network response changes the
                // LazyColumn geometry and can make the visible header jump.
                item(key = "actions") {
                    if (songs.isNotEmpty()) {
                        ArtistActionArea(
                            artistName = page.title,
                            actionModifier = detailItemTopSegmentModifier(
                                listState = listState,
                                itemKey = "actions",
                                segmentHeight = 56.dp,
                            ),
                            palette = palette,
                            onPlay = { onSongClick(songs, 0) },
                            onShuffle = { onShuffle(songs) },
                            artistLiked = artistLiked,
                            artistBlocked = artistBlocked,
                            onToggleArtistLike = onToggleArtistLike,
                            onToggleArtistBlock = onToggleArtistBlock,
                        )
                    } else if (page.songs is UiState.Loading) {
                        ArtistActionsPlaceholder(
                            modifier = detailItemTopSegmentModifier(
                                listState = listState,
                                itemKey = "actions",
                                segmentHeight = 50.dp,
                            ),
                        )
                    }
                }
            }

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (isArtist) {
                    item(key = "artist-top-songs") {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        Column(
                            modifier = detailVerticalEdgeModifier(listState, "artist-top-songs")
                                .fillMaxWidth(),
                        ) {
                            // Orb v1.5.1 displays at most twenty Top songs here.
                            SectionHeading(stringResource(R.string.yt_ui_top_songs), palette)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(top.chunked(SONGS_PER_COLUMN)) { column ->
                                    Column(Modifier.fillParentMaxWidth(0.88f)) {
                                        column.forEach { song ->
                                            CompactSongRow(
                                                song = song,
                                                palette = palette,
                                                onClick = { onSongClick(top, top.indexOf(song)) },
                                                onLongPress = { onSongLongPress(song) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val numbered = page.type == BrowseType.ALBUM
                    itemsIndexed(
                        state.data,
                        key = { _, song -> "track-${song.videoId}" },
                    ) { index, song ->
                        if (numbered) {
                            // Keep SongRow's existing behaviour (tap, long press,
                            // swipe-to-queue, numbering, menu, etc.) and override
                            // only the text foregrounds for album tracks.
                            //
                            // SongRow's title follows the Material foreground,
                            // while its artist colour is already configurable.
                            // Feeding both from palette.accent makes every track
                            // title match the album title and every artist credit
                            // match the artist credit in ReleaseHeader.
                            //
                            // palette.accent is already contrast-corrected by
                            // ArtworkPalette: artwork accent when readable,
                            // otherwise the safer black/white foreground.
                            MaterialTheme(
                                colorScheme = MaterialTheme.colorScheme.copy(
                                    onBackground = palette.onBackground,
                                    onSurface = palette.onBackground,
                                    onSurfaceVariant = palette.onBackgroundVariant,
                                ),
                            ) {
                                SongRow(
                                    song = song,
                                    modifier = detailVerticalEdgeModifier(listState, "track-${song.videoId}"),
                                    onClick = { onSongClick(state.data, index) },
                                    onLongPress = { onSongLongPress(song) },
                                    onSwipeToQueue = if (directionalQueueSwipe) null else ({ onSongSwipe(song) }),
                                    onSwipeRight = if (directionalQueueSwipe) ({ onSongAddToQueue(song) }) else null,
                                    onSwipeLeft = if (directionalQueueSwipe) ({ onSongPlayNext(song) }) else null,
                                    rowBackground = Color.Transparent,
                                    trackNumber = index + 1,
                                    subtitleColor = palette.onBackgroundVariant,
                                    showSearchBadges = true,
                                    searchBadgesPreResolved = true,
                                    badgePageColor = palette.background,
                                    transparentDetailBadges = true,
                                )
                            }
                        } else {
                            if (page.type == BrowseType.PLAYLIST) {
                                // Keep user-created YouTube Music playlists on the
                                // same artwork-derived foreground as their title:
                                // track title, subtitle and trailing row actions.
                                MaterialTheme(
                                    colorScheme = MaterialTheme.colorScheme.copy(
                                        onBackground = palette.onBackground,
                                        onSurface = palette.onBackground,
                                        onSurfaceVariant = palette.onBackgroundVariant,
                                    ),
                                ) {
                                    SongRow(
                                        song = song.copy(
                                            thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl,
                                        ),
                                        modifier = detailVerticalEdgeModifier(listState, "track-${song.videoId}"),
                                        onClick = { onSongClick(state.data, index) },
                                        onLongPress = { onSongLongPress(song) },
                                        onSwipeToQueue = if (directionalQueueSwipe) null else ({ onSongSwipe(song) }),
                                        onSwipeRight = if (directionalQueueSwipe) ({ onSongAddToQueue(song) }) else null,
                                        onSwipeLeft = if (directionalQueueSwipe) ({ onSongPlayNext(song) }) else null,
                                        rowBackground = Color.Transparent,
                                        trackNumber = null,
                                        subtitleColor = palette.onBackgroundVariant,
                                        showSearchBadges = true,
                                        searchBadgesPreResolved = true,
                                        badgePageColor = palette.background,
                                        transparentDetailBadges = true,
                                    )
                                }
                            } else {
                                SongRow(
                                    song = song.copy(
                                        thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl,
                                    ),
                                    modifier = detailVerticalEdgeModifier(listState, "track-${song.videoId}"),
                                    onClick = { onSongClick(state.data, index) },
                                    onLongPress = { onSongLongPress(song) },
                                    onSwipeToQueue = if (directionalQueueSwipe) null else ({ onSongSwipe(song) }),
                                    onSwipeRight = if (directionalQueueSwipe) ({ onSongAddToQueue(song) }) else null,
                                    onSwipeLeft = if (directionalQueueSwipe) ({ onSongPlayNext(song) }) else null,
                                    rowBackground = Color.Transparent,
                                    trackNumber = null,
                                    subtitleColor = palette.onBackgroundVariant,
                                    showSearchBadges = true,
                                    searchBadgesPreResolved = true,
                                    badgePageColor = palette.background,
                                    transparentDetailBadges = true,
                                )
                            }
                        }
                        if (index < state.data.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }

            if (page.suggestedSongs.isNotEmpty()) {
                item(key = "suggested-heading") {
                    SectionHeading(stringResource(R.string.detail_suggested), palette)
                }
                itemsIndexed(
                    page.suggestedSongs,
                    key = { _, song -> "suggested-${song.videoId}" },
                ) { index, song ->
                    SuggestedSongRow(
                        song = song,
                        modifier = detailVerticalEdgeModifier(listState, "suggested-${song.videoId}"),
                        palette = palette,
                        onClick = { onSongClick(page.suggestedSongs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onAdd = { onAddSuggested(song) },
                    )
                    if (index < page.suggestedSongs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = palette.divider,
                        )
                    }
                }
            }

            items(
                page.sections,
                key = { shelf -> "section-${shelf.title}" },
            ) { shelf ->
                Column(
                    detailVerticalEdgeModifier(listState, "section-${shelf.title}")
                        .padding(top = 22.dp),
                ) {
                    SectionHeading(localizedYouTubeSectionTitle(shelf.title), palette)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(shelf.items) { item ->
                            SectionCard(
                                item = item,
                                palette = palette,
                                onClick = onSectionItemClick,
                            )
                        }
                    }
                }
            }
        }
        // Keep the album rating dialog composition independent from LazyColumn
        // item visibility, so the Action Button can open it even when the
        // information section is collapsed or scrolled out of composition.
        if (page.type == BrowseType.ALBUM) {
            AlbumInfoPanel(
                page = page,
                artist = page.headerArtists.firstOrNull()?.name ?: songs.firstOrNull()?.artist.orEmpty(),
                palette = palette,
                blocked = albumPlaybackBlocked,
                onToggleBlock = onToggleAlbumPlaybackBlock,
                showDetails = false,
                rateRequest = albumRateRequest,
                ratingLandingTargetOnScreen = releaseRatingStarBoundsOnScreen,
            )
        }
        }
    }
}

@Composable
private fun detailVerticalEdgeModifier(
    listState: LazyListState,
    itemKey: String,
): Modifier {
    val density = LocalDensity.current
    val minFadeDistancePx = with(density) { 84.dp.toPx() }
    val earlyTriggerPx = with(density) { 12.dp.toPx() }
    val edgeState by remember(listState, itemKey, minFadeDistancePx, earlyTriggerPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val itemStart = info.offset.toFloat()
            val itemEnd = itemStart + info.size.toFloat()
            val topEdge = layout.viewportStartOffset.toFloat()
            val bottomEdge = layout.viewportEndOffset.toFloat()
            // Start scaling while a row is entering the viewport, not only once
            // it has already crossed an edge. This makes album/playlist tracks
            // visibly grow into place from below and shrink on the way out.
            // Start the depth transition ~2dp before the previous threshold so
            // the row begins reacting just before it reaches the visible edge.
            val fadeDistance = maxOf(minFadeDistancePx, info.size * 1.08f) + earlyTriggerPx
            val topProgress = ((itemEnd - topEdge) / fadeDistance).coerceIn(0f, 1f)
            val bottomProgress = ((bottomEdge - itemStart) / fadeDistance).coerceIn(0f, 1f)
            val raw = minOf(topProgress, bottomProgress)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = when {
                itemEnd < topEdge + fadeDistance -> -1f
                itemStart > bottomEdge - fadeDistance -> 1f
                else -> 0f
            }
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }
    val edge = edgeState.first
    val direction = edgeState.second
    return Modifier
        .alpha(edge)
        .offset(y = 10.dp * direction * (1f - edge))
        .graphicsLayer {
            val scale = 0.80f + 0.20f * edge
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Applies the same depth/fade language used by detail rows to a visual segment
 * that lives inside the oversized `header` LazyColumn item. The header itself
 * also contains the artwork footprint, so scaling that whole item would make
 * the cover move. Instead we model only the title/action segment near the
 * header's bottom edge and leave the artwork completely untouched.
 *
 * This effect intentionally owns only the top edge: these header controls are
 * fully sized on the initial frame and begin shrinking only after real upward
 * scrolling carries them into the viewport's top boundary.
 */
@Composable
private fun detailHeaderBottomSegmentModifier(
    listState: LazyListState,
    bottomInset: Dp,
    segmentHeight: Dp,
): Modifier {
    val density = LocalDensity.current
    val minFadeDistancePx = with(density) { 84.dp.toPx() }
    val earlyTriggerPx = with(density) { 12.dp.toPx() }
    val bottomInsetPx = with(density) { bottomInset.toPx() }
    val segmentHeightPx = with(density) { segmentHeight.toPx() }
    val edgeState by remember(
        listState,
        minFadeDistancePx,
        earlyTriggerPx,
        bottomInsetPx,
        segmentHeightPx,
    ) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val header = layout.visibleItemsInfo.firstOrNull { it.key == "header" }
                ?: return@derivedStateOf Pair(1f, 0f)
            val topEdge = layout.viewportStartOffset.toFloat()
            val segmentEnd = header.offset.toFloat() + header.size.toFloat() - bottomInsetPx
            val fadeDistance = maxOf(minFadeDistancePx, segmentHeightPx * 1.08f) + earlyTriggerPx
            val raw = ((segmentEnd - topEdge) / fadeDistance).coerceIn(0f, 1f)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = if (segmentEnd < topEdge + fadeDistance) -1f else 0f
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }
    val edge = edgeState.first
    val direction = edgeState.second
    return Modifier
        .alpha(edge)
        .offset(y = 10.dp * direction * (1f - edge))
        .graphicsLayer {
            val scale = 0.80f + 0.20f * edge
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Variant for a compact visual segment anchored at the top of a normal lazy
 * item. Artist action controls use this instead of the item's full measured
 * height so expanding the information panel cannot change the fade threshold.
 */
@Composable
private fun detailItemTopSegmentModifier(
    listState: LazyListState,
    itemKey: String,
    segmentHeight: Dp,
): Modifier {
    val density = LocalDensity.current
    val minFadeDistancePx = with(density) { 84.dp.toPx() }
    val earlyTriggerPx = with(density) { 12.dp.toPx() }
    val segmentHeightPx = with(density) { segmentHeight.toPx() }
    val edgeState by remember(listState, itemKey, minFadeDistancePx, earlyTriggerPx, segmentHeightPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val topEdge = layout.viewportStartOffset.toFloat()
            val segmentEnd = info.offset.toFloat() + segmentHeightPx
            val fadeDistance = maxOf(minFadeDistancePx, segmentHeightPx * 1.08f) + earlyTriggerPx
            val raw = ((segmentEnd - topEdge) / fadeDistance).coerceIn(0f, 1f)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = if (segmentEnd < topEdge + fadeDistance) -1f else 0f
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }
    val edge = edgeState.first
    val direction = edgeState.second
    return Modifier
        .alpha(edge)
        .offset(y = 10.dp * direction * (1f - edge))
        .graphicsLayer {
            val scale = 0.80f + 0.20f * edge
            scaleX = scale
            scaleY = scale
        }
}

@Composable
private fun ReleaseHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    artHeight: Dp,
    trackCount: Int,
    songs: List<Song>,
    songsCountText: String?,
    albumLabel: String,
    playlistLabel: String,
    artistLabel: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: ((List<Song>) -> Unit)?,
    onDownloadPlaylist: ((List<Song>) -> Unit)?,
    onArtistClick: (String, String) -> Unit,
    onToggleLibrary: (() -> Unit)?,
    albumPlaybackBlocked: Boolean,
    onToggleAlbumPlaybackBlock: (() -> Unit)?,
    onAlbumInfo: () -> Unit,
    onRateAlbum: () -> Unit,
    onRatingBadgePosition: (Rect) -> Unit,
    albumRating: Float?,
    onCollectionPlayNext: (List<Song>) -> Unit,
    onCollectionAddToQueue: (List<Song>) -> Unit,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val ratingAnchorView = LocalView.current
    var actionsVisible by remember(page.browseId) { mutableStateOf(false) }
    var shareVisible by remember(page.browseId) { mutableStateOf(false) }
    val (subtitleCredit, meta) =
        page.headerLines(trackCount, songsCountText, albumLabel, playlistLabel, artistLabel)
    val firstSong = songs.firstOrNull()
    // Cards from Home, Search and artist shelves do not all carry the same
    // subtitle fields. Album tracks, however, always give us a reliable artist
    // fallback once the page has loaded, so the release header no longer loses
    // the artist merely because the entry card omitted it.
    val credit = subtitleCredit.ifBlank {
        if (page.type == BrowseType.ALBUM) firstSong?.artist.orEmpty() else ""
    }
    val isCollection =
        page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST
    val downloadScope = rememberCoroutineScope()
    val offlinePlaylists by com.music.orb.download.OfflinePlaylistStore.playlists.collectAsStateWithLifecycle()
    val isOfflinePlaylistSaved = page.type == BrowseType.PLAYLIST && offlinePlaylists.any { it.browseId == page.browseId }
    var collectionDownloadConfirm by remember(page.browseId) { mutableStateOf<CollectionDownloadConfirm?>(null) }
    val badges = rememberCollectionBadges(
        type = page.type,
        title = page.title,
        subtitle = credit,
        browseId = page.browseId,
        explicitHint = page.isExplicit,
        knownSongs = songs,
        allowProbe = false,
    )
    val loading = page.songs is UiState.Loading
    val hasActions = songs.isNotEmpty() || loading
    val activeDownloads by Downloads.active.collectAsStateWithLifecycle()
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    // Album and playlist headers expose the same collection-download state. The
    // previous implementation gated this on ALBUM, so playlists kept showing the
    // generic download glyph even while their tracks were downloading or after
    // every item had completed.
    val collectionTrackIds = remember(page.browseId, songs) {
        songs.asSequence().map(Song::videoId).filter(String::isNotBlank).distinct().toList()
    }
    val collectionDownloadInProgress = isCollection && collectionTrackIds.any { id ->
        activeDownloads[id] is DownloadState.Queued || activeDownloads[id] is DownloadState.Resolving ||
            activeDownloads[id] is DownloadState.Running
    }
    val collectionFullyDownloaded = isCollection &&
        collectionTrackIds.isNotEmpty() &&
        !collectionDownloadInProgress &&
        collectionTrackIds.all(savedDownloads::containsKey)
    Box(Modifier.fillMaxWidth()) {
        // A release always owns this footprint from the first composition.
        // Explicit may arrive with the tracks and Lossless is asynchronous, but
        // neither is allowed to move the buttons/header after the page is visible.
        val badgeFootprint = if (isCollection) RELEASE_BADGE_FOOTPRINT else 0.dp
        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP + badgeFootprint))

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = detailHeaderBottomSegmentModifier(
                    listState = listState,
                    // Account for the action row only while that slot exists.
                    bottomInset = if (hasActions) 78.dp else 14.dp,
                    // Covers the title, artist/meta lines and the reserved badge slot.
                    segmentHeight = 112.dp,
                ).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                )
                if (credit.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    HeaderCreditText(
                        credit = credit,
                        artists = page.headerArtists,
                        color = palette.onBackground,
                        onArtistClick = onArtistClick,
                        modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                    )
                }
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                        color = palette.onBackgroundVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                    )
                }
                if (isCollection) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RELEASE_BADGE_HEIGHT)
                            .padding(horizontal = HEADER_GUTTER),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReleaseBadges(
                                isExplicit = badges.isExplicit,
                                isLossless = badges.isLossless,
                                isHiResLossless = badges.isHiResLossless,
                                isHiQuality = badges.isHiQuality,
                                color = palette.onBackground,
                                plainExplicit = page.type == BrowseType.ALBUM,
                                glassPageColor = palette.background,
                                transparentLikeNowPlaying = true,
                            )
                            if (page.type == BrowseType.ALBUM || albumRating != null) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .orbBadgeGlassSurface(
                                            palette.background,
                                            cornerRadius = 5.dp,
                                            contentColorHint = palette.onBackground,
                                            transparentLikeNowPlaying = true,
                                        ),
                                    contentColor = palette.onBackground,
                                ) {
                                    Row(Modifier.height(RELEASE_BADGE_HEIGHT).padding(horizontal = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Star, contentDescription = null,
                                            modifier = Modifier.size(12.dp)
                                                .onGloballyPositioned { coordinates ->
                                                    val bounds = coordinates.boundsInWindow()
                                                    val screenOrigin = IntArray(2)
                                                    ratingAnchorView.rootView.getLocationOnScreen(screenOrigin)
                                                    // Capture exactly the on-screen 12dp star,
                                                    // including size, for the rating flight.
                                                    onRatingBadgePosition(Rect(
                                                        bounds.left + screenOrigin[0],
                                                        bounds.top + screenOrigin[1],
                                                        bounds.right + screenOrigin[0],
                                                        bounds.bottom + screenOrigin[1],
                                                    ))
                                                })
                                        Text(albumRating?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hasActions) {
                val library = page.library?.takeIf { onToggleLibrary != null }
                // This spacing is intentionally constant. Explicit/quality badges
                // may be confirmed after the first frame; changing this gap when
                // that happens would move the action row even though its reserved
                // header footprint is already stable.
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = detailHeaderBottomSegmentModifier(
                        listState = listState,
                        bottomInset = 14.dp,
                        segmentHeight = 50.dp,
                    ).fillMaxWidth(),
                ) {
                    if (songs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = if (page.type == BrowseType.PLAYLIST) 10.dp else if (onToggleAlbumPlaybackBlock != null) 12.dp else HEADER_GUTTER,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(
                                if (page.type == BrowseType.PLAYLIST) 6.dp else if (onToggleAlbumPlaybackBlock != null) 8.dp else 10.dp,
                                Alignment.CenterHorizontally,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (page.type == BrowseType.ALBUM) {
                                CircleIconButton(
                                    icon = if (library?.saved == true) OrbIcons.Check else OrbIcons.Plus,
                                    contentDescription = stringResource(
                                        if (library?.saved == true) R.string.song_action_remove_from_library
                                        else R.string.song_action_add_to_library,
                                    ),
                                    palette = palette,
                                    glass = true,
                                    controlSize = 40.dp,
                                    enabled = onToggleLibrary != null && page.library != null,
                                    selected = library?.saved == true,
                                    onClick = { onToggleLibrary?.invoke() },
                                )
                            }
                            CircleIconButton(
                                icon = OrbIcons.Shuffle,
                                contentDescription = stringResource(R.string.btn_shuffle),
                                palette = palette,
                                glass = true,
                                controlSize = if (page.type == BrowseType.PLAYLIST) 46.dp else 44.dp,
                                onClick = onShuffle,
                            )
                            PlayPill(
                                palette = palette,
                                glass = true,
                                onClick = onPlay,
                                modifier = Modifier.width(if (page.type == BrowseType.PLAYLIST) 112.dp else 104.dp),
                                controlHeight = if (page.type == BrowseType.PLAYLIST) 56.dp else 52.dp,
                                horizontalPadding = 10.dp,
                            )
                            if (page.type == BrowseType.ALBUM) {
                                CircleIconButton(
                                    icon = Icons.Rounded.Info,
                                    contentDescription = stringResource(R.string.album_info),
                                    palette = palette,
                                    glass = true,
                                    controlSize = 44.dp,
                                    onClick = { OrbHaptics.perform(context, OrbHaptics.Kind.ACTION); onAlbumInfo() },
                                )
                            }
                            CircleIconButton(
                                icon = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.collection_actions_more),
                                palette = palette,
                                glass = true,
                                controlSize = 40.dp,
                                onClick = {
                                    OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                                    actionsVisible = true
                                },
                            )
                        }
                    } else {
                        // Draw the loading controls *inside the exact slot* occupied
                        // by the real controls. The previous implementation reserved
                        // this slot and then drew a second skeleton below the header,
                        // which made the shadows jump upward when loading finished.
                        ReleaseActionsPlaceholder(
                            showLibrary = onToggleLibrary != null && page.type == BrowseType.ALBUM,
                            showDownload = false,
                            showPlaylistDownload = false,
                            isAlbum = page.type == BrowseType.ALBUM,
                        )
                    }
                }
            }
        }
    }

    if (actionsVisible) {
        val artistLink = page.headerArtists.firstOrNull()
        val artistId = artistLink?.artistId ?: firstSong?.artistId
        val artistName = artistLink?.name ?: firstSong?.artist.orEmpty()
        CollectionActionsSheet(
            page = page,
            pageSurfaceColor = palette.background,
            pagePrimaryTextColor = palette.onBackground,
            pageSecondaryTextColor = palette.onBackgroundVariant,
            savedInLibrary = page.library?.saved == true,
            libraryActionAvailable = onToggleLibrary != null && page.library != null,
            ratingAvailable = onToggleLibrary != null,
            artistAvailable = !artistId.isNullOrBlank() && artistName.isNotBlank(),
            onRate = { actionsVisible = false; onRateAlbum() },
            onDownload = {
                actionsVisible = false
                if (page.type == BrowseType.PLAYLIST && onDownloadPlaylist != null) {
                    onDownloadPlaylist(songs)
                } else {
                    onDownload?.invoke(songs)
                }
            },
            onToggleLibrary = { actionsVisible = false; onToggleLibrary?.invoke() },
            onPlayNext = { actionsVisible = false; if (songs.isNotEmpty()) onCollectionPlayNext(songs) },
            onAddToQueue = { actionsVisible = false; if (songs.isNotEmpty()) onCollectionAddToQueue(songs) },
            onOpenArtist = {
                actionsVisible = false
                if (!artistId.isNullOrBlank()) onArtistClick(artistId, artistName)
            },
            onShare = { actionsVisible = false; shareVisible = true },
            onDismiss = { actionsVisible = false },
        )
    }
    if (shareVisible) {
        val sharingSong = Song(
            videoId = page.browseId,
            title = page.title,
            artist = credit,
            thumbnailUrl = page.thumbnailUrl,
            albumName = if (page.type == BrowseType.ALBUM) albumLabel else playlistLabel,
            isExplicit = page.isExplicit,
        )
        val collectionLink = if (page.type == BrowseType.PLAYLIST) {
            "https://music.youtube.com/playlist?list=${android.net.Uri.encode(page.browseId.removePrefix("VL"))}"
        } else {
            "https://music.youtube.com/browse/${android.net.Uri.encode(page.browseId)}"
        }
        SongShareSheet(
            song = sharingSong,
            onDismiss = { shareVisible = false },
            shareLinkOverride = collectionLink,
            floatingTitleOverride = stringResource(
                if (page.type == BrowseType.ALBUM) R.string.collection_share_album
                else R.string.collection_share_playlist,
            ),
        )
    }

    collectionDownloadConfirm?.let { action ->
        val cancelInProgress = action == CollectionDownloadConfirm.CANCEL
        AlertDialog(
            onDismissRequest = { collectionDownloadConfirm = null },
            modifier = Modifier.orbGlassSurface(palette.background, cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    stringResource(
                        if (cancelInProgress) R.string.collection_download_cancel_title
                        else R.string.collection_download_remove_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (cancelInProgress) R.string.collection_download_cancel_message
                        else R.string.collection_download_remove_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        collectionDownloadConfirm = null
                        downloadScope.launch {
                            if (cancelInProgress) {
                                // Stop queued/running tracks first so none can
                                // finish after the user confirms cancellation.
                                songs.forEach { Downloads.cancel(it.videoId) }
                            }
                            // Remove only files that actually reached the saved
                            // state. Missing/pending tracks are naturally no-ops.
                            songs.forEach { song -> Downloads.delete(context, song.videoId) }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (cancelInProgress) R.string.collection_download_cancel_confirm
                            else R.string.collection_download_remove_confirm,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { collectionDownloadConfirm = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ArtistHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    artHeight: Dp,
    listState: LazyListState,
) {
    // The header is a public artist score, never the local device's personal ratings.
    val revision by AlbumRatingStore.revision.collectAsStateWithLifecycle()
    val average by produceState<Float?>(null, page.title, revision) {
        value = runCatching { CommunityRatingRepository.artistAverage(page.title) }.getOrNull()
    }

    Box(Modifier.fillMaxWidth()) {
        // Artist content needs less vertical drop than album / playlist
        // headers. Only the list content moves up; the hero artwork itself is
        // still sized and rendered from [artHeight] by PageBackground.
        Spacer(Modifier.fillMaxWidth().height(artHeight + ARTIST_HEADER_DROP))
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    detailHeaderBottomSegmentModifier(
                        listState = listState,
                        bottomInset = 14.dp,
                        segmentHeight = 96.dp,
                    ),
                )
                .padding(start = HEADER_GUTTER, end = HEADER_GUTTER, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.displayLarge,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val artistBadgeContent = palette.onBackground
                page.monthlyAudience?.takeIf { it.isNotBlank() }?.let { audience ->
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(RELEASE_BADGE_HEIGHT)
                            .clip(RoundedCornerShape(5.dp))
                            .orbBadgeGlassSurface(
                                palette.background,
                                cornerRadius = 5.dp,
                                contentColorHint = artistBadgeContent,
                                transparentLikeNowPlaying = true,
                            )
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = localizedYouTubeMetadata(audience),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            color = artistBadgeContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .height(RELEASE_BADGE_HEIGHT)
                        .clip(RoundedCornerShape(5.dp))
                        .orbBadgeGlassSurface(
                            palette.background,
                            cornerRadius = 5.dp,
                            contentColorHint = artistBadgeContent,
                            transparentLikeNowPlaying = true,
                        )
                        .padding(horizontal = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = artistBadgeContent,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        average?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = artistBadgeContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageBackground(
    page: DetailPage,
    palette: ArtworkPalette,
    canvas: CanvasArtwork?,
    artHeight: Dp,
    artworkHeight: Dp,
    listState: LazyListState,
    hazeState: HazeState,
    bottomEdgeField: ArtworkBottomEdgeField,
    isTabletLandscape: Boolean,
    isTabletPortrait: Boolean,
    tabletArtworkSize: Dp,
    modifier: Modifier = Modifier,
) {
    val artworkUrl = page.thumbnailUrl.artworkAt(HEADER_ART_PX)
    val surfaceColor = bottomEdgeField.solidColor

    Box(
        modifier
            .clipToBounds()
            .hazeSource(hazeState),
    ) {
        // The entire page settles into the colour sampled from the physical
        // artwork edge. A generated edge field continues into the content pane.
        Box(
            Modifier
                .matchParentSize()
                .background(surfaceColor),
        )

        if (isTabletLandscape) {
            // Large-screen landscape mirrors the media layouts in the reference:
            // one full-height artwork pane and one independent content
            // pane. The cover is fixed while the list on the right scrolls.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .width(tabletArtworkSize)
                    .fillMaxHeight()
                    .landscapeArtworkFade(),
            ) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopStart,
                    modifier = Modifier.matchParentSize(),
                )

                canvas?.let { clip ->
                    CanvasArtworkPlayer(
                        canvas = clip,
                        isPlaying = true,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            // Rotate the existing bottom-edge continuation onto the physical
            // right edge. This makes the art and content read as one surface
            // without stretching or duplicating recognisable artwork detail.
            bottomEdgeField.edgeFlow?.let { edgeFlow ->
                Image(
                    bitmap = edgeFlow,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .width(DETAIL_TABLET_COLOR_FLOW_WIDTH)
                        .fillMaxHeight()
                        .offset(x = tabletArtworkSize - DETAIL_TABLET_COLOR_FLOW_OVERLAP)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = artworkLandscapeFlowMask(),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(artworkHeight)
                    .offset {
                        IntOffset(
                            0,
                            listState.headerItemTop(artHeight.toPx()).roundToInt(),
                        )
                    },
            ) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    // Never crop the cover/photo. Fit lets the source consume
                    // the maximum available rectangle while preserving all of it.
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.matchParentSize(),
                )

                canvas?.let { clip ->
                    CanvasArtworkPlayer(
                        canvas = clip,
                        isPlaying = true,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            // Continue only the horizontal colour field that physically touches
            // the lower edge. It progressively mixes into [surfaceColor].
            bottomEdgeField.edgeFlow?.let { edgeFlow ->
                Image(
                    bitmap = edgeFlow,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DETAIL_COLOR_FLOW_HEIGHT)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (
                                    listState.headerItemTop(artHeight.toPx()) +
                                        artworkHeight.toPx() -
                                        (if (isTabletPortrait) {
                                            DETAIL_TABLET_COLOR_FLOW_OVERLAP
                                        } else {
                                            DETAIL_COLOR_FLOW_OVERLAP
                                        }).toPx()
                                    ).roundToInt(),
                            )
                        }
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.00f to Color.Transparent,
                                    0.16f to Color.White.copy(alpha = 0.30f),
                                    0.30f to Color.White.copy(alpha = 0.82f),
                                    0.42f to Color.White,
                                    1.00f to Color.White,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun MergeBand(
    surfaceColor: Color,
    artHeight: Dp,
    artworkHeight: Dp,
    listState: LazyListState,
    hazeState: HazeState,
    isTabletLandscape: Boolean,
    isTabletPortrait: Boolean,
    tabletArtworkSize: Dp,
) {
    val geometry = if (isTabletLandscape) {
        Modifier
            .width(DETAIL_MERGE_BAND)
            .fillMaxHeight()
            .offset(x = tabletArtworkSize - DETAIL_TABLET_MERGE_OVERLAP)
    } else {
        Modifier
            .fillMaxWidth()
            .height(DETAIL_MERGE_BAND)
            .offset {
                IntOffset(
                    x = 0,
                    y = (
                        listState.headerItemTop(artHeight.toPx()) +
                            artworkHeight.toPx() -
                            (if (isTabletPortrait) {
                                DETAIL_TABLET_MERGE_OVERLAP
                            } else {
                                DETAIL_MERGE_BAND * 0.46f
                            }).toPx()
                        ).roundToInt(),
                )
            }
    }

    ArtworkMergeBand(
        surfaceColor = surfaceColor,
        hazeState = hazeState,
        horizontal = isTabletLandscape,
        modifier = geometry,
    )
}

/**
 * Actual on-screen Y of the keyed detail header.
 *
 * The artwork lives behind the LazyColumn, so deriving its position from
 * firstVisibleItemScrollOffset only approximates where the header is. Pull
 * refresh, item replacement and a first-visible-item change can all make that
 * approximation diverge from the real header, which is what made the cover
 * slide upward while the title/actions appeared to stay put. Using the layout
 * engine's own item offset keeps artwork, blur and content locked together.
 */
private fun LazyListState.headerItemTop(fallbackHeightPx: Float): Float {
    layoutInfo.visibleItemsInfo
        .firstOrNull { it.key == "header" }
        ?.let { return it.offset.toFloat() }

    return if (firstVisibleItemIndex == 0) {
        -firstVisibleItemScrollOffset.toFloat()
    } else {
        -fallbackHeightPx * 2f
    }
}

@Composable
private fun ArtistActionsPlaceholder(
    modifier: Modifier = Modifier,
) {
    Column(modifier.graphicsLayer { alpha = 0.40f }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HEADER_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerBox(Modifier.size(50.dp), CircleShape)
            ShimmerBox(Modifier.width(130.dp).height(50.dp), CircleShape)
            ShimmerBox(Modifier.size(50.dp), CircleShape)
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun ReleaseActionsPlaceholder(
    showLibrary: Boolean,
    showDownload: Boolean,
    showPlaylistDownload: Boolean = false,
    isAlbum: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (!isAlbum) 10.dp else HEADER_GUTTER)
            .graphicsLayer { alpha = 0.40f },
        horizontalArrangement = Arrangement.spacedBy(if (!isAlbum) 6.dp else 10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLibrary) ShimmerBox(Modifier.size(40.dp), CircleShape)
        ShimmerBox(Modifier.size(if (isAlbum) 44.dp else 46.dp), CircleShape)
        ShimmerBox(
            Modifier.width(if (isAlbum) 104.dp else 112.dp)
                .height(if (isAlbum) 52.dp else 56.dp),
            CircleShape,
        )
        if (isAlbum) {
            ShimmerBox(Modifier.size(44.dp), CircleShape)
            ShimmerBox(Modifier.size(40.dp), CircleShape)
        } else ShimmerBox(Modifier.size(40.dp), CircleShape)
        if (showPlaylistDownload) ShimmerBox(Modifier.size(40.dp), CircleShape)
    }
}

@Composable
private fun ArtistActionArea(
    artistName: String,
    actionModifier: Modifier = Modifier,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    artistLiked: Boolean,
    artistBlocked: Boolean,
    onToggleArtistLike: (() -> Unit)?,
    onToggleArtistBlock: (() -> Unit)?,
) {
    var expanded by remember(artistName) { mutableStateOf(false) }
    var info by remember(artistName) { mutableStateOf<WikipediaArtistInfoRepository.ArtistInfo?>(null) }
    var infoLoading by remember(artistName) { mutableStateOf(false) }
    var infoAttempted by remember(artistName) { mutableStateOf(false) }

    LaunchedEffect(expanded, artistName) {
        if (!expanded || infoAttempted) return@LaunchedEffect
        infoAttempted = true
        infoLoading = true
        info = WikipediaArtistInfoRepository.summary(artistName)
        infoLoading = false
    }

    Row(
        modifier = actionModifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = OrbIcons.Shuffle,
            contentDescription = stringResource(R.string.btn_shuffle),
            palette = palette,
            controlSize = 46.dp,
            onClick = onShuffle,
        )
        PlayPill(
            palette = palette,
            modifier = Modifier.width(104.dp),
            controlHeight = 56.dp,
            horizontalPadding = 10.dp,
            onClick = onPlay,
        )
        CircleIconButton(
            icon = Icons.Rounded.Info,
            contentDescription = stringResource(R.string.artist_info),
            palette = palette,
            controlSize = 46.dp,
            onClick = { expanded = !expanded },
        )
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        ArtistInfoPanel(
            palette = palette,
            info = info,
            loading = infoLoading,
            artistLiked = artistLiked,
            artistBlocked = artistBlocked,
            onToggleArtistLike = onToggleArtistLike,
            onToggleArtistBlock = onToggleArtistBlock,
        )
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun ArtistInfoPanel(
    palette: ArtworkPalette,
    info: WikipediaArtistInfoRepository.ArtistInfo?,
    loading: Boolean,
    artistLiked: Boolean,
    artistBlocked: Boolean,
    onToggleArtistLike: (() -> Unit)?,
    onToggleArtistBlock: (() -> Unit)?,
) {
    val staticText = when {
        loading -> stringResource(R.string.artist_info_loading)
        info == null -> stringResource(R.string.artist_info_unavailable)
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HEADER_GUTTER, end = HEADER_GUTTER, top = 16.dp),
    ) {
        StableTypewriterText(
            text = info?.extract ?: staticText,
            animate = !loading && info != null,
            color = palette.onBackground,
        )
        if (info != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.artist_info_source),
                style = MaterialTheme.typography.labelSmall,
                color = palette.onBackgroundVariant,
            )
        }
        if (onToggleArtistLike != null || onToggleArtistBlock != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onToggleArtistLike?.let { action ->
                    ArtistPreferenceIconButton(
                        icon = Icons.Rounded.Favorite,
                        contentDescription = stringResource(
                            if (artistLiked) R.string.artist_liked else R.string.artist_like,
                        ),
                        selected = artistLiked,
                        palette = palette,
                        onClick = action,
                    )
                }
                onToggleArtistBlock?.let { action ->
                    ArtistPreferenceButton(
                        text = stringResource(if (artistBlocked) R.string.artist_blocked else R.string.artist_block),
                        icon = Icons.Rounded.Block,
                        selected = artistBlocked,
                        palette = palette,
                        onClick = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistPreferenceIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    palette: ArtworkPalette,
    onClick: () -> Unit,
) {
    // Release-page actions inherit the same foreground used by the page title/body.
    // Expressive Glass defines the material, while the page palette defines the glyph.
    val content = palette.onBackground
    val iconTint = if (selected) Color.Red else content
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "artistPreferenceGlassScale",
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .albumGlassSurface(palette.background, cornerRadius = 14.dp, transparentLikeNowPlaying = true)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ArtistPreferenceButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep action labels/icons chromatically continuous with the artist page text.
    val content = palette.onBackground
    val buttonShape = if (selected) CircleShape else RoundedCornerShape(14.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 390f),
        label = "artistPreferencePillGlassScale",
    )
    Row(
        modifier = modifier
            .height(46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(buttonShape)
            .albumGlassSurface(
                palette.background,
                cornerRadius = if (selected) null else 14.dp,
                transparentLikeNowPlaying = true,
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun PlayPill(
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 32.dp,
    controlHeight: Dp = 50.dp,
    glass: Boolean = true,
) {
    // Retain the private caller signature while all detail pages share the
    // same lightweight transparent glass as Now Playing.
    @Suppress("UNUSED_VARIABLE") val keepGlassParameter = glass
    val buttonContent = palette.onBackground
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 410f),
        label = "releasePlayExpressiveGlassScale",
    )

    Row(
        modifier = modifier
            .height(controlHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .albumGlassSurface(palette.background, transparentLikeNowPlaying = true)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = OrbIcons.Play,
            contentDescription = null,
            tint = buttonContent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.album_play),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = buttonContent,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: ArtworkPalette,
    enabled: Boolean = true,
    selected: Boolean = false,
    controlSize: Dp = 50.dp,
    glass: Boolean = true,
    onClick: () -> Unit,
) {
    // Retain transparent glass even for selected/blocked toggles.
    @Suppress("UNUSED_VARIABLE") val keepGlassParameter = glass
    val buttonContent = palette.onBackground
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 440f),
        label = "releaseIconExpressiveGlassScale",
    )

    Box(
        modifier = Modifier
            .size(controlSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .albumGlassSurface(palette.background, transparentLikeNowPlaying = true)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = buttonContent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ReleaseFooter(songs: List<Song>, palette: ArtworkPalette) {
    Text(
        text = songs.playtimeSummary(),
        style = MaterialTheme.typography.labelMedium,
        color = palette.onBackgroundVariant,
        modifier = Modifier.padding(start = HEADER_GUTTER, end = HEADER_GUTTER, top = 18.dp),
    )
}

@Composable
private fun SectionHeading(title: String, palette: ArtworkPalette) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = palette.onBackground,
        modifier = Modifier.padding(
            start = PAGE_GUTTER, end = PAGE_GUTTER, top = 10.dp, bottom = 8.dp,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactSongRow(
    song: Song,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quality = rememberTrackLosslessBadgeState(song, allowProbe = false)
    var launchBounds by remember(song.videoId) { mutableStateOf<Rect?>(null) }
    val launchAwareClick = {
        launchBounds?.let(NowPlayingLaunchOriginRegistry::record)
        onClick()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = launchAwareClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.positionInRoot()
                    launchBounds = Rect(
                        left = topLeft.x,
                        top = topLeft.y,
                        right = topLeft.x + coordinates.size.width,
                        bottom = topLeft.y + coordinates.size.height,
                    )
                }
                .clip(RoundedCornerShape(7.dp))
                .thumbnailBorder(RoundedCornerShape(7.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.isExplicit || quality.isLossless || quality.isHiQuality) {
                    Spacer(Modifier.width(6.dp))
                    CompactMediaBadges(
                        isExplicit = song.isExplicit,
                        isLossless = quality.isLossless,
                        isHiResLossless = quality.isHiResLossless,
                        isHiQuality = quality.isHiQuality,
                        color = palette.onBackground,
                        glassPageColor = palette.background,
                        transparentLikeNowPlaying = true,
                    )
                }
            }
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        DownloadStatusGlyph(
            videoId = song.videoId,
            tint = palette.onBackgroundVariant,
        )
        song.durationText?.let { duration ->
            Spacer(Modifier.width(5.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.collection_actions_more),
                tint = palette.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedSongRow(
    song: Song,
    modifier: Modifier = Modifier,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAdd: () -> Unit,
) {
    var launchBounds by remember(song.videoId) { mutableStateOf<Rect?>(null) }
    val launchAwareClick = {
        launchBounds?.let(NowPlayingLaunchOriginRegistry::record)
        onClick()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = launchAwareClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.positionInRoot()
                    launchBounds = Rect(
                        left = topLeft.x,
                        top = topLeft.y,
                        right = topLeft.x + coordinates.size.width,
                        bottom = topLeft.y + coordinates.size.height,
                    )
                }
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitTitle(
                text = song.title,
                isExplicit = song.isExplicit,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                badgePageColor = palette.background,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.16f))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.song_action_add_to_playlist),
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: (ShelfItem) -> Unit,
) {
    val isCollection = item.type == BrowseType.ALBUM || item.type == BrowseType.PLAYLIST
    val badges = rememberCollectionBadges(
        type = item.type,
        title = item.title,
        subtitle = item.subtitle,
        browseId = item.browseId,
        explicitHint = item.isExplicit,
        allowProbe = false,
    )

    Column(
        modifier = Modifier
            .width(SHELF_CARD_WIDTH)
            .clickable { onClick(item.copy(isExplicit = badges.isExplicit)) },
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .width(SHELF_CARD_WIDTH)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .thumbnailBorder(RoundedCornerShape(10.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.height(8.dp))
        ExplicitTitle(
            text = item.title,
            isExplicit = item.isExplicit && !isCollection,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            badgePageColor = palette.background,
        )
        Text(
            text = localizedYouTubeMetadata(item.subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isCollection) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReleaseBadges(
                        isExplicit = badges.isExplicit,
                        isLossless = badges.isLossless,
                        isHiResLossless = badges.isHiResLossless,
                        isHiQuality = badges.isHiQuality,
                        color = palette.onBackground,
                        glassPageColor = palette.background,
                        transparentLikeNowPlaying = true,
                    )
                    if (item.type == BrowseType.ALBUM) {
                        AlbumRatingBadge(
                            albumId = item.browseId,
                            glassPageColor = palette.background,
                            contentColorOverride = palette.onBackground,
                            transparentLikeNowPlaying = true,
                        )
                    }
                }
            }
        }
    }
}

private data class HeaderArtistRange(
    val start: Int,
    val endExclusive: Int,
    val artist: ArtistLink,
)

/**
 * Header credit with click targets only where YouTube explicitly linked an
 * artist page. A playlist creator therefore never inherits the first track's
 * artist id, while an actual artist credit remains individually clickable.
 */
@Composable
private fun HeaderCreditText(
    credit: String,
    artists: List<ArtistLink>,
    color: Color,
    onArtistClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranges = remember(credit, artists) { headerArtistRanges(credit, artists) }

    if (ranges.isEmpty()) {
        Text(
            text = credit,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    val annotated = remember(credit, ranges) {
        buildAnnotatedString {
            append(credit)
            ranges.forEachIndexed { index, range ->
                addStringAnnotation(
                    tag = "orb_header_artist",
                    annotation = index.toString(),
                    start = range.start,
                    end = range.endExclusive,
                )
            }
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.titleMedium.copy(
            color = color,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onClick = { offset ->
            val annotation = annotated
                .getStringAnnotations(
                    tag = "orb_header_artist",
                    start = offset,
                    end = offset,
                )
                .firstOrNull()
                ?: return@ClickableText
            val range = annotation.item.toIntOrNull()
                ?.let(ranges::getOrNull)
                ?: return@ClickableText
            onArtistClick(range.artist.artistId, range.artist.name)
        },
    )
}

private fun headerArtistRanges(
    credit: String,
    artists: List<ArtistLink>,
): List<HeaderArtistRange> {
    if (credit.isBlank() || artists.isEmpty()) return emptyList()
    val occupied = BooleanArray(credit.length)
    val ranges = mutableListOf<HeaderArtistRange>()

    artists
        .filter { it.name.isNotBlank() && it.artistId.isNotBlank() }
        .distinctBy { it.artistId to it.name.lowercase() }
        .sortedByDescending { it.name.length }
        .forEach { artist ->
            var from = 0
            while (from < credit.length) {
                val start = credit.indexOf(artist.name, startIndex = from, ignoreCase = true)
                if (start < 0) break
                val end = start + artist.name.length
                val boundaryBefore = start == 0 || !credit[start - 1].isLetterOrDigit()
                val boundaryAfter = end == credit.length || !credit[end].isLetterOrDigit()
                val free = (start until end).none { occupied[it] }
                if (boundaryBefore && boundaryAfter && free) {
                    (start until end).forEach { occupied[it] = true }
                    ranges += HeaderArtistRange(start, end, artist)
                    break
                }
                from = start + 1
            }
        }

    return ranges.sortedBy { it.start }
}

private fun DetailPage.headerLines(
    trackCount: Int,
    songsCountText: String?,
    albumLabel: String,
    playlistLabel: String,
    artistLabel: String
): Pair<String, String> {
    val parts = subtitle
        .split("•", "·")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val year = parts.lastOrNull { part ->
        part.length == 4 && part.all(Char::isDigit)
    }

    // The backend commonly returns English type words ("Album", "Playlist")
    // even when Android is rendering a translated UI. Treat those words as
    // semantic metadata, never as display text.
    val rawKind = parts.firstOrNull { it.lowercase() in KIND_WORDS }

    val credit = parts
        .filter { it != year && it != rawKind }
        .map { it.replace(TRACK_TALLY_SUFFIX, "").trim().trimEnd(',', '•', '·').trim() }
        .filter { it.isNotEmpty() && !it.matches(TRACK_TALLY_ONLY) }
        .joinToString(", ")

    val fallbackTypeLabel = when (type) {
        BrowseType.ALBUM -> albumLabel
        BrowseType.PLAYLIST -> playlistLabel
        BrowseType.ARTIST -> artistLabel
        BrowseType.OTHER -> null
    }

    val localizedKind = when (rawKind?.lowercase()) {
        "album" -> albumLabel
        "playlist" -> playlistLabel
        "artist" -> artistLabel
        // These names are conventionally used as-is in the supported locales.
        "single" -> "Single"
        "ep" -> "EP"
        // Keep less common backend kinds instead of deleting information.
        null -> fallbackTypeLabel
        else -> rawKind
    }

    val meta = listOfNotNull(
        localizedKind ?: fallbackTypeLabel,
        year,
        songsCountText,
    ).joinToString(" • ").uppercase()

    return credit to meta
}

private val TRACK_TALLY_SUFFIX = Regex(
    """(?:[,·•]\s*)?\d[\d.,]*\s*(?:tracks?|songs?|vídeos?|videos?|músicas?|faixas?|canci(?:ón|ones))\s*$""",
    RegexOption.IGNORE_CASE,
)
private val TRACK_TALLY_ONLY = Regex(
    """\d[\d.,]*\s*(?:tracks?|songs?|vídeos?|videos?|músicas?|faixas?|canci(?:ón|ones))""",
    RegexOption.IGNORE_CASE,
)

private val KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
)

@Composable
private fun List<Song>.playtimeSummary(): String {
    val count = if (size == 1) {
        stringResource(R.string.album_song_singular)
    } else {
        stringResource(R.string.album_songs_plural, size)
    }

    val minutes = sumOf { it.durationText.toSeconds() } / 60
    return when {
        minutes <= 0 -> count
        minutes < 60 -> {
            val minText = if (minutes == 1) stringResource(R.string.album_minutes_singular) else stringResource(R.string.album_minutes_plural, minutes)
            "$count, $minText"
        }
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            val hourText = if (hours == 1) stringResource(R.string.album_hour_singular) else stringResource(R.string.album_hours_plural, hours)
            if (rest == 0) {
                "$count, $hourText"
            } else {
                val minText = if (rest == 1) stringResource(R.string.album_minutes_singular) else stringResource(R.string.album_minutes_plural, rest)
                "$count, $hourText $minText"
            }
        }
    }
}

private fun String?.toSeconds(): Int {
    val parts = this?.split(":")?.map { it.trim().toIntOrNull() ?: return 0 } ?: return 0
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0
    }
}

/** Keep the share frame opacity, with a subtle tint from the actual page surface. */
/** Content follows the actual neutral-dominant Expressive Glass surface. */
@Composable
internal fun albumGlassContentColor(pageColor: Color): Color =
    orbExpressiveGlassContentColor(pageColor)

/**
 * Release and artist pages opt into the light transparent glass seen in Now Playing.
 * Other callers (e.g. profile) keep their previous Expressive Glass material.
 */
internal fun Modifier.albumGlassSurface(
    pageColor: Color,
    cornerRadius: Dp? = null,
    transparentLikeNowPlaying: Boolean = false,
): Modifier = if (transparentLikeNowPlaying) {
    orbNowPlayingStyleGlass(contentColor = bestForegroundForArtworkSurface(pageColor))
} else {
    orbGlassSurface(pageColor = pageColor, cornerRadius = cornerRadius, drawRim = false)
}
