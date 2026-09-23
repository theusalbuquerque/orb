package com.music.orb.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.SongCredits
import com.music.orb.data.model.UiState
import com.music.orb.data.model.artworkAt
import com.music.orb.data.YtMusicRepository
import com.music.orb.download.DownloadState
import com.music.orb.download.Downloads
import com.music.orb.playback.SleepTimer
import com.music.orb.ui.player.rememberArtworkColors
import com.music.orb.ui.flavor.OrbFlavorUi
import com.music.orb.ui.theme.ArtworkPalette
import com.music.orb.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Long-press menu for a track, in the shape music apps normally use.
 *
 * The account actions lead — rating, playlists, library — because they are
 * what the menu is opened for; the queue and navigation rows below it were
 * always the fallback for "I meant to do something with this song".
 *
 * Everything that writes to the account is hidden outright when [signedIn] is
 * false rather than shown and refused. The same goes for a track that is
 * playing from a local file or a finished download (`song.localUri != null`):
 * rating, playlists, downloading it again and sharing all assume a YouTube
 * identity the file doesn't carry, so those rows drop out regardless of
 * [signedIn].
 *
 * [showSleepTimer] and [onShare] are the player's extras: a sleep timer isn't a
 * property of some row in a list, so it only appears where it means something.
 *
 * [onDownload] is only the *start* of a download — cancelling one and deleting
 * a saved file are answered here, because neither needs anything the caller
 * has. Starting one might: below API 29 it needs a storage permission that only
 * an Activity can ask for.
 *
 * The sheet is painted in the track's own colours, the same way its album page
 * is — it is opened *from* that artwork, usually with it still on screen behind
 * the scrim, and a slab of flat grey in front of a coloured page reads as
 * something borrowed from another app. The host supplies no container colour
 * and no drag handle; both are drawn here, over the tint.
 */
@Composable
fun SongActionsSheet(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    inLibrary: Boolean,
    libraryActionAvailable: Boolean,
    onToggleLibrary: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    allowOfflineDownloadRemoval: Boolean = false,
    onDownloadRemoved: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    paletteOverride: ArtworkPalette? = null,
    usePlayerPalette: Boolean = false,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    onShare: (() -> Unit)? = null,
    /**
     * Copies what the app logged while starting this track. Null everywhere
     * except the player, where "this track" means something.
     */
    onCopyLog: (() -> Unit)? = null,
    /**
     * True while a lookup for this track's album/artist ids is still in
     * flight, so it isn't yet known whether "Open album" and "Open artist"
     * belong on this sheet at all. Only the player ever opens a sheet before
     * it knows; everywhere else this is simply false, and the two rows behave
     * as before — present when the id is there, absent when it never was.
     */
    resolvingLinks: Boolean = false,
) {
    var pickingSleepTimer by remember { mutableStateOf(false) }
    var showingCredits by remember(song.videoId) { mutableStateOf(false) }
    val creditsState by produceState<UiState<SongCredits>?>(
        initialValue = null,
        song.videoId,
        showingCredits,
    ) {
        if (!showingCredits) {
            value = null
            return@produceState
        }
        if (song.localUri != null || song.localPath != null) {
            value = UiState.Error("Credits are not available for this local file.")
            return@produceState
        }
        value = UiState.Loading
        value = YtMusicRepository.songCredits(song.videoId).fold(
            onSuccess = { UiState.Success(it) },
            onFailure = { UiState.Error(it.message ?: "Credits unavailable") },
        )
    }
    val creditsReleaseYear by produceState<Int?>(
        initialValue = song.releaseYear,
        song.videoId,
        song.albumId,
        song.releaseYear,
        showingCredits,
    ) {
        value = song.releaseYear
        if (!showingCredits || value != null || song.localUri != null || song.localPath != null) {
            return@produceState
        }

        // Queue/watch rows often know the album/year even when the surface that
        // opened this menu did not. Resolve that lightweight metadata first.
        val linked = YtMusicRepository.trackLinks(song.videoId).getOrNull()
        value = linked?.releaseYear
        if (value != null) return@produceState

        // Some watch responses expose the album but omit its year. The album
        // header is authoritative for this display and already has a parser in
        // YtMusicRepository, so use it as the final catalogue fallback.
        val albumId = song.albumId ?: linked?.albumId ?: return@produceState
        value = YtMusicRepository.browseSongs(albumId)
            .getOrNull()
            ?.releaseSubtitle
            ?.let { subtitle -> Regex("(?:19|20)\\d{2}").find(subtitle)?.value?.toIntOrNull() }
    }
    // Read from the thumbnail the row that opened this sheet was already
    // showing, not a larger copy of it: the tint is a blur and a handful of
    // swatches, neither of which a bigger image improves, and going back for
    // one is what had the sheet opening grey and colouring in afterwards.
    val artworkPalette = rememberArtworkPalette(song.thumbnailUrl, artPx = ROW_ART_PX)
    val playerContentColor = rememberArtworkColors(song.thumbnailUrl).contentColor
    val playerPalette = artworkPalette.copy(
        background = artworkPalette.playerBackground,
        wash = artworkPalette.playerBackground,
        accent = artworkPalette.playerAccent,
        onBackground = playerContentColor,
        onBackgroundVariant = playerContentColor.copy(alpha = 0.78f),
        divider = playerContentColor.copy(alpha = 0.12f),
    )
    val palette = when {
        paletteOverride != null -> paletteOverride
        usePlayerPalette -> playerPalette
        else -> artworkPalette
    }
    // ActionRow reads MaterialTheme colors. Always install the sheet's own
    // artwork-aware foreground/background decision, including when the sheet
    // is opened directly from Home/Explore. Previously only player/detail
    // sheets did this, which could leave dark text on a dark artwork wash.
    val forcePaletteTheme = true
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    // A local file or a finished download has no YouTube identity behind it to
    // rate, save, queue into a playlist, fetch again, or share a link for.
    val isOffline = song.localUri != null || song.localPath != null

    MaterialTheme(
        colorScheme = if (forcePaletteTheme) {
            MaterialTheme.colorScheme.copy(
                primary = palette.accent,
                onBackground = palette.onBackground,
                onSurface = palette.onBackground,
                onSurfaceVariant = palette.onBackgroundVariant,
            )
        } else {
            MaterialTheme.colorScheme
        },
    ) {
        TintedSheet(
            palette = palette,
            imageUrl = song.thumbnailUrl,
            flatPageBackground = paletteOverride != null || usePlayerPalette,
            modifier = modifier,
        ) {
            if (showingCredits) {
                SongCreditsPanel(
                    song = song,
                    state = creditsState,
                    releaseYear = creditsReleaseYear,
                    palette = palette,
                    onBack = { showingCredits = false },
                )
                return@TintedSheet
            }
            if (pickingSleepTimer) {
                SleepTimerPicker(palette = palette, onBack = { pickingSleepTimer = false })
                return@TintedSheet
            }

            ProgressiveActionSheetItem(
                index = 0,
                itemKey = "${song.videoId}:header",
            ) {
                Column {
                    SheetTrackHeader(song, subtitleColor = palette.onBackgroundVariant)
                    HorizontalDivider(thickness = 0.5.dp, color = palette.divider)
                }
            }

            if (signedIn && !isOffline) {
                ProgressiveActionSheetItem(
                    index = 1,
                    itemKey = "${song.videoId}:like",
                ) {
                    ActionRow(
                        icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = if (liked) stringResource(R.string.song_action_remove_liked_music) else stringResource(R.string.song_action_like),
                        tint = if (liked) palette.accent else null,
                        accent = palette.accent,
                        onClick = onToggleLike,
                    )
                }
                ProgressiveActionSheetItem(
                    index = 2,
                    itemKey = "${song.videoId}:dislike",
                ) {
                    ActionRow(
                        icon = if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                        label = if (disliked) stringResource(R.string.song_action_undo_dislike) else stringResource(R.string.song_action_dislike),
                        tint = if (disliked) palette.accent else null,
                        accent = palette.accent,
                        onClick = onToggleDislike,
                    )
                }
            }

            // Keep the original action order here: sharing belongs near the
            // contextual end of the menu, after credits/timer, and still uses
            // the story-card renderer supplied from MainActivity.
            ProgressiveActionSheetItem(
                index = 3,
                itemKey = "${song.videoId}:download",
            ) {
                DownloadRow(song, palette, isOffline, allowOfflineDownloadRemoval, onDownloadRemoved, onDownload)
            }

            if (!isOffline) {
                ProgressiveActionSheetItem(
                    index = 4,
                    itemKey = "${song.videoId}:library",
                ) {
                    ActionRow(
                        icon = if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
                        label = stringResource(
                            if (inLibrary) R.string.song_action_remove_from_library
                            else R.string.song_action_add_to_library,
                        ),
                        tint = if (inLibrary) palette.accent else null,
                        accent = palette.accent,
                        enabled = libraryActionAvailable,
                        onClick = onToggleLibrary,
                    )
                }
            }

            if (signedIn && !isOffline) {
                ProgressiveActionSheetItem(
                    index = 5,
                    itemKey = "${song.videoId}:add-playlist",
                ) {
                    ActionRow(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        label = stringResource(R.string.song_action_add_to_playlist),
                        accent = palette.accent,
                        onClick = onAddToPlaylist,
                    )
                }
                // Contextual playlist editing stays adjacent to the playlist action.
                onRemoveFromPlaylist?.let {
                    ProgressiveActionSheetItem(
                        index = 6,
                        itemKey = "${song.videoId}:remove-playlist",
                    ) {
                        ActionRow(
                            icon = Icons.Rounded.PlaylistRemove,
                            label = stringResource(R.string.song_action_remove_from_playlist),
                            accent = palette.accent,
                            onClick = it,
                        )
                    }
                }
            }

            ProgressiveActionSheetItem(
                index = 7,
                itemKey = "${song.videoId}:play-next",
            ) {
                ActionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    label = stringResource(R.string.song_action_play_next),
                    accent = palette.accent,
                    onClick = onPlayNext,
                )
            }
            ProgressiveActionSheetItem(
                index = 8,
                itemKey = "${song.videoId}:queue",
            ) {
                ActionRow(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    label = stringResource(R.string.song_action_add_to_queue),
                    accent = palette.accent,
                    onClick = onAddToQueue,
                )
            }
            ProgressiveActionSheetItem(
                index = 9,
                itemKey = "${song.videoId}:album",
            ) {
                when (val id = song.albumId) {
                    null -> if (resolvingLinks) LoadingActionRow(Icons.Rounded.Album, stringResource(R.string.song_action_open_album), palette)
                    else -> ActionRow(Icons.Rounded.Album, stringResource(R.string.song_action_open_album), accent = palette.accent) { onOpenAlbum(id) }
                }
            }
            ProgressiveActionSheetItem(
                index = 10,
                itemKey = "${song.videoId}:artist",
            ) {
                when (val id = song.artistId) {
                    null -> if (resolvingLinks) LoadingActionRow(Icons.Rounded.Person, stringResource(R.string.song_action_open_artist), palette)
                    else -> ActionRow(Icons.Rounded.Person, stringResource(R.string.song_action_open_artist), accent = palette.accent) { onOpenArtist(id) }
                }
            }
            ProgressiveActionSheetItem(
                index = 11,
                itemKey = "${song.videoId}:credits",
            ) {
                ActionRow(
                    icon = Icons.Rounded.Info,
                    label = stringResource(R.string.song_action_view_credits),
                    accent = palette.accent,
                ) { showingCredits = true }
            }
            if (showSleepTimer) {
                ProgressiveActionSheetItem(
                    index = 12,
                    itemKey = "${song.videoId}:sleep",
                ) {
                    ActionRow(
                        icon = Icons.Rounded.Bedtime,
                        label = stringResource(R.string.song_action_sleep_timer),
                        value = sleepTimerStatus(),
                        accent = palette.accent,
                    ) { pickingSleepTimer = true }
                }
            }
            onShare?.let {
                ProgressiveActionSheetItem(
                    index = 13,
                    itemKey = "${song.videoId}:share",
                ) {
                    ActionRow(
                        icon = Icons.Rounded.Share,
                        label = stringResource(R.string.song_action_share),
                        accent = palette.accent,
                        onClick = it,
                    )
                }
            }
            // MainActivity supplies this callback only to Beta builds, so the stable
            // app never exposes diagnostics in the public action menu.
            onCopyLog?.let {
                ProgressiveActionSheetItem(
                    index = 14,
                    itemKey = "${song.videoId}:copy-log",
                ) {
                    ActionRow(Icons.Rounded.BugReport, stringResource(R.string.song_action_copy_log), accent = palette.accent, onClick = it)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SongCreditsPanel(
    song: Song,
    state: UiState<SongCredits>?,
    releaseYear: Int?,
    palette: ArtworkPalette,
    onBack: () -> Unit,
) {
    // Credits are a second action-button page, not a static replacement. Give
    // their rows the same progressive top-to-bottom entrance as the primary
    // action list and the artist ranking.
    ProgressiveActionSheetItem(
        index = 0,
        itemKey = "${song.videoId}:credits:header",
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.song_action_back),
                        tint = palette.onBackground,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.song_action_credits_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.onBackground,
                    )
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(48.dp))
            }
            HorizontalDivider(thickness = 0.5.dp, color = palette.divider)
        }
    }

    releaseYear?.let { year ->
        ProgressiveActionSheetItem(
            index = 1,
            itemKey = "${song.videoId}:credits:year:$year",
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.song_action_release_year),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.accent,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.onBackground,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 22.dp),
                    thickness = 0.5.dp,
                    color = palette.divider,
                )
            }
        }
    }

    val contentStartIndex = if (releaseYear != null) 2 else 1
    when (state) {
        null, UiState.Loading -> {
            ProgressiveActionSheetItem(
                index = contentStartIndex,
                itemKey = "${song.videoId}:credits:loading",
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        is UiState.Error -> {
            ProgressiveActionSheetItem(
                index = contentStartIndex,
                itemKey = "${song.videoId}:credits:error",
            ) {
                Text(
                    text = stringResource(R.string.song_action_credits_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                )
            }
        }
        is UiState.Success -> {
            state.data.sections.forEachIndexed { index, section ->
                ProgressiveActionSheetItem(
                    index = contentStartIndex + index,
                    itemKey = "${song.videoId}:credits:section:$index:${section.title}",
                ) {
                    Column {
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 22.dp),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = localizedCreditSectionTitle(section.title),
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.accent,
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = section.names.joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/**
 * YouTube can return credit-role headings in the account/server language instead of the app
 * locale. Keep contributor names untouched, but map every visible role heading to Orb's own
 * localized vocabulary so opening Credits never leaks English into a Portuguese/Spanish UI.
 * Unknown roles use a localized "Additional credits" bucket instead of showing raw source text.
 */
@Composable
private fun localizedCreditSectionTitle(raw: String): String {
    val value = raw.trim().lowercase()
    val resId = when {
        value.contains("performed by") || value.contains("interpretado por") ||
            value.contains("interpretation") || value.contains("interpretación") ||
            value.contains("interpretação") -> R.string.song_credit_performed_by
        value.contains("written by") || value.contains("escrito por") ||
            value.contains("composi") -> R.string.song_credit_written_by
        value.contains("produced by") || value.contains("producido por") ||
            value.contains("produzido por") || value == "production" ||
            value == "producción" || value == "produção" -> R.string.song_credit_produced_by
        value.contains("metadata") || value.contains("metadatos") || value.contains("metadados") ->
            R.string.song_credit_metadata_by
        value.contains("composer") || value.contains("compositor") -> R.string.song_credit_composer
        value.contains("lyric") || value.contains("letrista") || value == "letra" -> R.string.song_credit_lyricist
        value.contains("vocal") || value.contains("voz") -> R.string.song_credit_vocals
        value.contains("guitar") || value.contains("guitarra") -> R.string.song_credit_guitar
        value == "bass" || value.contains("bass guitar") || value == "baixo" || value == "bajo" -> R.string.song_credit_bass
        value.contains("drum") || value.contains("bateria") || value.contains("batería") -> R.string.song_credit_drums
        value.contains("keyboard") || value.contains("teclado") -> R.string.song_credit_keyboards
        value.contains("piano") -> R.string.song_credit_piano
        value.contains("programming") || value.contains("programación") || value.contains("programação") ->
            R.string.song_credit_programming
        value.contains("mixing") || value.contains("mix engineer") || value.contains("mezcla") || value.contains("mixagem") ->
            R.string.song_credit_mixing
        value.contains("mastering") || value.contains("masterización") || value.contains("masterização") ->
            R.string.song_credit_mastering
        value.contains("recording") || value.contains("grabación") || value.contains("gravação") ->
            R.string.song_credit_recording
        value.contains("publisher") || value.contains("editorial") || value.contains("editora") ->
            R.string.song_credit_publisher
        value.contains("label") || value.contains("discográfica") || value.contains("gravadora") ->
            R.string.song_credit_label
        else -> R.string.song_credit_additional
    }
    return stringResource(resId)
}

/**
 * A bottom sheet wearing the artwork's colours: the tint and its blurred wash
 * behind, the rounded top corners and the drag handle drawn over it.
 *
 * The corners and the handle are this composable's job rather than
 * `ModalBottomSheet`'s because the host has to pass a transparent container for
 * the wash to be visible at all — and a transparent container has nothing left
 * to clip or to hang a handle on.
 */
@Composable
private fun TintedSheet(
    palette: ArtworkPalette,
    imageUrl: String?,
    flatPageBackground: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(SHEET_SHAPE),
    ) {
        if (flatPageBackground) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(palette.background),
            )
        } else {
            ArtworkBackdrop(
                palette = palette,
                imageUrl = imageUrl,
                modifier = Modifier.matchParentSize(),
                // A sheet is a fraction of the height of a page, so the wash has
                // to resolve over a much shorter run to read the same way.
                washFraction = 0.75f,
                artPx = ROW_ART_PX,
            )
        }
        Column(Modifier.fillMaxWidth()) {
            // Drawn rather than taken from BottomSheetDefaults, whose handle
            // carries 22dp of padding on each side — half a row's worth of
            // nothing between the grip and the track it is about.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 34.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(palette.onBackground.copy(alpha = 0.35f)),
                )
            }
            val actionScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(actionScroll),
            ) {
                content()
            }
        }
    }
}

private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * One row carrying the whole life of a download: start it, watch it, cancel it,
 * and delete what it produced.
 *
 * A row rather than a screen because that is the size of the decision. The
 * files land in the device's own Music folder, which already has a manager
 * — the Files app — and building a second one inside this app would be
 * duplicating it in a worse place. What this app uniquely knows is which *song*
 * a file belongs to, and that is exactly what this row says.
 *
 * The state comes straight from [Downloads] rather than through the caller: it
 * changes while the sheet is open, and threading a flow through the sheet's
 * signature would buy nothing over reading it where it's drawn — the same
 * arrangement the sleep timer row already uses.
 */
@Composable
private fun DownloadRow(
    song: Song,
    palette: ArtworkPalette,
    isOffline: Boolean,
    allowOfflineDownloadRemoval: Boolean,
    onDownloadRemoved: (() -> Unit)?,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    // A normal network row is removable only when Downloads itself recorded
    // the file. Inside Library > Downloads we also accept the scanned local URI
    // as proof, so older/orphaned Orb downloads can be removed too. This flag is
    // intentionally supplied by the host page so arbitrary device music never
    // gains a destructive delete action.
    val recorded = saved[song.videoId]
    val scannedDownloadUri = song.localUri
        ?.takeIf { allowOfflineDownloadRemoval }
        ?.let(Uri::parse)
    val file by produceState(
        initialValue = recorded?.let(Uri::parse) ?: scannedDownloadUri,
        song.videoId,
        recorded,
        song.localUri,
        allowOfflineDownloadRemoval,
    ) {
        value = Downloads.savedUri(context, song.videoId) ?: scannedDownloadUri
    }

    // A failure is worth stating once. Leaving it set would have the row still
    // reporting last week's dead connection the next time the sheet is opened.
    DisposableEffect(song.videoId) {
        onDispose { Downloads.dismissFailure(song.videoId) }
    }

    when (val state = active[song.videoId]) {
        is DownloadState.Queued -> ActionRow(
            icon = Icons.Rounded.Downloading,
            label = stringResource(R.string.song_action_queued),
            value = stringResource(R.string.song_action_cancel),
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Running -> ActionRow(
            icon = Icons.Rounded.Downloading,
            label = stringResource(R.string.song_action_downloading),
            value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null,
            tint = palette.accent,
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Failed -> ActionRow(
            icon = Icons.Rounded.ErrorOutline,
            label = state.reason,
            value = stringResource(R.string.song_action_retry),
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            onClick = onDownload,
        )

        null -> if (file != null) {
            ActionRow(
                icon = Icons.Rounded.DownloadDone,
                label = stringResource(R.string.song_action_saved_to_downloads),
                value = stringResource(R.string.song_action_remove_download),
                tint = palette.accent,
                accent = palette.accent,
            ) {
                scope.launch {
                    if (Downloads.delete(context, song)) onDownloadRemoved?.invoke()
                }
            }
        } else if (!isOffline) {
            ActionRow(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.song_action_download),
                accent = palette.accent,
                onClick = onDownload,
            )
        }
    }
}

/** End of track or a duration, plus a way out once one is running. */
@Composable
private fun SleepTimerPicker(palette: ArtworkPalette, onBack: () -> Unit) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val countdown = sleepTimerCountdown()

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.then(
                    if (OrbFlavorUi.expressive) Modifier.size(44.dp) else Modifier
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.song_action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.song_action_sleep_timer),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = when {
                        countdown != null -> stringResource(R.string.song_action_sleep_countdown, countdown)
                        afterTrack -> stringResource(R.string.song_action_pausing_when_song_ends)
                        else -> stringResource(R.string.song_action_pause_after_a_while)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        // Finishing the song is the one people reach for at the end of a
        // listen, so it leads rather than sitting under the durations.
        SleepOption(label = stringResource(R.string.song_action_after_this_song), selected = afterTrack, accent = palette.accent) {
            SleepTimer.startAfterTrack()
            onBack()
        }
        SleepTimer.PRESETS.forEach { minutes ->
            SleepOption(
                label = if (minutes == 60) stringResource(R.string.song_action_one_hour) else stringResource(R.string.song_action_minutes, minutes),
                selected = minutes == chosen,
                accent = palette.accent,
            ) {
                SleepTimer.start(minutes)
                onBack()
            }
        }
        if (chosen != null || afterTrack) {
            ActionRow(Icons.Rounded.Close, stringResource(R.string.song_action_turn_off_timer), accent = palette.accent) {
                SleepTimer.cancel()
                onBack()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SleepOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.song_action_running),
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the sleep timer row shows on the right, or null when none is armed. */
@Composable
private fun sleepTimerStatus(): String? {
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    return sleepTimerCountdown() ?: stringResource(R.string.song_action_after_this_song).takeIf { afterTrack }
}

/** Live "m:ss" until the sleep timer fires, or null when none is running. */
@Composable
private fun sleepTimerCountdown(): String? {
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState<Long?>(initialValue = SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let {
        val seconds = it / 1000
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

/**
 * One line of a bottom sheet's menu. Shared with the playlist picker so the
 * two sheets read as the same control rather than as two lists that happen to
 * look alike.
 *
 * [tint] is for rows whose icon carries state — a filled heart on a liked
 * track — and is otherwise the ordinary foreground. [accent] colours the
 * trailing [value], and defaults to the app's own red: a sheet tinted from
 * artwork passes the artwork's accent instead, so the row belongs to the sheet
 * it is drawn on.
 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.48f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (tint ?: MaterialTheme.colorScheme.onBackground).copy(alpha = contentAlpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = accent.copy(alpha = contentAlpha),
                maxLines = 1,
            )
        }
    }
}

/**
 * Stands in for [ActionRow] while whether it belongs on the sheet at all is
 * still unknown — "Open album" or "Open artist" before the lookup for their
 * ids has come back. A spinner rather than the row simply being missing, so
 * it doesn't read as decided against until it actually is.
 */
@Composable
private fun LoadingActionRow(icon: ImageVector, label: String, palette: ArtworkPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.onBackground.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.onBackground.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f),
        )
        CircularProgressIndicator(
            color = palette.onBackgroundVariant,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The track a sheet is about, drawn at its head. Shared by the actions menu
 * and the playlist picker, which is the same track two taps later.
 *
 * [subtitleColor] exists because the two sheets stand on different ground: the
 * picker's is the flat theme background, where the usual dim grey is right,
 * while the actions sheet is tinted from this very artwork and needs the
 * credit brighter to stay off the wash.
 */
@Composable
internal fun SheetTrackHeader(
    song: Song,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitTitle(
                text = song.title,
                isExplicit = song.isExplicit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The heading over a sheet's second half — "Add to playlist". */
@Composable
internal fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}
