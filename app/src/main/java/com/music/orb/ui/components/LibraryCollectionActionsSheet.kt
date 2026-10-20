package com.music.orb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.DetailPage
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.UiState
import com.music.orb.ui.theme.rememberArtworkPalette

/**
 * A Library card is not a new sheet design: it uses the exact collection
 * actions sheet shown by album/playlist detail pages reached from Home.
 * In particular, the CD/sleeve hero, drag physics, glass, entry and exit
 * choreography have one implementation: [CollectionActionsSheet].
 */
@Composable
fun LibraryCollectionActionsSheet(
    item: ShelfItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onManagePlaylist: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
) {
    val id = item.browseId ?: return
    val type = when {
        item.type == BrowseType.ALBUM || item.type == BrowseType.PLAYLIST -> item.type
        id.startsWith("MPRE", ignoreCase = true) -> BrowseType.ALBUM
        else -> BrowseType.PLAYLIST
    }
    val page = remember(item) {
        DetailPage(
            browseId = id,
            title = item.title,
            subtitle = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
            songs = UiState.Loading,
            type = type,
            isExplicit = item.isExplicit,
        )
    }
    val palette = rememberArtworkPalette(item.thumbnailUrl)
    CollectionActionsSheet(
        page = page,
        pageSurfaceColor = palette.background,
        pagePrimaryTextColor = palette.onBackground,
        pageSecondaryTextColor = palette.onBackgroundVariant,
        // Shelf metadata does not include the detail page's library feedback
        // token, so avoid pretending that saving/removing is available here.
        savedInLibrary = true,
        libraryActionAvailable = false,
        ratingAvailable = false,
        artistAvailable = type == BrowseType.ALBUM && onOpenArtist != null,
        onRate = {},
        onDownload = onDownload,
        onToggleLibrary = {},
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onOpenArtist = { onOpenArtist?.invoke() },
        onShare = onShare,
        onDismiss = onDismiss,
        onOpen = onOpen,
        onManagePlaylist = if (type == BrowseType.PLAYLIST) onManagePlaylist else null,
    )
}
