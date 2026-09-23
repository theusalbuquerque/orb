package com.music.orb.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import com.music.orb.R
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.ui.icons.BitChordIcons
import coil3.compose.AsyncImage
import com.music.orb.data.model.CARD_ART_PX
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.HEADER_ART_PX
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.Song
import com.music.orb.data.model.UiState
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.model.artworkAt
import com.music.orb.ui.components.CompactMediaBadges
import com.music.orb.ui.components.LosslessBadgeState
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.SHELF_CARD_WIDTH
import com.music.orb.ui.components.ExplicitTitle
import com.music.orb.ui.components.ReleaseBadges
import com.music.orb.ui.components.rememberCollectionBadges
import com.music.orb.ui.components.rememberTrackLosslessBadgeState
import com.music.orb.ui.components.SignInBanner
import com.music.orb.ui.components.feedMoreSkeleton
import com.music.orb.ui.components.feedSkeleton
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.flavor.OrbFlavorUi
import com.music.orb.ui.player.NowPlayingLaunchOriginRegistry

private val HOME_SHELF_BOTTOM_SPACING = 38.dp
private val RELEASE_BADGE_SLOT_HEIGHT = 24.dp
internal val PAGE_AURA_HEIGHT = 430.dp
internal const val HOME_RECOMMENDATIONS_SHELF_TITLE = "__orb_recommendations__"
internal const val HOME_TOP_ARTISTS_SHELF_TITLE = "__orb_top_artists__"
internal const val HOME_FAVORITE_ALBUM_RELEASES_SHELF_TITLE = "__orb_favorite_album_releases__"

// Strong full-card scrim for hero cards. The lower half of the artwork is
// progressively darkened so title, artist and badges share one legible backdrop.
private val HERO_CARD_TEXT_SCRIM = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.42f to Color.Transparent,
        0.58f to Color.Black.copy(alpha = 0.16f),
        0.70f to Color.Black.copy(alpha = 0.40f),
        0.82f to Color.Black.copy(alpha = 0.68f),
        0.92f to Color.Black.copy(alpha = 0.84f),
        1.0f to Color.Black.copy(alpha = 0.94f),
    ),
)
private val HERO_CARD_TITLE_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.88f),
    offset = Offset(0f, 2.4f),
    blurRadius = 12f,
)
private val HERO_CARD_SUBTITLE_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.80f),
    offset = Offset(0f, 2.0f),
    blurRadius = 10f,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onRankedSongClick: ((List<Song>, Int) -> Unit)? = null,
    onRecommendationClick: ((ShelfItem) -> Unit)? = null,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String = stringResource(R.string.title_listen_now),
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    // Explore doesn't page — only Home has a continuation worth following.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    /** Explore compact song cards use the full Explicit/Lossless row below artist. */
    fullTrackBadgesInShelves: Boolean = false,
    /** Home owns its greeting and filters in the glass header, not in the list. */
    homeHeaderMode: Boolean = false,
    /** Explore owns its compact title/search glass header, not an in-list title. */
    exploreHeaderMode: Boolean = false,
    /** Only For You uses the large editorial lead card. Other hub tabs use shelves uniformly. */
    heroFirstShelf: Boolean = true,
) {
    val homeAuraEnabled by AppSettings.homeAuraEnabled.collectAsStateWithLifecycle()

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (OrbFlavorUi.expressive && ((homeHeaderMode && homeAuraEnabled) || exploreHeaderMode)) {
                HomeHeaderAura(
                    scrollOffsetPx = listState.auraScrollOffsetPx(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PAGE_AURA_HEIGHT)
                        .align(Alignment.TopCenter),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                if (!homeHeaderMode && !exploreHeaderMode) {
                    item {
                        if (OrbFlavorUi.expressive) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(122.dp)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
                                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.26f),
                                                Color.Transparent,
                                            ),
                                            radius = 720f,
                                        ),
                                    ),
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        fontSize = 40.sp,
                                        lineHeight = 43.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(horizontal = PAGE_GUTTER, vertical = 18.dp),
                                )
                            }
                        } else {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                            )
                        }
                    }
                }

                if (!signedIn && onSignIn != null) {
                    item {
                        SignInBanner(
                            onSignIn = onSignIn,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                when (state) {
                    is UiState.Loading -> feedSkeleton()
                    is UiState.Error -> item {
                        MessageState(
                            state.message,
                            actionLabel = stringResource(R.string.beta_ui_retry),
                            onAction = onRetry,
                        )
                    }
                    is UiState.Success -> {
                        itemsIndexedShelves(
                            shelves = state.data,
                            listState = listState,
                            onItemClick = onItemClick,
                            onItemLongPress = onItemLongPress,
                            onRankedSongClick = onRankedSongClick,
                            onRecommendationClick = onRecommendationClick,
                            fullTrackBadgesInShelves = fullTrackBadgesInShelves,
                            expressive = OrbFlavorUi.expressive,
                            heroFirstShelf = heroFirstShelf,
                        )
                        if (loadingMore) feedMoreSkeleton()
                    }
                }
            }
        }
    }

    if (onLoadMore != null && state is UiState.Success) {
        val nearEnd by remember {
            derivedStateOf {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                layout.totalItemsCount > 0 && last >= layout.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, loadingMore) {
            if (nearEnd && !loadingMore) onLoadMore()
        }
    }
}

@Composable
internal fun HomeHeaderAura(
    modifier: Modifier = Modifier,
    scrollOffsetPx: Int = 0,
) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    val background = MaterialTheme.colorScheme.background

    // The page aura is intentionally a fixed backdrop. It never drifts sideways
    // or runs an internal clock; the only motion comes from the page scroll and
    // is applied as a vertical translation to the whole cached composition.
    val baseColors = remember(primary, tertiary, secondary) {
        listOf(
            primary.copy(alpha = 0.546f),
            tertiary.copy(alpha = 0.322f),
            secondary.copy(alpha = 0.168f),
            Color.Transparent,
        )
    }
    val primaryAuraColors = remember(primary) {
        listOf(
            primary.copy(alpha = 0.658f),
            primary.copy(alpha = 0.294f),
            primary.copy(alpha = 0.084f),
            Color.Transparent,
        )
    }
    val tertiaryAuraColors = remember(tertiary) {
        listOf(
            tertiary.copy(alpha = 0.532f),
            tertiary.copy(alpha = 0.217f),
            tertiary.copy(alpha = 0.056f),
            Color.Transparent,
        )
    }
    val secondaryAuraColors = remember(secondary) {
        listOf(
            secondary.copy(alpha = 0.392f),
            secondary.copy(alpha = 0.126f),
            Color.Transparent,
        )
    }
    val fadeStops = remember(background) {
        arrayOf(
            0.00f to background.copy(alpha = 0f),
            0.42f to background.copy(alpha = 0f),
            0.54f to background.copy(alpha = 0.03f),
            0.66f to background.copy(alpha = 0.10f),
            0.77f to background.copy(alpha = 0.23f),
            0.86f to background.copy(alpha = 0.42f),
            0.93f to background.copy(alpha = 0.65f),
            0.98f to background.copy(alpha = 0.86f),
            1.00f to background,
        )
    }

    val maxTravelPx = with(LocalDensity.current) { PAGE_AURA_HEIGHT.toPx() }
    val baseLayer = remember(baseColors) { Modifier.auraLinearLayer(baseColors) }
    val primaryLayer = remember(primaryAuraColors) {
        Modifier.auraRadialLayer(primaryAuraColors, centerX = 0.20f, centerY = 0.13f, radiusWidth = 0.84f)
    }
    val tertiaryLayer = remember(tertiaryAuraColors) {
        Modifier.auraRadialLayer(tertiaryAuraColors, centerX = 0.84f, centerY = 0.08f, radiusWidth = 0.72f)
    }
    val secondaryLayer = remember(secondaryAuraColors) {
        Modifier.auraRadialLayer(secondaryAuraColors, centerX = 0.52f, centerY = 0.55f, radiusWidth = 0.82f)
    }
    val veilLayer = remember(fadeStops) { Modifier.auraVerticalLayer(fadeStops) }

    Box(
        modifier = modifier.graphicsLayer {
            translationX = 0f
            translationY = -scrollOffsetPx.toFloat().coerceIn(0f, maxTravelPx)
        },
    ) {
        Box(Modifier.matchParentSize().then(baseLayer))
        Box(Modifier.matchParentSize().then(primaryLayer))
        Box(Modifier.matchParentSize().then(tertiaryLayer))
        Box(Modifier.matchParentSize().then(secondaryLayer))
        Box(Modifier.matchParentSize().then(veilLayer))
    }
}


private fun Modifier.auraLinearLayer(colors: List<Color>): Modifier = drawWithCache {
    val brush = Brush.linearGradient(
        colors = colors,
        start = Offset(size.width * -0.12f, size.height * -0.04f),
        end = Offset(size.width * 1.10f, size.height * 0.94f),
    )
    onDrawBehind { drawRect(brush = brush) }
}

private fun Modifier.auraRadialLayer(
    colors: List<Color>,
    centerX: Float,
    centerY: Float,
    radiusWidth: Float,
): Modifier = drawWithCache {
    val brush = Brush.radialGradient(
        colors = colors,
        center = Offset(size.width * centerX, size.height * centerY),
        radius = size.width * radiusWidth,
    )
    onDrawBehind { drawRect(brush = brush) }
}

private fun Modifier.auraVerticalLayer(colorStops: Array<Pair<Float, Color>>): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(colorStops = colorStops)
    onDrawBehind { drawRect(brush = brush) }
}

@Composable
internal fun LazyListState.auraScrollOffsetPx(): Int {
    val offset by remember(this) {
        derivedStateOf {
            if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset else Int.MAX_VALUE
        }
    }
    return offset
}

/**
 * The lead shelf gets Apple's full-bleed treatment — near-page-width cards that
 * page sideways — and the rest fall back to the compact grid of square cards.
 */
@Composable
private fun homeVerticalEdgeModifier(
    listState: LazyListState,
    itemKey: String,
): Modifier {
    val density = LocalDensity.current
    val minDistancePx = with(density) { 96.dp.toPx() }
    // The Home shelves physically scroll under the frosted header. Trigger the
    // scale/fade at that visible lower edge instead of the padded LazyColumn
    // origin, so For You / Albums / Trending / Playlists behave consistently.
    val headerOcclusionPx = with(density) { 144.dp.toPx() }
    val edgeState by remember(listState, itemKey, minDistancePx, headerOcclusionPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val start = info.offset.toFloat()
            val end = start + info.size.toFloat()
            // Convert the fixed header's screen-relative lower edge to the
            // LazyColumn's padded content coordinates. Treating 144dp as an
            // absolute item offset double-counted Home's large top padding.
            val top = layout.viewportStartOffset.toFloat() + headerOcclusionPx
            val bottom = layout.viewportEndOffset.toFloat()
            val distance = maxOf(minDistancePx, (info.size * 0.55f).coerceAtMost(minDistancePx * 1.8f))
            // Do not shrink a Home section until its leading edge has actually
            // crossed under the frosted header. The previous end-based formula
            // could begin the effect while the whole section was still visible.
            val topProgress = if (start >= top) {
                1f
            } else {
                ((end - top) / info.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
            val bottomProgress = ((bottom - start) / distance).coerceIn(0f, 1f)
            val raw = minOf(topProgress, bottomProgress)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = when {
                start < top -> -1f
                start > bottom - distance -> 1f
                else -> 0f
            }
            Pair(smooth.coerceAtLeast(0.05f), direction)
        }
    }
    val edge = edgeState.first
    val direction = edgeState.second
    return Modifier
        .alpha(edge)
        .graphicsLayer {
            val scale = 0.86f + 0.14f * edge
            scaleX = scale
            scaleY = scale
            translationY = with(density) { (8.dp * direction * (1f - edge)).toPx() }
        }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShelves(
    shelves: List<HomeShelf>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onRankedSongClick: ((List<Song>, Int) -> Unit)?,
    onRecommendationClick: ((ShelfItem) -> Unit)?,
    fullTrackBadgesInShelves: Boolean,
    expressive: Boolean,
    heroFirstShelf: Boolean,
) {
    shelves.forEachIndexed { index, shelf ->
        val itemKey = shelf.title + index
        item(key = itemKey) {
            Box(modifier = homeVerticalEdgeModifier(listState, itemKey)) {
            if (shelf.title == HOME_FAVORITE_ALBUM_RELEASES_SHELF_TITLE) {
                FavoriteAlbumReleaseShelf(
                    shelf = shelf,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    expressive = expressive,
                )
            } else if (shelf.title == HOME_TOP_ARTISTS_SHELF_TITLE) {
                ArtistProfileShelf(
                    shelf = shelf,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    expressive = expressive,
                )
            } else if (shelf.title.lowercase().startsWith("top 10 ")) {
                RankedShelf(
                    shelf = shelf,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    onRankedSongClick = onRankedSongClick,
                    expressive = expressive,
                )
            } else if (
                heroFirstShelf &&
                shelf.title == HOME_RECOMMENDATIONS_SHELF_TITLE
            ) {
                RotatingRecommendationShelf(
                    shelf = shelf,
                    onItemClick = onRecommendationClick ?: onItemClick,
                    onItemLongPress = onItemLongPress,
                    expressive = expressive,
                )
            } else if (index == 0 && heroFirstShelf) {
                // If Suggestions is still resolving, Recently played must stay a
                // normal shelf. Promoting it to the legacy hero layout made the
                // Home look as if the rotating recommendation carousel had been
                // replaced by Recently played.
                Shelf(
                    shelf = shelf,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    fullTrackBadges = fullTrackBadgesInShelves,
                    expressive = expressive,
                )
            } else {
                Shelf(
                    shelf = shelf,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    fullTrackBadges = fullTrackBadgesInShelves,
                    expressive = expressive,
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteAlbumReleaseShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    expressive: Boolean,
) {
    if (shelf.items.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = if (expressive) 16.dp else 12.dp,
            bottom = if (expressive) 26.dp else 22.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(shelf.items) { item ->
            val shape = RoundedCornerShape(if (expressive) 22.dp else 18.dp)
            val containerColor = if (expressive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val contentColor = if (expressive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .width(if (expressive) 318.dp else 300.dp)
                    .height(if (expressive) 118.dp else 110.dp)
                    .clip(shape)
                    .background(containerColor)
                    .combinedClickable(
                        onClick = { onItemClick(item) },
                        onLongClick = onItemLongPress?.let { callback ->
                            { callback(item) }
                        },
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(if (expressive) 98.dp else 90.dp)
                        .clip(RoundedCornerShape(if (expressive) 16.dp else 13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.width(if (expressive) 184.dp else 178.dp),
                ) {
                    Text(
                        text = stringResource(R.string.expressive_albums_new_from_favorite),
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor.copy(alpha = 0.76f),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = albumArtistFromMetadata(item.subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.74f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun albumArtistFromMetadata(metadata: String): String {
    val pieces = metadata
        .split(" • ", " · ", " | ")
        .map(String::trim)
        .filter(String::isNotBlank)
    return pieces.firstOrNull { piece ->
        val normalized = piece.lowercase()
        normalized !in setOf("album", "álbum", "single", "ep") &&
            !piece.matches(Regex("\\d{4}")) &&
            !piece.matches(Regex("\\d+\\s+(songs?|tracks?|músicas?|canciones?)", RegexOption.IGNORE_CASE))
    } ?: metadata
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistProfileShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    expressive: Boolean,
) {
    Column(
        modifier = Modifier.padding(
            bottom = if (expressive) 40.dp else HOME_SHELF_BOTTOM_SPACING,
        ),
    ) {
        SectionHeader(shelf.title, shelf.subtitle, expressive = expressive)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            itemsIndexed(shelf.items.take(10)) { _, item ->
                val resolvedArtistArtwork by produceState(
                    initialValue = ArtistArtworkRepository.cached(item.title),
                    key1 = item.title,
                    key2 = item.browseId,
                ) {
                    value = ArtistArtworkRepository.resolve(item.title, item.browseId)
                }
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .combinedClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = onItemLongPress?.let { callback ->
                                { callback(item) }
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AsyncImage(
                        model = resolvedArtistArtwork.artworkAt(CARD_ART_PX),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // Reserve a stable two-line footprint. LazyRow otherwise
                        // changes height when a longer artist name scrolls into
                        // view, which makes the shelf below jump vertically.
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankedShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onRankedSongClick: ((List<Song>, Int) -> Unit)?,
    expressive: Boolean,
) {
    val rankedSongs = remember(shelf.items) {
        shelf.items.take(10).mapNotNull { item ->
            item.videoId?.let { videoId ->
                Song(
                    videoId = videoId,
                    title = item.title,
                    artist = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    isExplicit = item.isExplicit,
                )
            }
        }
    }
    Column(
        modifier = Modifier.padding(
            bottom = if (expressive) 36.dp else HOME_SHELF_BOTTOM_SPACING,
        ),
    ) {
        SectionHeader(shelf.title, shelf.subtitle, expressive = expressive)
        Column(
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            shelf.items.take(10).forEachIndexed { index, item ->
                val rank = item.rank ?: (index + 1)
                val shape = RoundedCornerShape(if (expressive) 18.dp else 12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (expressive) {
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.58f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                        .combinedClickable(
                            onClick = {
                                if (onRankedSongClick != null && rankedSongs.size == shelf.items.take(10).size) {
                                    onRankedSongClick(rankedSongs, index)
                                } else {
                                    onItemClick(item)
                                }
                            },
                            onLongClick = onItemLongPress?.let { { it(item) } },
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = rank.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp),
                    )
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(if (expressive) 14.dp else 9.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val quality = item.videoId?.let { videoId ->
                            rememberTrackLosslessBadgeState(
                                Song(
                                    videoId = videoId,
                                    title = item.title,
                                    artist = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    isExplicit = item.isExplicit,
                                ),
                                allowProbe = false,
                            )
                        } ?: LosslessBadgeState()
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = localizedYouTubeMetadata(item.subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.isExplicit || quality.isLossless || quality.isHiQuality) {
                            Spacer(Modifier.height(3.dp))
                            CompactMediaBadges(
                                isExplicit = item.isExplicit,
                                isLossless = quality.isLossless,
                                isHiResLossless = quality.isHiResLossless,
                                isHiQuality = quality.isHiQuality,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shared by the home feed, Explore and Library so headings line up across tabs. */
@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String = "",
    expressive: Boolean = false,
) {
    Column(
        Modifier.padding(
            horizontal = PAGE_GUTTER,
            vertical = if (expressive) 12.dp else 10.dp,
        ),
    ) {
        Text(
            text = localizedYouTubeSectionTitle(title),
            style = if (expressive) {
                MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 30.sp)
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = localizedYouTubeMetadata(subtitle),
                style = if (expressive) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RotatingRecommendationShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    expressive: Boolean,
) {
    // Suggestions is a permanent, dedicated hero section. While its one-shot
    // session request is resolving, keep the carousel footprint visible instead
    // of allowing Recently played to become the first visual shelf.
    if (shelf.items.isEmpty()) {
        Column(
            modifier = Modifier.padding(
                bottom = if (expressive) 44.dp else HOME_SHELF_BOTTOM_SPACING,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionHeader(shelf.title, shelf.subtitle, expressive = expressive)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (expressive) 238.dp else 226.dp),
                contentAlignment = Alignment.Center,
            ) {
                val artworkSize = if (expressive) {
                    (maxWidth * 0.54f).coerceIn(186.dp, 210.dp)
                } else {
                    (maxWidth * 0.50f).coerceIn(176.dp, 196.dp)
                }
                val density = LocalDensity.current
                val placeholders = listOf(
                    Triple(-2, -82.dp, 0.76f),
                    Triple(-1, -44.dp, 0.88f),
                    Triple(0, 0.dp, 1f),
                    Triple(1, 44.dp, 0.88f),
                    Triple(2, 82.dp, 0.76f),
                )
                placeholders.forEach { (order, x, scale) ->
                    Box(
                        modifier = Modifier
                            .requiredSize(artworkSize)
                            .zIndex(10f - kotlin.math.abs(order).toFloat())
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = with(density) { x.toPx() }
                                alpha = if (order == 0) 0.38f else 0.22f
                                shape = RoundedCornerShape(if (expressive) 18.dp else 14.dp)
                                clip = true
                            }
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .width(112.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .width(78.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            )
        }
        return
    }

    val itemCount = shelf.items.size
    // Keep a large virtual loop so the first visible recommendation is already
    // surrounded by cards on both sides. The user can therefore rotate the
    // shelf left or right immediately instead of starting at a hard list edge.
    val virtualCycles = 20_000
    val virtualItemCount = itemCount * virtualCycles
    val initialVirtualIndex = (virtualCycles / 2) * itemCount
    val carouselState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialVirtualIndex,
    )
    val scope = rememberCoroutineScope()

    val selectedVirtualIndex by remember(carouselState) {
        derivedStateOf {
            val layout = carouselState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo
                .minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }
                ?.index
                ?: carouselState.firstVisibleItemIndex
        }
    }
    val selectedIndex = selectedVirtualIndex.floorMod(itemCount)
    val selected = shelf.items[selectedIndex]

    Column(
        modifier = Modifier.padding(
            bottom = if (expressive) 44.dp else HOME_SHELF_BOTTOM_SPACING,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(shelf.title, shelf.subtitle, expressive = expressive)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (expressive) 238.dp else 226.dp),
        ) {
            // The recommendation cover is deliberately larger than the 150dp
            // cards used by the shelves below it.  Keeping a very narrow
            // logical slot makes the neighbouring covers live *behind* the
            // selected one instead of reading as another ordinary carousel.
            val slotWidth = if (expressive) 48.dp else 52.dp
            val artworkSize = if (expressive) {
                (maxWidth * 0.54f).coerceIn(186.dp, 210.dp)
            } else {
                (maxWidth * 0.50f).coerceIn(176.dp, 196.dp)
            }
            val sidePadding = ((maxWidth - slotWidth) / 2f).coerceAtLeast(0.dp)
            val flingBehavior = rememberSnapFlingBehavior(lazyListState = carouselState)

            LazyRow(
                state = carouselState,
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(horizontal = sidePadding),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = virtualItemCount,
                    key = { virtualIndex -> virtualIndex },
                ) { virtualIndex ->
                    val logicalIndex = virtualIndex.floorMod(itemCount)
                    val item = shelf.items[logicalIndex]
                    val relativePosition by remember(carouselState, virtualIndex) {
                        derivedStateOf {
                            val layout = carouselState.layoutInfo
                            val viewportCenter =
                                (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                            val info = layout.visibleItemsInfo.firstOrNull {
                                it.index == virtualIndex
                            }
                            if (info == null) {
                                (virtualIndex - selectedVirtualIndex).toFloat()
                            } else {
                                val itemCenter = info.offset + info.size / 2f
                                (itemCenter - viewportCenter) /
                                    info.size.coerceAtLeast(1).toFloat()
                            }
                        }
                    }
                    val distance = abs(relativePosition).coerceAtMost(3f)
                    val signed = relativePosition.coerceIn(-3f, 3f)
                    val density = LocalDensity.current
                    // Reference motion behaves like a physical card deck: the
                    // front card peels away with a small rotation while the next
                    // one grows out from behind it. This is intentionally
                    // asymmetric instead of the old mirrored 3D fan.
                    val scale = when {
                        signed < 0f -> (1f - distance * 0.065f).coerceIn(0.78f, 1f)
                        distance <= 1f -> 0.90f + (1f - distance) * 0.10f
                        else -> (0.90f - (distance - 1f) * 0.055f).coerceIn(0.74f, 0.90f)
                    }
                    val alpha = when {
                        signed < 0f -> (1f - distance * 0.16f).coerceIn(0.62f, 1f)
                        else -> (1f - distance * 0.10f).coerceIn(0.70f, 1f)
                    }
                    val rotationZ = when {
                        signed < 0f -> (signed * 7.5f).coerceIn(-10f, 0f)
                        else -> (signed * 3.2f).coerceIn(0f, 7f)
                    }
                    val rotationY = (-signed * 1.8f).coerceIn(-4f, 4f)
                    val translationX = with(density) {
                        when {
                            signed < 0f -> signed * 36.dp.toPx()
                            else -> signed * 18.dp.toPx()
                        }
                    }
                    val translationY = with(density) {
                        (distance * 4.dp.toPx()).coerceAtMost(12.dp.toPx())
                    }
                    val elevation = with(density) {
                        if (distance < 0.45f) 20.dp.toPx() else 5.dp.toPx()
                    }
                    val camera = with(density) { 28.dp.toPx() }
                    var artworkBounds by remember(virtualIndex) { mutableStateOf<Rect?>(null) }

                    Box(
                        modifier = Modifier
                            .width(slotWidth)
                            .height(artworkSize)
                            .zIndex(20f - distance),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                // requiredSize intentionally ignores the narrow
                                // slot's max width, keeping every cover truly 1:1.
                                .requiredSize(artworkSize)
                                .onGloballyPositioned { coordinates ->
                                    val topLeft = coordinates.positionInRoot()
                                    artworkBounds = Rect(
                                        left = topLeft.x,
                                        top = topLeft.y,
                                        right = topLeft.x + coordinates.size.width,
                                        bottom = topLeft.y + coordinates.size.height,
                                    )
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                    this.rotationZ = rotationZ
                                    this.rotationY = rotationY
                                    this.translationX = translationX
                                    this.translationY = translationY
                                    shadowElevation = elevation
                                    cameraDistance = camera
                                    transformOrigin = TransformOrigin(0.5f, 0.88f)
                                    shape = RoundedCornerShape(if (expressive) 18.dp else 14.dp)
                                    clip = true
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = {
                                        scope.launch {
                                            carouselState.animateScrollToItem(virtualIndex)
                                            artworkBounds?.let(NowPlayingLaunchOriginRegistry::record)
                                            onItemClick(item)
                                        }
                                    },
                                    onLongClick = onItemLongPress?.let { callback ->
                                        { callback(item) }
                                    },
                                ),
                        )
                    }
                }
            }
        }

        Text(
            text = selected.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = if (expressive) 19.sp else 18.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = localizedYouTubeMetadata(selected.subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        )
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

@Composable
private fun HeroShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    expressive: Boolean,
) {
    Column(
        Modifier.padding(
            bottom = if (expressive) 46.dp else HOME_SHELF_BOTTOM_SPACING,
        ),
    ) {
        SectionHeader(shelf.title, shelf.subtitle, expressive = expressive)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val featureWidth = (maxWidth - PAGE_GUTTER * 2f) * 0.92f
            LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shelf.items) { item ->
                HeroCard(
                    item = item,
                    onClick = onItemClick,
                    onLongPress = onItemLongPress?.let { callback -> { callback(item) } },
                        expressive = expressive,
                        modifier = Modifier
                            .width(featureWidth)
                            .then(if (expressive) Modifier.height(230.dp) else Modifier),
                )
            }
            }
        }
    }
}

/** Big card: artwork with the caption laid over a scrim, as on Listen Now. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    item: ShelfItem,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (() -> Unit)? = null,
    expressive: Boolean,
    modifier: Modifier = Modifier,
) {
    val isCollection = item.type == BrowseType.ALBUM || item.type == BrowseType.PLAYLIST
    val isTrack = !isCollection && !item.videoId.isNullOrBlank()
    val collectionBadges = rememberCollectionBadges(
        type = item.type,
        title = item.title,
        subtitle = item.subtitle,
        browseId = item.browseId,
        explicitHint = item.isExplicit,
        allowProbe = false,
    )
    val trackQuality = if (isTrack) {
        rememberTrackLosslessBadgeState(
            Song(
                videoId = requireNotNull(item.videoId),
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                isExplicit = item.isExplicit,
                durationText = item.durationText,
                artistId = item.artistId,
                albumId = item.albumId,
                albumName = item.albumName,
                isVideo = item.isVideo,
                setVideoId = item.setVideoId,
                fromAutoplay = item.fromAutoplay,
                localUri = item.localUri,
                localPath = item.localPath,
                sourceQuality = item.sourceQuality,
                queuePinned = item.queuePinned,
                releaseYear = item.releaseYear,
                sourceExplicitKnown = item.sourceExplicitKnown,
                sourcePlaylistId = item.sourcePlaylistId,
                sourcePlaylistTitle = item.sourcePlaylistTitle,
                sourcePlaylistArtworkUrl = item.sourcePlaylistArtworkUrl,
            ),
            allowProbe = false,
        )
    } else {
        LosslessBadgeState()
    }
    val badgeExplicit = if (isCollection) collectionBadges.isExplicit else item.isExplicit
    val badgeLossless = if (isCollection) collectionBadges.isLossless else trackQuality.isLossless
    val badgeHiResLossless =
        if (isCollection) collectionBadges.isHiResLossless else trackQuality.isHiResLossless
    val badgeHiQuality = if (isCollection) collectionBadges.isHiQuality else trackQuality.isHiQuality
    val supportsBadges = isCollection || isTrack

    val heroShape = RoundedCornerShape(if (expressive) 30.dp else 18.dp)

    Box(
        modifier = modifier
            .then(if (!expressive) Modifier.aspectRatio(0.92f) else Modifier)
            .clip(heroShape)
            .thumbnailBorder(heroShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { onClick(item.copy(isExplicit = badgeExplicit)) },
                onLongClick = onLongPress,
            ),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // One continuous scrim over the artwork. It starts above the caption
        // area so title, artist and badges all sit on the same darkening field
        // instead of the badges looking like the only protected element.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HERO_CARD_TEXT_SCRIM),
        )
        if (expressive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                            ),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(
                    start = if (expressive) 20.dp else 16.dp,
                    end = if (expressive) 20.dp else 16.dp,
                    top = 66.dp,
                    bottom = if (expressive) 18.dp else 14.dp,
                ),
        ) {
            // Hero cards are the intentional layout exception: Explicit stays
            // beside the title, while Lossless/Hi-Res occupies the lower-right
            // corner of the caption area instead of joining the title row.
            ExplicitTitle(
                text = item.title,
                isExplicit = badgeExplicit,
                style = if (expressive) {
                    MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 28.sp,
                        lineHeight = 30.sp,
                        shadow = HERO_CARD_TITLE_SHADOW,
                    )
                } else {
                    MaterialTheme.typography.titleLarge.copy(shadow = HERO_CARD_TITLE_SHADOW)
                },
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                badgeSpacing = 5.dp,
                // The Hero Lossless mark below uses a 13dp ribbon / 10sp text.
                badgeSize = 13.dp,
                badgeFontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(if (expressive) 7.dp else 5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = localizedYouTubeMetadata(item.subtitle),
                    style = MaterialTheme.typography.bodyMedium.copy(shadow = HERO_CARD_SUBTITLE_SHADOW),
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (supportsBadges && (badgeLossless || badgeHiQuality)) {
                    Spacer(Modifier.width(10.dp))
                    ReleaseBadges(
                        isExplicit = false,
                        isLossless = badgeLossless,
                        isHiResLossless = badgeHiResLossless,
                        isHiQuality = badgeHiQuality,
                        color = Color.White,
                    )
                }
            }
            }
        }
    }

/**
 * [leadingCard] rides at the head of the row, ahead of the content — the
 * Library tab's "New playlist" tile, which belongs among the playlists rather
 * than in a bar somewhere above them. [onItemLongPress] is likewise the
 * Library's: a card is only worth holding where there is something to do to
 * the thing behind it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    leadingCard: (@Composable () -> Unit)? = null,
    localizeItemLabels: Boolean = false,
    fullTrackBadges: Boolean = false,
    expressive: Boolean = false,
) {
    Column(
        Modifier.padding(
            bottom = if (expressive) 46.dp else HOME_SHELF_BOTTOM_SPACING,
        ),
    ) {
        SectionHeader(shelf.title, shelf.subtitle, expressive = expressive)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            items(shelf.items) { item ->
                ShelfCard(
                    item = item,
                    onClick = onItemClick,
                    onLongPress = onItemLongPress?.let { { it(item) } },
                    localizeLabels = localizeItemLabels,
                    fullTrackBadges = fullTrackBadges,
                    expressive = expressive,
                )
            }
        }
    }
}

/**
 * A card that isn't a thing yet — the dashed "New playlist" tile at the head
 * of the Library's playlist row, sized to sit in line with the covers beside
 * it rather than as a button bolted above them.
 */
@Composable
internal fun NewShelfCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    expressive: Boolean = false,
) {
    Column(
        modifier = Modifier
            .width(SHELF_CARD_WIDTH)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(SHELF_CARD_WIDTH)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(if (expressive) 20.dp else 12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Playlist cards reserve this row for Explicit/Lossless. Keep the
        // leading "New playlist" tile exactly the same height.
        Spacer(Modifier.height(RELEASE_BADGE_SLOT_HEIGHT))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    item: ShelfItem,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (() -> Unit)? = null,
    localizeLabels: Boolean = false,
    fullTrackBadges: Boolean = false,
    expressive: Boolean = false,
) {
    val isCollection = item.type == BrowseType.ALBUM || item.type == BrowseType.PLAYLIST
    val isTrack = !isCollection && !item.videoId.isNullOrBlank()
    val badges = rememberCollectionBadges(
        type = item.type,
        title = item.title,
        subtitle = item.subtitle,
        browseId = item.browseId,
        explicitHint = item.isExplicit,
        allowProbe = false,
    )
    val trackQuality = if (isTrack) {
        rememberTrackLosslessBadgeState(
            Song(
                videoId = requireNotNull(item.videoId),
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                isExplicit = item.isExplicit,
            ),
            allowProbe = false,
        )
    } else {
        LosslessBadgeState()
    }
    val showTrackBadgeRow = isTrack
    val badgeExplicit = if (isCollection) badges.isExplicit else item.isExplicit
    val badgeLossless = if (isCollection) badges.isLossless else trackQuality.isLossless
    val badgeHiResLossless =
        if (isCollection) badges.isHiResLossless else trackQuality.isHiResLossless
    val badgeHiQuality = if (isCollection) badges.isHiQuality else trackQuality.isHiQuality

    Column(
        modifier = Modifier
            .width(SHELF_CARD_WIDTH)
            .combinedClickable(
                onClick = { onClick(item.copy(isExplicit = badgeExplicit)) },
                onLongClick = onLongPress,
            ),
    ) {
        when (item.browseId) {
            "local:downloads" -> {
                Box(
                    modifier = Modifier
                        .width(SHELF_CARD_WIDTH)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(if (expressive) 20.dp else 12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = BitChordIcons.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            "local:all" -> {
                Box(
                    modifier = Modifier
                        .width(SHELF_CARD_WIDTH)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(if (expressive) 20.dp else 12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            else -> {
                val cardShape = RoundedCornerShape(if (expressive) 20.dp else 12.dp)
                Box(
                    modifier = Modifier
                        .width(SHELF_CARD_WIDTH)
                        .aspectRatio(1f)
                        .clip(cardShape)
                        .thumbnailBorder(cardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    item.rank?.let { rank ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(9.dp)
                                .clip(RoundedCornerShape(if (expressive) 14.dp else 10.dp))
                                .background(Color.Black.copy(alpha = 0.62f))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "#$rank",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Compact shelf cards keep track badges out of the title line.
        // Explicit and Lossless/Hi-Res live together in the dedicated row
        // below the artist/metadata, so titles stay stable and uncluttered.
        ExplicitTitle(
            text = if (localizeLabels) localizedYouTubeLibraryItemTitle(item.title) else item.title,
            isExplicit = false,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // Metadata such as Album / Songs / Tracks is YouTube UI copy, not
            // user-authored music text, so it is safe (and expected) to localize
            // on Home/Explore as well as Library.
            text = localizedYouTubeMetadata(item.subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isCollection || showTrackBadgeRow) {
            // Fixed footprint: async Explicit/Lossless results can fill this row
            // without changing the LazyRow height and making sections jump.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RELEASE_BADGE_SLOT_HEIGHT)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                ReleaseBadges(
                    isExplicit = badgeExplicit,
                    isLossless = badgeLossless,
                    isHiResLossless = badgeHiResLossless,
                    isHiQuality = badgeHiQuality,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
