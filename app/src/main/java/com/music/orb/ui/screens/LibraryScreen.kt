package com.music.orb.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.orb.R
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.LibraryPage
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.UiState
import com.music.orb.ui.icons.BitChordIcons
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.librarySkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    signedIn: Boolean,
    state: UiState<LibraryPage>,
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
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item {
                Text(
                    text = stringResource(R.string.screen_library),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                )
            }
            item(key = "shelf:$ON_DEVICE") {
                Shelf(
                    shelf = HomeShelf(
                        title = ON_DEVICE,
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
                )
            }
            if (!signedIn) {
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
                    MessageState(state.message, actionLabel = "Retry", onAction = onRetry)
                }
                is UiState.Success -> {
                    val shelves = state.data.shelves
                    if (shelves.none { it.title == PLAYLISTS }) {
                        item(key = "shelf:$PLAYLISTS") {
                            PlaylistShelf(
                                shelf = HomeShelf(PLAYLISTS, emptyList()),
                                onItemClick = onShelfItemClick,
                                onItemLongPress = onShelfItemLongPress,
                                onNewPlaylist = onNewPlaylist,
                            )
                        }
                    }
                    shelves.forEach { shelf ->
                        item(key = "shelf:${shelf.title}") {
                            if (shelf.title == PLAYLISTS) {
                                PlaylistShelf(
                                    shelf = shelf,
                                    onItemClick = onShelfItemClick,
                                    onItemLongPress = onShelfItemLongPress,
                                    onNewPlaylist = onNewPlaylist,
                                )
                            } else {
                                Shelf(shelf = shelf, onItemClick = onShelfItemClick)
                            }
                        }
                    }
                }
            }
        }
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
        leadingCard = {
            NewShelfCard(
                icon = BitChordIcons.Plus,
                label = "New playlist",
                subtitle = "Saved to YouTube Music",
                onClick = onNewPlaylist,
            )
        },
    )
}

private const val PLAYLISTS = "Playlists"
private const val ON_DEVICE = "On Device"