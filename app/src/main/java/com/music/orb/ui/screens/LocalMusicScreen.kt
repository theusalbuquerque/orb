@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
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
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Shuffle
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import com.music.orb.ui.components.OrbGlassOutlinedButton as OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.R
import com.music.orb.data.LocalMediaRepository
import com.music.orb.download.OfflinePlaylist
import com.music.orb.download.OfflineAlbumStore
import com.music.orb.download.orderedOfflineItems
import com.music.orb.download.OfflinePlaylistStore
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.OrbBouncyPillSelector
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.ROW_DIVIDER_INSET
import com.music.orb.ui.components.SongRow

private const val LOCAL_TAB_SONGS = 0
private const val LOCAL_TAB_ARTISTS = 1
private const val LOCAL_TAB_ALBUMS = 2
private const val LOCAL_TAB_PLAYLISTS = 3

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
    val offlinePlaylists by OfflinePlaylistStore.playlists.collectAsState()
    val offlineAlbums by OfflineAlbumStore.albums.collectAsState()
    val savedDownloadUris by com.music.orb.download.Downloads.saved.collectAsState()
    // The requested YouTube ID and the resolved FLAC source ID can refer to the
    // same downloaded file. Map both aliases so album/playlist folder order is stable.
    val downloadedById = remember(songs, savedDownloadUris) {
        val byId = songs.associateBy(Song::videoId).toMutableMap()
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        savedDownloadUris.forEach { (id, uri) ->
            byUri[uri]?.let { byId.putIfAbsent(id, it) }
        }
        byId
    }

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
    val topBarHeight = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 60.dp + 18.dp

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        indicatorTopPadding = topBarHeight,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                LocalMediaModeSelector(
                    selectedTab = selectedTab,
                    showPlaylists = isDownloadsPage,
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
                        val transition = if (targetState.startsWith("drill:")) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 3 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it } + fadeOut())
                        }
                        transition.using(null)
                    },
                    label = "local_music_content",
                    modifier = Modifier.fillMaxSize(),
                ) { key ->
                    when {
                        songs.isEmpty() && emptyMessage != null &&
                            !(isDownloadsPage && selectedTab == LOCAL_TAB_PLAYLISTS && offlinePlaylists.isNotEmpty()) -> {
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

                        key == "tab:$LOCAL_TAB_PLAYLISTS" && isDownloadsPage -> {
                            PlaylistsTab(
                                playlists = offlinePlaylists,
                                downloadedById = downloadedById,
                                onPlaylistClick = { playlist, playlistSongs ->
                                    if (playlistSongs.isNotEmpty()) {
                                        drillDownLabel = playlist.title
                                        drillDownSongs = playlistSongs.map { song ->
                                            song.copy(
                                                sourcePlaylistId = playlist.browseId,
                                                sourcePlaylistTitle = playlist.title,
                                                sourcePlaylistArtworkUrl = playlist.artworkUrl,
                                            )
                                        }
                                    }
                                },
                                contentPadding = bodyContentPadding,
                            )
                        }

                        else -> {
                            val albums = remember(songs, offlineAlbums, downloadedById) {
                                val savedFolderTracks = mutableSetOf<String>()
                                val fromAlbumButtons = offlineAlbums.mapNotNull { folder ->
                                    val tracks = orderedOfflineItems(folder.trackIds, downloadedById)
                                    if (tracks.isEmpty()) null else {
                                        tracks.forEach { savedFolderTracks.add(it.localUri ?: it.videoId) }
                                        java.util.AbstractMap.SimpleEntry(folder.title, tracks)
                                    }
                                }
                                // Legacy or individually downloaded songs remain discoverable.
                                val otherAlbums = songs.filter { song ->
                                    !song.albumName.isNullOrBlank() &&
                                        (song.localUri ?: song.videoId) !in savedFolderTracks
                                }.groupBy { it.albumName!! }.map { (name, tracks) ->
                                    java.util.AbstractMap.SimpleEntry(name, tracks)
                                }
                                (fromAlbumButtons + otherAlbums).sortedBy { it.key.lowercase() }
                            }
                            AlbumsTab(
                                albums = albums,
                                onAlbumClick = { album, albumSongs ->
                                    drillDownLabel = album
                                    // Selecting a downloaded album folder is still an
                                    // authored album Play, never a playlist or radio mix.
                                    val offlineAlbumId = offlineAlbums.firstOrNull { entry ->
                                        entry.title == album &&
                                            entry.trackIds.any { it in downloadedById }
                                    }?.browseId
                                    drillDownSongs = if (isDownloadsPage) {
                                        albumSongs.map { song ->
                                            song.copy(
                                                albumId = offlineAlbumId ?: "local:downloaded-album:$album",
                                                albumName = album,
                                                preserveAlbumRelease = true,
                                            )
                                        }
                                    } else albumSongs
                                },
                                contentPadding = bodyContentPadding,
                            )
                        }
                    }
                }
            }

            if (songs.isNotEmpty() && !inDrillDown) {
                val shuffleShape = RoundedCornerShape(22.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = shuffleFabBottom)
                        .size(58.dp)
                        .clip(shuffleShape)
                        // Opaque-enough secondary glass to read against artwork,
                        // retaining the same frosted material as the bottom bar.
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.80f))
                        .orbGlassSurface(
                            pageColor = MaterialTheme.colorScheme.secondaryContainer,
                            cornerRadius = 22.dp,
                            drawRim = false,
                            overlayStrength = true,
                            overlayOpacity = 0.58f,
                            backdropBlur = 56.dp,
                            manageDialogBackdrop = false,
                        )
                        .clickable(onClickLabel = stringResource(R.string.btn_shuffle)) { onShuffle(songs) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }

    if (showRemoveAllDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDownloadsDialog = false },
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
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
                        contentDescription = stringResource(R.string.song_action_back),
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
    showPlaylists: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(LOCAL_TAB_SONGS, LOCAL_TAB_ARTISTS, LOCAL_TAB_ALBUMS) +
        if (showPlaylists) listOf(LOCAL_TAB_PLAYLISTS) else emptyList()
    val labels = listOf(
        stringResource(R.string.local_tab_songs),
        stringResource(R.string.local_tab_artists),
        stringResource(R.string.local_tab_albums),
    ) + if (showPlaylists) listOf(stringResource(R.string.offline_playlists_tab)) else emptyList()
    val icons = listOf(
        Icons.Rounded.MusicNote,
        Icons.Rounded.Person,
        Icons.Rounded.Album,
    ) + if (showPlaylists) listOf(Icons.Rounded.PlaylistPlay) else emptyList()

    OrbBouncyPillSelector(
        labels = labels,
        selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
        onSelected = { index -> tabs.getOrNull(index)?.let(onSelect) },
        icons = icons,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PlaylistsTab(
    playlists: List<OfflinePlaylist>,
    downloadedById: Map<String, Song>,
    onPlaylistClick: (OfflinePlaylist, List<Song>) -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val ordered = remember(playlists) { playlists.sortedByDescending(OfflinePlaylist::savedAtMs) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        item {
            SectionHeader(
                icon = Icons.Rounded.PlaylistPlay,
                title = stringResource(R.string.offline_playlists_tab),
            )
        }
        if (ordered.isEmpty()) {
            item { MessageState(message = stringResource(R.string.offline_playlists_empty)) }
        }
        items(ordered, key = { it.browseId }) { playlist ->
            val downloaded = orderedOfflineItems(playlist.trackIds, downloadedById)
            val total = playlist.trackIds.size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = downloaded.isNotEmpty()) {
                        onPlaylistClick(playlist, downloaded)
                    }
                    .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    val artwork = playlist.artworkUrl ?: downloaded.firstOrNull()?.thumbnailUrl
                    if (!artwork.isNullOrBlank()) {
                        AsyncImage(
                            model = artwork.artworkAt(ROW_ART_PX),
                            contentDescription = playlist.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.offline_playlist_progress, downloaded.size, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (downloaded.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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
