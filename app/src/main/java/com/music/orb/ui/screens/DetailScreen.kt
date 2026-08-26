package com.music.orb.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.music.orb.R
import com.music.orb.data.canvas.CanvasArtwork
import com.music.orb.data.canvas.CanvasRepository
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
import com.music.orb.ui.components.ArtworkWash
import com.music.orb.ui.components.MessageState
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.ROW_DIVIDER_INSET
import com.music.orb.ui.components.SHELF_CARD_WIDTH
import com.music.orb.ui.components.SongRow
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.components.detailSkeleton
import com.music.orb.ui.icons.BitChordIcons
import com.music.orb.ui.player.CanvasArtworkPlayer
import com.music.orb.ui.theme.ArtworkPalette
import com.music.orb.ui.theme.rememberArtworkPalette
import kotlin.math.roundToInt

private const val MAX_ARTIST_SONGS = 20
private const val SONGS_PER_COLUMN = 4
private const val ARTIST_PHOTO_RATIO = 0.95f
private const val SLEEVE_RATIO = 0.92f
private const val SLEEVE_FRACTION = 0.80f

private val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val PILL_SHAPE = RoundedCornerShape(12.dp)
private val HEADER_GUTTER = PAGE_GUTTER + 14.dp
private val HEADER_DROP = 44.dp

@Composable
fun DetailScreen(
    page: DetailPage,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onDownloadAll: (List<Song>) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onToggleLibrary: (() -> Unit)? = null,
) {
    val songs = (page.songs as? UiState.Success)?.data.orEmpty()
    val isArtist = page.type == BrowseType.ARTIST
    val palette = rememberArtworkPalette(page.thumbnailUrl)

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
    val artHeight = LocalConfiguration.current.screenWidthDp.dp /
            if (isArtist) ARTIST_PHOTO_RATIO else SLEEVE_RATIO

    Box(modifier.fillMaxSize()) {
        PageBackground(
            page = page,
            palette = palette,
            canvas = canvas,
            artHeight = artHeight,
            listState = listState,
            hazeState = pageHaze,
            modifier = Modifier.matchParentSize(),
        )

        MergeBand(
            palette = palette,
            artHeight = artHeight,
            listState = listState,
            hazeState = pageHaze,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(page = page, palette = palette, artHeight = artHeight)
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
                        onArtistClick = onArtistClick,
                        onToggleLibrary = onToggleLibrary,
                    )
                }
            }

            if (songs.isNotEmpty() && isArtist) {
                item(key = "actions") {
                    ActionRow(
                        palette = palette,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                    )
                }
            }

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (isArtist) {
                    item {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        SectionHeading("Top songs", palette)
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
                } else {
                    val numbered = page.type == BrowseType.ALBUM
                    itemsIndexed(state.data) { index, song ->
                        SongRow(
                            song = if (numbered) {
                                song
                            } else {
                                song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl)
                            },
                            onClick = { onSongClick(state.data, index) },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            rowBackground = Color.Transparent,
                            trackNumber = (index + 1).takeIf { numbered },
                            subtitleColor = palette.onBackgroundVariant,
                        )
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
                    SectionHeading("Suggested", palette)
                }
                itemsIndexed(
                    page.suggestedSongs,
                    key = { _, song -> "suggested-${song.videoId}" },
                ) { index, song ->
                    SuggestedSongRow(
                        song = song,
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

            items(page.sections) { shelf ->
                Column(Modifier.padding(top = 22.dp)) {
                    SectionHeading(shelf.title, palette)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(shelf.items) { item ->
                            SectionCard(
                                item = item,
                                palette = palette,
                                onClick = { onSectionItemClick(item) },
                            )
                        }
                    }
                }
            }
        }
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
    onArtistClick: (String, String) -> Unit,
    onToggleLibrary: (() -> Unit)?,
) {
    val (credit, meta) = page.headerLines(trackCount, songsCountText, albumLabel, playlistLabel, artistLabel)
    val artist = songs.firstOrNull()

    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP))

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
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
                Text(
                    text = credit,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = HEADER_GUTTER)
                        .let { m ->
                            val id = artist?.artistId
                            if (id == null) {
                                m
                            } else {
                                m.clip(RoundedCornerShape(6.dp))
                                    .clickable { onArtistClick(id, artist.artist) }
                            }
                        },
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

            if (songs.isNotEmpty()) {
                val library = page.library?.takeIf { onToggleLibrary != null }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HEADER_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (library != null) {
                        CircleIconButton(
                            icon = if (library.saved) BitChordIcons.Check else BitChordIcons.Plus,
                            contentDescription = if (library.saved) {
                                "Remove from library"
                            } else {
                                "Add to library"
                            },
                            palette = palette,
                            onClick = { onToggleLibrary?.invoke() },
                        )
                    }
                    CircleIconButton(
                        icon = BitChordIcons.Shuffle,
                        contentDescription = "Shuffle",
                        palette = palette,
                        onClick = onShuffle,
                    )
                    PlayPill(
                        palette = palette,
                        onClick = onPlay,
                        horizontalPadding = if (library != null && onDownload != null) 24.dp else 32.dp,
                    )
                    onDownload?.let { download ->
                        CircleIconButton(
                            icon = BitChordIcons.Download,
                            contentDescription = "Download all",
                            palette = palette,
                            onClick = { download(songs) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(page: DetailPage, palette: ArtworkPalette, artHeight: Dp) {
    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP))
        Text(
            text = page.title,
            style = MaterialTheme.typography.displayLarge,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = HEADER_GUTTER, vertical = 14.dp),
        )
    }
}

@Composable
private fun PageBackground(
    page: DetailPage,
    palette: ArtworkPalette,
    canvas: CanvasArtwork?,
    artHeight: Dp,
    listState: LazyListState,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clipToBounds()
            .hazeSource(hazeState),
    ) {
        ArtworkWash(palette = palette, modifier = Modifier.matchParentSize())

        Box(
            Modifier
                .fillMaxWidth()
                .height(artHeight)
                .offset { IntOffset(0, listState.headerTop(artHeight.toPx()).roundToInt()) },
        ) {
            AsyncImage(
                model = page.thumbnailUrl.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .background(palette.elevated),
            )

            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = true,
                    modifier = Modifier.matchParentSize(),
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.28f)
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.background.copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
            )

            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1.00f to palette.wash.copy(alpha = 0.88f),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun MergeBand(
    palette: ArtworkPalette,
    artHeight: Dp,
    listState: LazyListState,
    hazeState: HazeState,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) return

    Box(
        Modifier
            .fillMaxWidth()
            .height(MERGE_BAND)
            .offset {
                IntOffset(
                    x = 0,
                    y = (
                            listState.headerTop(artHeight.toPx()) +
                                    artHeight.toPx() - MERGE_BAND.toPx() / 2f
                            ).roundToInt(),
                )
            }
            .hazeEffect(hazeState) {
                canDrawArea = { true }
                blurRadius = MERGE_BLUR
                noiseFactor = 0f
                tints = listOf(HazeTint(Color.Transparent))
                backgroundColor = palette.wash
                mask = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.50f to Color.Black,
                    1.00f to Color.Transparent,
                )
            },
    )
}

private fun LazyListState.headerTop(artHeightPx: Float): Float =
    if (firstVisibleItemIndex == 0) -firstVisibleItemScrollOffset.toFloat() else -artHeightPx * 2f

private val MERGE_BAND = 320.dp
private val MERGE_BLUR = 100.dp

@Composable
private fun ActionRow(palette: ArtworkPalette, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = BitChordIcons.Shuffle,
            contentDescription = "Shuffle",
            palette = palette,
            onClick = onShuffle,
        )

        PlayPill(
            palette = palette,
            onClick = onPlay,
        )
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun PlayPill(
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 32.dp,
) {
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = BitChordIcons.Play,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.album_play),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(7.dp))
                .thumbnailBorder(RoundedCornerShape(7.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                contentDescription = "More",
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
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                contentDescription = "Add to playlist",
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(item: ShelfItem, palette: ArtworkPalette, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(SHELF_CARD_WIDTH)
            .clickable(onClick = onClick),
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
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DetailPage.headerLines(
    trackCount: Int,
    songsCountText: String?,
    albumLabel: String,
    playlistLabel: String,
    artistLabel: String
): Pair<String, String> {
    val parts = subtitle.split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }
    val kind = parts.firstOrNull { it.lowercase() in KIND_WORDS }
    val credit = parts.filter { it != year && it != kind }.joinToString(", ")

    val typeLabel = when (type) {
        BrowseType.ALBUM -> albumLabel
        BrowseType.PLAYLIST -> playlistLabel
        BrowseType.ARTIST -> artistLabel
        BrowseType.OTHER -> null
    }

    val meta = listOfNotNull(
        kind ?: typeLabel,
        year,
        songsCountText,
    ).joinToString(" • ").uppercase()
    return credit to meta
}

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