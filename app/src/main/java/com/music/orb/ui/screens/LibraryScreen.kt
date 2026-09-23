package com.music.orb.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.orb.R
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.LibraryPage
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.UiState
import com.music.orb.ui.icons.BitChordIcons
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.librarySkeleton
import com.music.orb.ui.flavor.OrbFlavorUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    signedIn: Boolean,
    usingOrbLibrary: Boolean,
    state: UiState<LibraryPage>,
    query: String,
    listState: LazyListState,
    onShelfItemClick: (ShelfItem) -> Unit,
    onShelfItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val trimmedQuery = query.trim()
    val searching = trimmedQuery.isNotEmpty()

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                if (!OrbFlavorUi.expressive) {
                    item {
                        Text(
                            text = stringResource(R.string.screen_library),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                        )
                    }
                }
                if (!searching) {
                    item(key = "shelf:$ON_DEVICE") {
                        Box(modifier = libraryVerticalEdgeModifier(listState, "shelf:$ON_DEVICE")) {
                        Shelf(
                            shelf = HomeShelf(
                                title = stringResource(R.string.beta_ui_on_device),
                                items = listOf(
                                    ShelfItem(
                                        title = stringResource(R.string.library_downloads),
                                        subtitle = stringResource(R.string.library_downloads_subtitle),
                                        thumbnailUrl = null,
                                        videoId = null,
                                        browseId = "local:downloads",
                                    ),
                                    ShelfItem(
                                        title = stringResource(R.string.library_local_music),
                                        subtitle = stringResource(R.string.library_local_music_subtitle),
                                        thumbnailUrl = null,
                                        videoId = null,
                                        browseId = "local:all",
                                    ),
                                ),
                            ),
                            onItemClick = onShelfItemClick,
                            localizeItemLabels = true,
                            fullTrackBadges = true,
                            expressive = OrbFlavorUi.expressive,
                        )
                        }
                    }
                }
                if (!signedIn && !usingOrbLibrary) {
                    item {
                        MessageState(
                            message = stringResource(R.string.library_signin_description),
                            actionLabel = stringResource(R.string.btn_sign_in),
                            onAction = onSignIn,
                        )
                    }
                    return@LazyColumn
                }
                when (state) {
                    is UiState.Loading -> librarySkeleton()
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(R.string.beta_ui_retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        val shelves = if (searching) {
                            state.data.shelves.mapNotNull { shelf ->
                                val matches = shelf.items.filter { item ->
                                    item.isLibraryAlbumOrPlaylist(shelf.title) &&
                                        (item.title.contains(trimmedQuery, ignoreCase = true) ||
                                            item.subtitle.contains(trimmedQuery, ignoreCase = true))
                                }
                                shelf.copy(items = matches).takeIf { matches.isNotEmpty() }
                            }
                        } else {
                            state.data.shelves
                        }

                        if (searching && shelves.isEmpty()) {
                            item { MessageState(stringResource(R.string.library_search_empty)) }
                        } else if (usingOrbLibrary && state.data.isEmpty) {
                            item { MessageState(stringResource(R.string.orb_library_empty)) }
                        }

                        if (!searching && !usingOrbLibrary && shelves.none { it.title == PLAYLISTS }) {
                            item(key = "shelf:$PLAYLISTS") {
                                Box(modifier = libraryVerticalEdgeModifier(listState, "shelf:$PLAYLISTS")) {
                                PlaylistShelf(
                                    shelf = HomeShelf(stringResource(R.string.beta_ui_playlists), emptyList()),
                                    onItemClick = onShelfItemClick,
                                    onItemLongPress = onShelfItemLongPress,
                                    onNewPlaylist = onNewPlaylist,
                                )
                                }
                            }
                        }
                        shelves.forEach { shelf ->
                            item(key = "shelf:${shelf.title}") {
                                Box(modifier = libraryVerticalEdgeModifier(listState, "shelf:${shelf.title}")) {
                                if (!searching && !usingOrbLibrary && shelf.title == PLAYLISTS) {
                                    PlaylistShelf(
                                        shelf = shelf.copy(title = stringResource(R.string.beta_ui_playlists)),
                                        onItemClick = onShelfItemClick,
                                        onItemLongPress = onShelfItemLongPress,
                                        onNewPlaylist = onNewPlaylist,
                                    )
                                } else {
                                    Shelf(
                                        shelf = shelf,
                                        onItemClick = onShelfItemClick,
                                        onItemLongPress = if (usingOrbLibrary) null else onShelfItemLongPress,
                                        localizeItemLabels = true,
                                        fullTrackBadges = true,
                                        expressive = OrbFlavorUi.expressive,
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
}

@Composable
private fun libraryVerticalEdgeModifier(
    listState: LazyListState,
    itemKey: String,
): Modifier {
    val density = LocalDensity.current
    val topOcclusionPx = with(density) { 86.dp.toPx() }
    val minDistancePx = with(density) { 78.dp.toPx() }
    val edgeState by remember(listState, itemKey, topOcclusionPx, minDistancePx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val start = info.offset.toFloat()
            val end = start + info.size.toFloat()
            // Header height is screen-relative; item offsets are content-
            // relative. Translate through viewportStartOffset so the reserved top
            // padding is not counted twice.
            val top = layout.viewportStartOffset.toFloat() + topOcclusionPx
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

@Composable
private fun PlaylistShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
) {
    Shelf(
        shelf = shelf,
        onItemClick = onItemClick,
        onItemLongPress = onItemLongPress,
        localizeItemLabels = true,
        expressive = OrbFlavorUi.expressive,
        leadingCard = {
            NewShelfCard(
                icon = BitChordIcons.Plus,
                label = stringResource(R.string.beta_ui_new_playlist),
                subtitle = stringResource(R.string.beta_ui_saved_to_youtube_music),
                onClick = onNewPlaylist,
                expressive = OrbFlavorUi.expressive,
            )
        },
    )
}

private const val PLAYLISTS = "Playlists"
private const val ON_DEVICE = "On Device"

private fun ShelfItem.isLibraryAlbumOrPlaylist(shelfTitle: String): Boolean {
    if (type == BrowseType.ALBUM || type == BrowseType.PLAYLIST) return true
    val id = browseId.orEmpty()
    if (id.startsWith("MPRE", ignoreCase = true) ||
        id.startsWith("VL", ignoreCase = true) ||
        id.startsWith("PL", ignoreCase = true)
    ) return true
    val shelf = shelfTitle.lowercase()
    return "album" in shelf || "álbum" in shelf || "playlist" in shelf || "lista de reprodução" in shelf
}
