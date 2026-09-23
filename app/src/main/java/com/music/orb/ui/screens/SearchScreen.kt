package com.music.orb.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.data.model.BrowseItem
import com.music.orb.data.model.CARD_ART_PX
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.artworkAt
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.Song
import com.music.orb.data.model.UiState
import com.music.orb.data.settings.SearchHistoryEntry
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.ROW_DIVIDER_INSET
import com.music.orb.ui.components.SongRow
import com.music.orb.ui.components.CompactMediaBadges
import com.music.orb.ui.components.ReleaseBadges
import com.music.orb.ui.components.LosslessBadgeState
import com.music.orb.ui.components.rememberCollectionBadges
import com.music.orb.ui.components.rememberTrackLosslessBadgeState
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.components.songListSkeleton
import com.music.orb.ui.flavor.OrbFlavorUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    listState: LazyListState,
    focusTrigger: Int = 0,
    showSearchField: Boolean = true,
    showIdleContent: Boolean = true,
    showHeaderAura: Boolean = false,
    onSearchActivated: () -> Unit = {},
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: (BrowseItem) -> Unit,
    history: List<SearchHistoryEntry>,
    discoverState: UiState<List<HomeShelf>>,
    onDiscoverItemClick: (ShelfItem) -> Unit,
    onDiscoverItemLongPress: (ShelfItem) -> Unit,
    onDiscoverRetry: () -> Unit,
    suggestions: List<String>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusTrigger, showSearchField) {
        if (showSearchField && focusTrigger > 0) focusRequester.requestFocus()
    }
    val suggesting = suggestions.isNotEmpty()
    val bodyVisible = showIdleContent || query.isNotBlank() || results != null || suggesting
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (showHeaderAura && OrbFlavorUi.expressive) {
            HomeHeaderAura(
                scrollOffsetPx = listState.auraScrollOffsetPx(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PAGE_AURA_HEIGHT)
                    .align(Alignment.TopCenter),
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            if (showSearchField) {
                Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
                    SearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmit,
                        focusRequester = focusRequester,
                        onActivated = onSearchActivated,
                        modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
                    )
                    if (bodyVisible && results != null && !suggesting) {
                        SearchFilterTabs(filter = filter, onFilterChange = onFilterChange)
                    }
                }
            }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                top = if (showSearchField) 0.dp else contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            if (!showSearchField && bodyVisible && results != null && !suggesting) {
                item(key = "search-filter-tabs") {
                    SearchFilterTabs(filter = filter, onFilterChange = onFilterChange)
                }
            }
            when {
                !bodyVisible -> Unit
                suggesting -> searchSuggestions(
                    suggestions = suggestions,
                    listState = listState,
                    topOcclusion = if (showSearchField) 0.dp else 144.dp,
                    onClick = { term ->
                        onSuggestionClick(term)
                        focusManager.clearFocus()
                    },
                    onFill = onQueryChange,
                )
                results == null && query.isBlank() -> {
                    if (history.isNotEmpty()) {
                        recentSearches(
                            history = history,
                            listState = listState,
                            topOcclusion = if (showSearchField) 0.dp else 144.dp,
                            onClick = onHistoryClick,
                            onRemove = onHistoryRemove,
                            onClear = onHistoryClear,
                        )
                    }
                    discoveryGrid(
                        state = discoverState,
                        listState = listState,
                        topOcclusion = if (showSearchField) 0.dp else 144.dp,
                        onItemClick = onDiscoverItemClick,
                        onItemLongPress = onDiscoverItemLongPress,
                        onRetry = onDiscoverRetry,
                    )
                }
                results == null -> Unit
                results is UiState.Loading -> songListSkeleton(circular = filter == SearchFilter.ARTISTS)
                results is UiState.Error -> item { MessageState(results.message) }
                results is UiState.Success -> {
                    val tracks = results.data
                        .filterIsInstance<SearchResult.Track>()
                        .map { it.song }
                    itemsIndexed(
                        items = results.data,
                        key = { _, row -> searchResultKey(row) },
                    ) { index, row ->
                        val rowKey = searchResultKey(row)
                        Box(
                            modifier = exploreVerticalEdgeModifier(
                                listState = listState,
                                itemKey = rowKey,
                                topOcclusion = if (showSearchField) 0.dp else 144.dp,
                            ),
                        ) {
                            when (row) {
                                is SearchResult.Track -> SongRow(
                                    song = row.song,
                                    onClick = {
                                        onSongClick(tracks, tracks.indexOf(row.song).coerceAtLeast(0))
                                    },
                                    onLongPress = { onSongLongPress(row.song) },
                                    onSwipeToQueue = { onSongSwipe(row.song) },
                                    showSearchBadges = true,
                                    searchBadgesPreResolved = true,
                                    expressiveContainer = true,
                                    fullExplicitBadge = true,
                                )
                                is SearchResult.Browse -> BrowseRow(
                                    item = row.item,
                                    onClick = { onBrowseClick(row.item) },
                                    onLongPress = { onBrowseLongPress(row.item) },
                                )
                            }
                        }
                        if (!OrbFlavorUi.expressive && index < results.data.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun exploreVerticalEdgeModifier(
    listState: LazyListState,
    itemKey: String,
    topOcclusion: Dp,
): Modifier {
    val density = LocalDensity.current
    val minDistancePx = with(density) { 78.dp.toPx() }
    val topPx = with(density) { topOcclusion.toPx() }
    val edgeState by remember(listState, itemKey, minDistancePx, topPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val start = info.offset.toFloat()
            val end = start + info.size.toFloat()
            // topPx is the fixed Explore header height in screen coordinates.
            // Item offsets are relative to the padded LazyColumn content, whose
            // origin is shifted by viewportStartOffset. Translate the header into
            // that coordinate space instead of applying it a second time.
            val top = layout.viewportStartOffset.toFloat() + topPx
            val bottom = layout.viewportEndOffset.toFloat()
            val distance = maxOf(minDistancePx, info.size * 0.90f)
            val topProgress = if (start >= top) 1f else {
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
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }
    val edge = edgeState.first
    val direction = edgeState.second
    return Modifier.graphicsLayer {
        alpha = edge
        val scale = 0.84f + 0.16f * edge
        scaleX = scale
        scaleY = scale
        translationY = with(density) { (8.dp * direction * (1f - edge)).toPx() }
    }
}

private fun LazyListScope.searchSuggestions(
    suggestions: List<String>,
    listState: LazyListState,
    topOcclusion: Dp,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    itemsIndexed(suggestions, key = { _, term -> "suggest:$term" }) { _, term ->
        Box(
            modifier = exploreVerticalEdgeModifier(listState, "suggest:$term", topOcclusion),
        ) {
            SuggestionRow(
                term = term,
                onFill = { onFill(term) },
                onClick = { onClick(term) },
            )
        }
    }
}

@Composable
private fun SuggestionRow(term: String, onFill: (() -> Unit)?, onClick: () -> Unit) {
    val rowModifier = if (OrbFlavorUi.expressive) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onFill != null) {
            Box(
                modifier = Modifier
                    .size(if (OrbFlavorUi.expressive) 42.dp else 40.dp)
                    .clip(CircleShape)
                    .then(
                        if (OrbFlavorUi.expressive) {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        } else Modifier
                    )
                    .clickable(onClick = onFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.NorthWest,
                    contentDescription = stringResource(R.string.beta_ui_edit_search_term, term),
                    tint = if (OrbFlavorUi.expressive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
    }
}

private fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntry>,
    listState: LazyListState,
    topOcclusion: Dp,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.beta_ui_recent_searches),
                style = if (OrbFlavorUi.expressive) {
                    MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 30.sp)
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.beta_ui_clear),
                style = MaterialTheme.typography.titleMedium,
                color = if (OrbFlavorUi.expressive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .then(
                        if (OrbFlavorUi.expressive) {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        } else Modifier
                    )
                    .clickable(onClick = onClear)
                    .padding(
                        horizontal = if (OrbFlavorUi.expressive) 14.dp else 10.dp,
                        vertical = if (OrbFlavorUi.expressive) 7.dp else 4.dp,
                    ),
            )
        }
    }
    items(history, key = { "recent:${it.query}" }) { entry ->
        Box(
            modifier = exploreVerticalEdgeModifier(listState, "recent:${entry.query}", topOcclusion),
        ) {
            RecentSearchRow(
                entry = entry,
                onClick = { onClick(entry.query) },
                onRemove = { onRemove(entry.query) },
            )
        }
    }
}

@Composable
private fun RecentSearchRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val term = entry.query
    val rowModifier = if (OrbFlavorUi.expressive) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!entry.thumbnailUrl.isNullOrBlank()) {
            val artShape = RoundedCornerShape(if (OrbFlavorUi.expressive) 12.dp else 9.dp)
            AsyncImage(
                model = entry.thumbnailUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (OrbFlavorUi.expressive) 44.dp else 40.dp)
                    .clip(artShape)
                    .thumbnailBorder(artShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Text(
            text = term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(if (OrbFlavorUi.expressive) 42.dp else 40.dp)
                .clip(CircleShape)
                .then(
                    if (OrbFlavorUi.expressive) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    } else Modifier
                )
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.beta_ui_remove_recent_search, term),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun LazyListScope.discoveryGrid(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    topOcclusion: Dp,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is UiState.Loading -> item(key = "discover:loading") {
            Column {
                SectionHeader(
                    title = stringResource(R.string.expressive_discover_may_like),
                    expressive = OrbFlavorUi.expressive,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        is UiState.Error -> item(key = "discover:error") {
            Column {
                SectionHeader(
                    title = stringResource(R.string.expressive_discover_may_like),
                    expressive = OrbFlavorUi.expressive,
                )
                MessageState(
                    state.message,
                    actionLabel = stringResource(R.string.beta_ui_retry),
                    onAction = onRetry,
                )
            }
        }

        is UiState.Success -> {
            val shelf = state.data.firstOrNull()
            val suggestions = state.data.flatMap { it.items }
            if (suggestions.isNotEmpty()) {
                item(key = "discover:header") {
                    SectionHeader(
                        title = shelf?.title ?: stringResource(R.string.expressive_discover_may_like),
                        subtitle = shelf?.subtitle.orEmpty(),
                        expressive = OrbFlavorUi.expressive,
                    )
                }
                items(
                    items = suggestions.chunked(2),
                    key = { row ->
                        "discover:" + row.joinToString("|") {
                            it.videoId ?: it.browseId ?: "${it.title}:${it.subtitle}"
                        }
                    },
                ) { row ->
                    val rowKey = "discover:" + row.joinToString("|") {
                        it.videoId ?: it.browseId ?: "${it.title}:${it.subtitle}"
                    }
                    Row(
                        modifier = exploreVerticalEdgeModifier(listState, rowKey, topOcclusion)
                            .fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { item ->
                            DiscoverGridCard(
                                item = item,
                                onClick = { onItemClick(item) },
                                onLongPress = { onItemLongPress(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "discover:bottom-space") { Spacer(Modifier.height(26.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverGridCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
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
            ),
            allowProbe = false,
        )
    } else {
        LosslessBadgeState()
    }
    val badgeExplicit = if (isCollection) collectionBadges.isExplicit else item.isExplicit
    val badgeLossless = if (isCollection) collectionBadges.isLossless else trackQuality.isLossless
    val badgeHiRes = if (isCollection) collectionBadges.isHiResLossless else trackQuality.isHiResLossless
    val badgeHiQuality = if (isCollection) collectionBadges.isHiQuality else trackQuality.isHiQuality
    val shape = RoundedCornerShape(if (OrbFlavorUi.expressive) 22.dp else 14.dp)

    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(10.dp))
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(top = 5.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (isCollection || isTrack) {
                ReleaseBadges(
                    isExplicit = badgeExplicit,
                    isLossless = badgeLossless,
                    isHiResLossless = badgeHiRes,
                    isHiQuality = badgeHiQuality,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(
    item: BrowseItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val isCollection = item.type == BrowseType.ALBUM || item.type == BrowseType.PLAYLIST
    val resolvedArtwork by produceState(
        initialValue = if (item.type == BrowseType.ARTIST) {
            ArtistArtworkRepository.cached(item.title)
        } else {
            item.thumbnailUrl
        },
        key1 = item.type,
        key2 = item.title,
        key3 = item.thumbnailUrl,
    ) {
        value = if (item.type == BrowseType.ARTIST) {
            ArtistArtworkRepository.resolve(item.title, item.browseId)
        } else {
            item.thumbnailUrl
        }
    }
    val badges = rememberCollectionBadges(
        type = item.type,
        title = item.title,
        subtitle = item.subtitle,
        browseId = item.browseId,
        explicitHint = item.isExplicit,
        // MainViewModel only publishes this search row after its own badge
        // pass. Reading the cache here avoids starting the same TIDAL/detail
        // request a second time merely because Compose just created the row.
        allowProbe = false,
    )

    val rowModifier = if (OrbFlavorUi.expressive) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = resolvedArtwork.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else RoundedCornerShape(if (OrbFlavorUi.expressive) 14.dp else 8.dp),
                )
                .thumbnailBorder(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else RoundedCornerShape(if (OrbFlavorUi.expressive) 14.dp else 8.dp),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val showExplicit = if (isCollection) badges.isExplicit else item.isExplicit
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Keep track badges attached to the title. Collections now
                    // move release-level badges below the subtitle instead.
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!isCollection && (showExplicit || badges.isLossless || badges.isHiQuality)) {
                    Spacer(Modifier.width(6.dp))
                    ReleaseBadges(
                        isExplicit = showExplicit,
                        isLossless = badges.isLossless,
                        isHiResLossless = badges.isHiResLossless,
                        isHiQuality = badges.isHiQuality,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = localizedYouTubeMetadata(
                    item.subtitle.ifBlank { browseTypeLabel(item.type) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCollection && (showExplicit || badges.isLossless || badges.isHiQuality)) {
                Spacer(Modifier.height(4.dp))
                ReleaseBadges(
                    isExplicit = showExplicit,
                    isLossless = badges.isLossless,
                    isHiResLossless = badges.isHiResLossless,
                    isHiQuality = badges.isHiQuality,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun searchResultKey(row: SearchResult): String = when (row) {
    is SearchResult.Track -> "track:${row.song.videoId}:${row.song.title}"
    is SearchResult.Browse -> "browse:${row.item.type}:${row.item.browseId ?: row.item.title}"
}

@Composable
private fun SearchFilterTabs(filter: SearchFilter, onFilterChange: (SearchFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchFilter.entries.forEach { entry ->
            val selected = entry == filter
            val shape = if (OrbFlavorUi.expressive) {
                RoundedCornerShape(24.dp)
            } else {
                FILTER_PILL_SHAPE
            }
            val scale by animateFloatAsState(
                targetValue = if (OrbFlavorUi.expressive && selected) 1.04f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "searchFilterScale",
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .background(
                        when {
                            OrbFlavorUi.expressive && selected ->
                                MaterialTheme.colorScheme.primaryContainer
                            OrbFlavorUi.expressive ->
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            selected ->
                                MaterialTheme.colorScheme.onBackground
                            else ->
                                MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable { onFilterChange(entry) }
                    .padding(
                        horizontal = if (OrbFlavorUi.expressive) 20.dp else 16.dp,
                        vertical = if (OrbFlavorUi.expressive) 12.dp else 10.dp,
                    ),
            ) {
                Text(
                    text = searchFilterLabel(entry),
                    style = if (OrbFlavorUi.expressive) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = when {
                        OrbFlavorUi.expressive && selected ->
                            MaterialTheme.colorScheme.onPrimaryContainer
                        OrbFlavorUi.expressive ->
                            MaterialTheme.colorScheme.onSurface
                        selected ->
                            MaterialTheme.colorScheme.background
                        else ->
                            MaterialTheme.colorScheme.onBackground
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun searchFilterLabel(filter: SearchFilter): String = when (filter) {
    SearchFilter.SONGS -> stringResource(R.string.beta_ui_filter_songs)
    SearchFilter.ALBUMS -> stringResource(R.string.beta_ui_filter_albums)
    SearchFilter.ARTISTS -> stringResource(R.string.beta_ui_filter_artists)
    SearchFilter.PLAYLISTS -> stringResource(R.string.beta_ui_filter_playlists)
}

@Composable
private fun browseTypeLabel(type: BrowseType): String = when (type) {
    BrowseType.ALBUM -> stringResource(R.string.beta_ui_album)
    BrowseType.ARTIST -> stringResource(R.string.beta_ui_artist)
    BrowseType.PLAYLIST -> stringResource(R.string.beta_ui_playlist)
    else -> type.name.lowercase().replaceFirstChar { it.uppercase() }
}

private val FILTER_PILL_SHAPE = RoundedCornerShape(12.dp)

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onActivated: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val submit = {
        onSubmit()
        focusManager.clearFocus()
    }
    val fieldShape = RoundedCornerShape(if (OrbFlavorUi.expressive) 30.dp else 11.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (OrbFlavorUi.expressive) 58.dp else 46.dp)
            .background(
                if (OrbFlavorUi.expressive) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                fieldShape,
            )
            .padding(
                start = if (OrbFlavorUi.expressive) 12.dp else 8.dp,
                end = if (OrbFlavorUi.expressive) 16.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = stringResource(R.string.beta_ui_search),
            tint = if (OrbFlavorUi.expressive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .size(if (OrbFlavorUi.expressive) 38.dp else 32.dp)
                .clip(CircleShape)
                .then(
                    if (OrbFlavorUi.expressive) {
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                    } else Modifier
                )
                .clickable(enabled = query.isNotBlank(), onClick = submit)
                .padding(6.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_hint),
                    style = if (OrbFlavorUi.expressive) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = (
                    if (OrbFlavorUi.expressive) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    }
                ).copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onActivated() },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(if (OrbFlavorUi.expressive) 34.dp else 28.dp)
                    .clip(CircleShape)
                    .then(
                        if (OrbFlavorUi.expressive) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        } else Modifier
                    )
                    .clickable {
                        onQueryChange("")
                        focusManager.clearFocus()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.beta_ui_clear_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
