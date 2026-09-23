package com.music.orb.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.LocalMediaRepository
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.ROW_DIVIDER_INSET
import com.music.orb.ui.components.SongRow

private const val LOCAL_TAB_SONGS = 0
private const val LOCAL_TAB_ARTISTS = 1
private const val LOCAL_TAB_ALBUMS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicScreen(
    songs: List<Song>,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    contentPadding: PaddingValues,
    emptyMessage: String? = null,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    isDownloadsPage: Boolean = false,
    onRemoveAllDownloads: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(LOCAL_TAB_SONGS) }
    var drillDownLabel by remember { mutableStateOf<String?>(null) }
    var drillDownSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var showRemoveAllDownloadsDialog by remember { mutableStateOf(false) }

    val inDrillDown = drillDownLabel != null

    BackHandler(enabled = inDrillDown) {
        drillDownLabel = null
        drillDownSongs = emptyList()
    }

    val bodyContentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
    // listBottomPadding intentionally includes extra scroll clearance above the
    // bottom chrome. The shuffle FAB is fixed, so trim only that scroll-only
    // reserve and keep the button one normal 8dp rhythm step above the mini
    // player / navigation stack instead of overlapping it.
    val inheritedBottom = contentPadding.calculateBottomPadding()
    val shuffleFabBottom = if (inheritedBottom > 60.dp) inheritedBottom - 44.dp else 16.dp
    val topBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 52.dp

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                LocalMediaModeSelector(
                    selectedTab = selectedTab,
                    onSelect = { tab ->
                        selectedTab = tab
                        drillDownLabel = null
                    },
                    modifier = Modifier.padding(top = topBarHeight),
                )

                if (isDownloadsPage && songs.isNotEmpty() && onRemoveAllDownloads != null) {
                    OutlinedButton(
                        onClick = { showRemoveAllDownloadsDialog = true },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.downloads_remove_all),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                AnimatedContent(
                    targetState = if (inDrillDown) "drill:$drillDownLabel" else "tab:$selectedTab",
                    transitionSpec = {
                        if (targetState.startsWith("drill:")) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 3 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "local_music_content",
                    modifier = Modifier.fillMaxSize(),
                ) { key ->
                    when {
                        songs.isEmpty() && emptyMessage != null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bodyContentPadding),
                            ) {
                                MessageState(message = emptyMessage)
                            }
                        }

                        key.startsWith("drill:") -> {
                            DrillDownSongList(
                                label = drillDownLabel ?: "",
                                songs = drillDownSongs,
                                onSongClick = onSongClick,
                                onSongLongPress = onSongLongPress,
                                onSongSwipe = onSongSwipe,
                                onShuffle = onShuffle,
                                onBack = {
                                    drillDownLabel = null
                                    drillDownSongs = emptyList()
                                },
                                contentPadding = bodyContentPadding,
                                forceDownloadedGlyph = isDownloadsPage,
                            )
                        }

                        key == "tab:$LOCAL_TAB_SONGS" -> {
                            SongsTab(
                                songs = songs,
                                onSongClick = onSongClick,
                                onSongLongPress = onSongLongPress,
                                onSongSwipe = onSongSwipe,
                                contentPadding = bodyContentPadding,
                                forceDownloadedGlyph = isDownloadsPage,
                            )
                        }

                        key == "tab:$LOCAL_TAB_ARTISTS" -> {
                            val artists = remember(songs) {
                                songs.groupBy { it.artist }
                                    .entries
                                    .sortedBy { it.key.lowercase() }
                            }
                            ArtistsTab(
                                artists = artists,
                                onArtistClick = { artist, artistSongs ->
                                    drillDownLabel = artist
                                    drillDownSongs = artistSongs
                                },
                                contentPadding = bodyContentPadding,
                            )
                        }

                        else -> {
                            val albums = remember(songs) {
                                songs.filter { it.albumName != null }
                                    .groupBy { it.albumName!! }
                                    .entries
                                    .sortedBy { it.key.lowercase() }
                            }
                            AlbumsTab(
                                albums = albums,
                                onAlbumClick = { album, albumSongs ->
                                    drillDownLabel = album
                                    drillDownSongs = albumSongs
                                },
                                contentPadding = bodyContentPadding,
                            )
                        }
                    }
                }
            }

            if (songs.isNotEmpty() && !inDrillDown) {
                FloatingActionButton(
                    onClick = { onShuffle(songs) },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 20.dp,
                            bottom = shuffleFabBottom,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.btn_shuffle),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }

    if (showRemoveAllDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDownloadsDialog = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.downloads_remove_all_title)) },
            text = { Text(stringResource(R.string.downloads_remove_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveAllDownloadsDialog = false
                        onRemoveAllDownloads?.invoke()
                    },
                ) {
                    Text(stringResource(R.string.downloads_remove_all_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllDownloadsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    contentPadding: PaddingValues,
    forceDownloadedGlyph: Boolean = false,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SectionHeader(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.count_songs, songs.size),
            )
        }
        itemsIndexed(songs) { index, song ->
            SongRow(
                song = song,
                onClick = { onSongClick(songs, index) },
                onLongPress = { onSongLongPress(song) },
                onSwipeToQueue = { onSongSwipe(song) },
                forceDownloadedGlyph = forceDownloadedGlyph,
            )
            if (index < songs.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<Map.Entry<String, List<Song>>>,
    onArtistClick: (String, List<Song>) -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SectionHeader(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.count_artists, artists.size),
            )
        }
        items(artists) { (artist, artistSongs) ->
            ArtistRow(
                name = artist,
                songs = artistSongs,
                onClick = { onArtistClick(artist, artistSongs) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ArtistRow(name: String, songs: List<Song>, onClick: () -> Unit) {
    val songCount = songs.size
    val fallbackArtwork = remember(songs) {
        songs.firstNotNullOfOrNull { it.thumbnailUrl }
    }
    val artworkUrl by produceState(fallbackArtwork, name, fallbackArtwork) {
        value = LocalMediaRepository.artistArtwork(name) ?: fallbackArtwork
    }
    val songText = if (songCount == 1) {
        stringResource(R.string.count_song_singular)
    } else {
        stringResource(R.string.count_songs, songCount)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUrl.artworkAt(ROW_ART_PX),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = songText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AlbumsTab(
    albums: List<Map.Entry<String, List<Song>>>,
    onAlbumClick: (String, List<Song>) -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            SectionHeader(
                icon = Icons.Rounded.Album,
                title = stringResource(R.string.count_albums, albums.size),
            )
        }
        if (albums.isEmpty()) {
            item {
                MessageState(message = stringResource(R.string.local_no_albums))
            }
        }
        items(albums) { (album, albumSongs) ->
            AlbumRow(
                name = album,
                artist = albumSongs.firstOrNull()?.artist ?: "",
                songCount = albumSongs.size,
                artworkUrl = albumSongs.firstNotNullOfOrNull { it.thumbnailUrl },
                onClick = { onAlbumClick(album, albumSongs) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun AlbumRow(
    name: String,
    artist: String,
    songCount: Int,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    val resolvedArtworkUrl by produceState(artworkUrl, name, artist, artworkUrl) {
        value = artworkUrl ?: LocalMediaRepository.albumArtwork(name, artist)
    }
    val songText = if (songCount == 1) {
        stringResource(R.string.count_song_singular)
    } else {
        stringResource(R.string.count_songs, songCount)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!resolvedArtworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = resolvedArtworkUrl.artworkAt(ROW_ART_PX),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (artist.isNotBlank()) append("$artist · ")
                    append(songText)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DrillDownSongList(
    label: String,
    songs: List<Song>,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    forceDownloadedGlyph: Boolean = false,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = PAGE_GUTTER, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { if (songs.isNotEmpty()) onSongClick(songs, 0) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_play),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { if (songs.isNotEmpty()) onShuffle(songs) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_shuffle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        itemsIndexed(songs) { index, song ->
            SongRow(
                song = song,
                onClick = { onSongClick(songs, index) },
                onLongPress = { onSongLongPress(song) },
                onSwipeToQueue = { onSongSwipe(song) },
                forceDownloadedGlyph = forceDownloadedGlyph,
            )
            if (index < songs.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun LocalMediaModeSelector(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LocalModeButton(
                icon = Icons.Rounded.MusicNote,
                label = stringResource(R.string.local_tab_songs),
                selected = selectedTab == LOCAL_TAB_SONGS,
                onClick = { onSelect(LOCAL_TAB_SONGS) },
            )
            LocalModeButton(
                icon = Icons.Rounded.Person,
                label = stringResource(R.string.local_tab_artists),
                selected = selectedTab == LOCAL_TAB_ARTISTS,
                onClick = { onSelect(LOCAL_TAB_ARTISTS) },
            )
            LocalModeButton(
                icon = Icons.Rounded.Album,
                label = stringResource(R.string.local_tab_albums),
                selected = selectedTab == LOCAL_TAB_ALBUMS,
                onClick = { onSelect(LOCAL_TAB_ALBUMS) },
            )
        }
    }
}

@Composable
private fun RowScope.LocalModeButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
