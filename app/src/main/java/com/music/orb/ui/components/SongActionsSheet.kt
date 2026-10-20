package com.music.orb.ui.components

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.drawscope.clipRect

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.Delete
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
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.SongCredits
import com.music.orb.data.model.UserPlaylist
import com.music.orb.data.model.PlaylistPrivacy
import com.music.orb.data.model.UiState
import com.music.orb.data.model.artworkAt
import com.music.orb.data.YtMusicRepository
import com.music.orb.download.DownloadState
import com.music.orb.download.OfflineDownloadQuality
import com.music.orb.data.settings.AppSettings
import com.music.orb.download.Downloads
import com.music.orb.playback.SleepTimer
import com.music.orb.ui.player.rememberArtworkColors
import com.music.orb.ui.flavor.OrbFlavorUi
import com.music.orb.ui.theme.ArtworkPalette
import com.music.orb.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// V28 reversible frost trial: ~9% relative increase, keeping artwork tint.
private const val ACTION_SHEET_GLASS_OPACITY = 0.37f

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
 * The sheet uses Orb Expressive Glass: a neutral frosted material carries the
 * component identity while the track artwork enters only as a restrained tint.
 * The hero still belongs fully to the artwork, so the menu feels connected to
 * the song without turning the sheet into a flat slab of its dominant colour.
 * The host supplies no container colour and no drag handle; both are drawn here.
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
    playlistPickerVisible: Boolean = false,
    playlists: List<UserPlaylist> = emptyList(),
    playlistsLoading: Boolean = false,
    onPlaylistPick: (UserPlaylist) -> Unit = {},
    onPlaylistCreate: (String, PlaylistPrivacy) -> Unit = { _, _ -> },
    onPlaylistBack: () -> Unit = {},
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    allowOfflineDownloadRemoval: Boolean = false,
    onDownloadRemoved: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    paletteOverride: ArtworkPalette? = null,
    usePlayerPalette: Boolean = false,
    closing: Boolean = false,
    onHandleDismissRequest: () -> Unit = {},
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
    var entered by remember(song.videoId) { mutableStateOf(false) }
    var sheetOpened by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId) { entered = true }
    val sheetMotion by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered && !closing) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.88f,
            stiffness = 290f,
        ),
        finishedListener = { value -> sheetOpened = value == 1f && !closing },
        label = "actionSheetMotion",
    )
    val motionDensity = LocalDensity.current
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

    // The Action Button belongs visually to the track, not to the page that happened
    // to open it. Keep the originating page only as a subtle environmental influence:
    // 90% of the sheet surface comes from the artwork and 10% from the page/player
    // context. This makes the same song retain essentially the same sheet identity
    // across Home, album, search and Now Playing while still feeling embedded in the
    // surface underneath the glass.
    val contextSurface = when {
        paletteOverride != null -> paletteOverride.background
        usePlayerPalette -> playerPalette.background
        else -> MaterialTheme.colorScheme.background
    }
    val artworkSurface = lerp(
        artworkPalette.playerBackground,
        artworkPalette.wash,
        0.24f,
    )
    val actionSheetSurface = lerp(
        contextSurface,
        artworkSurface,
        0.90f,
    )
    // The floating title sits over the real page/player backdrop, not over the glass
    // panel. All sheets now dim their outside backdrop by 10%, so choose the
    // title colour against that perceived surface and reuse it across the sheet
    // label/icon. This is what prevents black text from disappearing on dark red/purple
    // artwork while keeping dark text on genuinely light backdrops.
    val perceivedHeroBackdrop = lerp(contextSurface.copy(alpha = 1f), Color.Black, 0.10f)
    val heroForegrounds = artworkSurfaceForegrounds(perceivedHeroBackdrop)
    // The same 10% scrim now also covers the page underneath the panel.
    // Estimate text contrast against that uniformly dimmed backdrop plus the
    // translucent artwork-coloured glass, not the raw undimmed page.
    val expectedActionSheetSurface = actionSheetSurface.copy(alpha = ACTION_SHEET_GLASS_OPACITY)
        .compositeOver(perceivedHeroBackdrop)
    val actionSheetForegrounds = artworkSurfaceForegrounds(expectedActionSheetSurface)
    val palette = artworkPalette.copy(
        background = actionSheetSurface,
        wash = actionSheetSurface,
        elevated = lerp(
            paletteOverride?.elevated ?: MaterialTheme.colorScheme.surfaceVariant,
            artworkPalette.elevated,
            0.90f,
        ),
        accent = actionSheetForegrounds.primary,
        onBackground = actionSheetForegrounds.primary,
        onBackgroundVariant = actionSheetForegrounds.secondary,
        divider = actionSheetForegrounds.primary.copy(alpha = 0.12f),
        playerBackground = actionSheetSurface,
        playerOnBackground = actionSheetForegrounds.primary,
        playerAccent = actionSheetForegrounds.primary,
    )

    // Action-button pages use one foreground color throughout. Artwork still owns the
    // background, but labels, values and icons no longer alternate between accent and
    // foreground colors. This keeps nested pages such as Credits visually coherent.
    val actionPalette = palette.copy(
        accent = palette.onBackground,
        onBackgroundVariant = palette.onBackground,
        divider = palette.onBackground.copy(alpha = 0.12f),
    )
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

    // The action sheet may only be dismissed by dragging its top handle (or by the
    // system back gesture handled by the host). Keep the drag state here so the
    // physical sheet and the floating cover/CD react to the exact same gesture.
    val dragConfiguration = LocalConfiguration.current
    val dragIsLandscape = dragConfiguration.screenWidthDp > dragConfiguration.screenHeightDp
    val dragIsTablet = dragConfiguration.smallestScreenWidthDp >= 600
    val handleDismissThresholdPx = with(motionDensity) { 72.dp.toPx() }
    // The CD only needs ~72dp of drag to disappear behind the sleeve, but the sheet
    // must keep following the finger all the way to the bottom edge. Match the same
    // fixed-height fraction used by TintedSheet so a full drag can move the surface
    // completely off-screen instead of stopping as soon as the disc is hidden.
    val handleMaxTravelPx = with(motionDensity) {
        val sheetFraction = when {
            !dragIsTablet && dragIsLandscape -> 0.50f
            dragIsLandscape -> 0.54f
            else -> 0.52f
        }
        (dragConfiguration.screenHeightDp * sheetFraction).dp.toPx()
    }
    var handleDragging by remember(song.videoId) { mutableStateOf(false) }
    var handleDismissCommitted by remember(song.videoId) { mutableStateOf(false) }
    var handleDragTargetPx by remember(song.videoId) { mutableFloatStateOf(0f) }
    val handleDragVisualPx by androidx.compose.animation.core.animateFloatAsState(
        targetValue = handleDragTargetPx,
        animationSpec = if (handleDragging) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.spring(
                dampingRatio = 0.82f,
                stiffness = 430f,
            )
        },
        label = "actionHandleDrag",
    )
    val handleDismissProgress = (handleDragVisualPx / handleDismissThresholdPx)
        .coerceIn(0f, 1f)

    var actionSheetTopPx by remember(song.videoId) { mutableFloatStateOf(0f) }
    MaterialTheme(
        colorScheme = if (forcePaletteTheme) {
            MaterialTheme.colorScheme.copy(
                primary = actionPalette.onBackground,
                onBackground = actionPalette.onBackground,
                onSurface = actionPalette.onBackground,
                onSurfaceVariant = actionPalette.onBackground,
            )
        } else {
            MaterialTheme.colorScheme
        },
    ) {
        ActionSheetSystemBackHandler(
            enabled = !closing,
            onBack = {
                when {
                    playlistPickerVisible -> onPlaylistBack()
                    showingCredits -> showingCredits = false
                    pickingSleepTimer -> pickingSleepTimer = false
                    else -> onHandleDismissRequest()
                }
            },
        )
        ActionSheetWindowEffects(
            // The outer scrim already shades the area above the panel. Native
            // DIM_BEHIND would darken the actual backdrop seen *through* the glass.
            dimAmount = 0f,
            blurRadius = 84.dp,
        )
        ActionSheetNavigationBar(
            background = palette.background,
            foreground = actionPalette.onBackground,
        )
        ActionSheetAlbumHero(
            song = song,
            foreground = heroForegrounds.primary,
            badgePageColor = palette.background,
            closing = closing,
            gestureDismissProgress = handleDismissProgress,
            sheetTopPx = actionSheetTopPx,
            openingReady = sheetOpened,
            handleTravelProgress = ((handleDragVisualPx - handleDismissThresholdPx) /
                (handleMaxTravelPx - handleDismissThresholdPx)).coerceIn(0f, 1f),
            exitThroughBottom = handleDismissCommitted,
            sheetDragOffsetPx = handleDragVisualPx,
        )
        TintedSheet(
            palette = palette,
            imageUrl = song.thumbnailUrl,
            flatPageBackground = paletteOverride != null || usePlayerPalette,
            handleTranslationPx = handleDragVisualPx,
            onHandleDragStart = {
                handleDragging = true
            },
            onHandleDragDelta = { delta ->
                handleDragTargetPx = (handleDragTargetPx + delta)
                    .coerceIn(0f, handleMaxTravelPx)
            },
            onHandleDragStop = { velocity ->
                handleDragging = false
                val shouldDismiss =
                    handleDragTargetPx >= handleDismissThresholdPx || velocity > 1_150f
                if (shouldDismiss) {
                    // The disc is already fully retracted at the threshold, but the sheet
                    // itself should continue to the physical bottom edge. Animate the remaining
                    // travel after release while the host runs the coordinated close sequence.
                    handleDismissCommitted = true
                    handleDragTargetPx = handleMaxTravelPx
                    onHandleDismissRequest()
                } else {
                    // A cancelled handle drag springs both the sheet and the CD back open.
                    handleDragTargetPx = 0f
                }
            },
            modifier = modifier
                .offset {
                    val revealOffset = with(motionDensity) { 20.dp.toPx() }
                    IntOffset(0, ((1f - sheetMotion) * revealOffset).roundToInt())
                }
                .graphicsLayer { alpha = 0.88f + 0.12f * sheetMotion }
                .onGloballyPositioned { actionSheetTopPx = it.boundsInWindow().top },
        ) {
            if (playlistPickerVisible) {
                // This is a second page of the same action sheet, not another modal.
                // Replacing only the sheet body lets the original action list leave
                // while the cover/CD hero above it remains physically untouched.
                PlaylistPickerSheet(
                    playlists = playlists,
                    loading = playlistsLoading,
                    song = song,
                    startCreating = false,
                    showTrackHeader = false,
                    sheetPageColor = palette.background,
                    onPick = onPlaylistPick,
                    onCreate = onPlaylistCreate,
                )
                return@TintedSheet
            }
            if (showingCredits) {
                SongCreditsPanel(
                    song = song,
                    state = creditsState,
                    releaseYear = creditsReleaseYear,
                    palette = actionPalette,
                    onBack = { showingCredits = false },
                )
                return@TintedSheet
            }
            if (pickingSleepTimer) {
                SleepTimerPicker(palette = actionPalette, onBack = { pickingSleepTimer = false })
                return@TintedSheet
            }

            if (signedIn && !isOffline) {
                ProgressiveActionSheetItem(
                    index = 1,
                    itemKey = "${song.videoId}:like",
                ) {
                    ActionRow(
                        icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = if (liked) stringResource(R.string.song_action_remove_liked_music) else stringResource(R.string.song_action_like),
                        tint = if (liked) actionPalette.onBackground else null,
                        accent = actionPalette.onBackground,
                        hapticKind = OrbHaptics.Kind.LIKE,
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
                        tint = if (disliked) actionPalette.onBackground else null,
                        accent = actionPalette.onBackground,
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
                DownloadRow(song, actionPalette, isOffline, allowOfflineDownloadRemoval, onDownloadRemoved, onDownload)
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
                        tint = if (inLibrary) actionPalette.onBackground else null,
                        accent = actionPalette.onBackground,
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
                        accent = actionPalette.onBackground,
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
                            accent = actionPalette.onBackground,
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
                    accent = actionPalette.onBackground,
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
                    accent = actionPalette.onBackground,
                    onClick = onAddToQueue,
                )
            }
            ProgressiveActionSheetItem(
                index = 9,
                itemKey = "${song.videoId}:album",
            ) {
                when (val id = song.albumId) {
                    null -> if (resolvingLinks) LoadingActionRow(Icons.Rounded.Album, stringResource(R.string.song_action_open_album), actionPalette)
                    else -> ActionRow(Icons.Rounded.Album, stringResource(R.string.song_action_open_album), accent = actionPalette.onBackground) { onOpenAlbum(id) }
                }
            }
            ProgressiveActionSheetItem(
                index = 10,
                itemKey = "${song.videoId}:artist",
            ) {
                when (val id = song.artistId) {
                    null -> if (resolvingLinks) LoadingActionRow(Icons.Rounded.Person, stringResource(R.string.song_action_open_artist), actionPalette)
                    else -> ActionRow(Icons.Rounded.Person, stringResource(R.string.song_action_open_artist), accent = actionPalette.onBackground) { onOpenArtist(id) }
                }
            }
            ProgressiveActionSheetItem(
                index = 11,
                itemKey = "${song.videoId}:credits",
            ) {
                ActionRow(
                    icon = Icons.Rounded.Info,
                    label = stringResource(R.string.song_action_view_credits),
                    accent = actionPalette.onBackground,
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
                        accent = actionPalette.onBackground,
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
                        accent = actionPalette.onBackground,
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
                    ActionRow(Icons.Rounded.BugReport, stringResource(R.string.song_action_copy_log), accent = actionPalette.onBackground, onClick = it)
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
                        color = palette.onBackground,
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
                        color = palette.onBackground,
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
                        color = palette.onBackground,
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
                    color = palette.onBackground,
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
                                color = palette.onBackground,
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

@Composable
private fun ActionSheetSystemBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val currentOnBack by rememberUpdatedState(onBack)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        DisposableEffect(view, enabled) {
            if (!enabled) {
                onDispose { }
            } else {
                val window = (view.parent as? DialogWindowProvider)?.window
                if (window == null) {
                    onDispose { }
                } else {
                    // ModalBottomSheet owns a separate dialog window. With
                    // shouldDismissOnBackPress=false, Activity-level BackHandlers never see
                    // the system back gesture. Register directly on this window instead so
                    // Back remains an explicit allowed dismissal path while scrim taps stay
                    // disabled.
                    val callback = OnBackInvokedCallback { currentOnBack() }
                    val dispatcher = window.onBackInvokedDispatcher
                    dispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        callback,
                    )
                    onDispose {
                        dispatcher.unregisterOnBackInvokedCallback(callback)
                    }
                }
            }
        }
    } else {
        // Legacy devices still use the Activity back dispatcher. Keeping this inside the
        // sheet means it is registered after the host handler and therefore owns Back while
        // the action sheet is visible.
        BackHandler(enabled = enabled) { currentOnBack() }
    }
}

@Composable
private fun ActionSheetWindowEffects(
    dimAmount: Float,
    blurRadius: Dp,
) {
    val view = LocalView.current
    val density = LocalDensity.current

    DisposableEffect(view, dimAmount, blurRadius, density) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousAttributes = WindowManager.LayoutParams().also { it.copyFrom(window.attributes) }
            val previousDim = window.attributes.dimAmount
            val radiusPx = with(density) { blurRadius.roundToPx() }

            window.setDimAmount(dimAmount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = radiusPx
                }
                runCatching { window.setBackgroundBlurRadius(radiusPx) }
            }

            onDispose {
                window.attributes = previousAttributes
                window.setDimAmount(previousDim)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { window.setBackgroundBlurRadius(0) }
                }
            }
        }
    }
}

@Composable
internal fun ActionSheetAlbumHero(
    song: Song,
    foreground: Color,
    badgePageColor: Color,
    closing: Boolean,
    gestureDismissProgress: Float,
    sheetTopPx: Float,
    openingReady: Boolean,
    handleTravelProgress: Float,
    exitThroughBottom: Boolean,
    sheetDragOffsetPx: Float,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isTabletLandscape = isTablet && isLandscape

    var coverVisible by remember(song.videoId) { mutableStateOf(false) }
    var discVisible by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId, closing, openingReady) {
        if (closing) {
            // Retract the CD behind the sleeve first; only then fade the whole hero.
            discVisible = false
            delay(300L)
            coverVisible = false
        } else if (openingReady) {
            // Begin behind the sheet only after its entrance spring has settled.
            delay(80L)
            coverVisible = true
            discVisible = false
            delay(1_000L)
            discVisible = true
        } else {
            coverVisible = false
            discVisible = false
        }
    }

    // Keep the hero visually prominent without letting it dominate the screen.
    // The previous revision was slightly oversized on both tablet orientations;
    // reduce cover + disc by exactly 10% while preserving their proportions.
    val isPhoneLandscape = !isTablet && isLandscape
    val baseCoverSize = when {
        // Tablet landscape has only ~46% of the short axis above the fixed sheet.
        // Use a compact hero so artwork + metadata never touch the menu surface.
        // Slightly larger than v3: enough presence on tablet landscape without
        // returning to the oversized hero that collided with the sheet.
        isTabletLandscape -> 184.dp
        isTablet -> 270.dp
        // Landscape phones have only a narrow strip above the fixed action sheet.
        // Use a purpose-built compact hero here instead of overlapping the menu.
        isPhoneLandscape -> 124.dp
        else -> 246.dp
    }
    val coverSize = baseCoverSize * 0.90f
    val discSize = coverSize * 0.96f
    val heroWidth = when {
        isTabletLandscape -> 382.dp
        isPhoneLandscape -> 260.dp
        else -> coverSize + if (isTablet) 130.dp else 114.dp
    }
    val compactLandscapeHero = isPhoneLandscape || isTabletLandscape
    val heroHeight = coverSize + if (compactLandscapeHero) 48.dp else 82.dp

    val coverRise by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (coverVisible || exitThroughBottom) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(340, easing=androidx.compose.animation.core.FastOutSlowInEasing),
        label = "actionCoverRise",
    )
    val coverScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (coverVisible) 1f else 0.90f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.84f,
            stiffness = 300f,
        ),
        label = "actionCoverScale",
    )
    val coverAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 260),
        label = "actionCoverAlpha",
    )
    val discReveal by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (discVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.78f,
            stiffness = 230f,
        ),
        label = "actionDiscReveal",
    )
    val discAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (discVisible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 260),
        label = "actionDiscAlpha",
    )
    val discRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (discVisible) 14f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.82f,
            stiffness = 190f,
        ),
        label = "actionDiscRotation",
    )
    // Pulling the handle down reverses the reveal in real time: the disc slides
    // behind the cover and the cover returns to center before the sheet disappears.
    val effectiveDiscReveal = discReveal * (1f - gestureDismissProgress.coerceIn(0f, 1f))

    val discTravel = when {
        isTabletLandscape -> 70.dp
        isTablet -> 110.dp
        isPhoneLandscape -> 50.dp
        else -> 88.dp
    }
    // Keep the disc and sleeve on the exact same vertical centerline. The realistic
    // PNG is slightly smaller than the cover, so a fixed 8dp offset made it look low.
    val discVerticalOffset = (coverSize - discSize) / 2f
    // The hero is window-relative, but it was hugging the system bar too closely.
    // Move the whole composition down while keeping it independent of the sheet.
    val popupTopMargin = when {
        isTabletLandscape -> 8.dp
        // Portrait tablets have substantially more vertical room than landscape.
        // Keep the horizontal layout exactly where it was, but lower the hero in
        // portrait so the empty space above/below reads balanced instead of top-heavy.
        isTablet -> 94.dp
        isPhoneLandscape -> 10.dp
        else -> 56.dp
    }
    val popupTopMarginPx = with(density) { popupTopMargin.roundToPx() }
    val sheetFraction = when { isPhoneLandscape -> .50f; isLandscape -> .54f; else -> .52f }
    val heroTop = if (isLandscape)
        maxOf(popupTopMargin, configuration.screenHeightDp.dp * (1f-sheetFraction)/2 - heroHeight/2)
        else popupTopMargin
    // graphicsLayer translations do not trigger onGloballyPositioned callbacks.
    // Add the animated handle offset explicitly so the occlusion edge follows the sheet.
    val sheetEdgePx = (if(sheetTopPx>0f) sheetTopPx + sheetDragOffsetPx - with(density) {heroTop.toPx()}
        else sheetDragOffsetPx + with(density) {(configuration.screenHeightDp.dp*(1f-sheetFraction)-heroTop).toPx()})
        .coerceAtLeast(with(density) {heroHeight.toPx()})
    val bottomExitPx = with(density) { (configuration.screenHeightDp.dp - heroTop + 24.dp).toPx() }
    // Keep the overlay clipped to the moving sheet edge: artwork descends behind it.
    val popupHeight = with(density) {sheetEdgePx.toDp()}
    val popupPositionProvider = remember(isTablet, isLandscape, isTabletLandscape, isPhoneLandscape, popupTopMarginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // Intentionally ignore anchorBounds: the hero belongs to the window, not to
                // the ModalBottomSheet. That keeps it in the same place even when the sheet
                // changes height, scrolls, or opens a nested action page.
                val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
                val y = when {
                    isPhoneLandscape -> {
                        // Phone landscape sheet is 50% of the window. Center the compact hero
                        // inside the remaining upper half so it can never overlap the menu.
                        val upperFreeHeight = windowSize.height / 2
                        ((upperFreeHeight - with(density) {heroHeight.roundToPx()}) / 2)
                            .coerceAtLeast(popupTopMarginPx)
                    }
                    isTabletLandscape -> {
                        // Tablet landscape sheet occupies 54% of the short axis. Center the
                        // compact hero in the remaining 46% and keep all metadata above it.
                        val upperFreeHeight = (windowSize.height * 0.46f).roundToInt()
                        ((upperFreeHeight - with(density) {heroHeight.roundToPx()}) / 2)
                            .coerceAtLeast(popupTopMarginPx)
                    }
                    else -> popupTopMarginPx
                }
                return IntOffset(x = x, y = y)
            }
        }
    }


    Popup(
        popupPositionProvider = popupPositionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .width(heroWidth)
                .height(popupHeight)
                .drawWithContent {
                    clipRect(bottom=sheetEdgePx) { this@drawWithContent.drawContent() }
                }
                .graphicsLayer {
                    translationY=(1f-coverRise) * (sheetEdgePx+24.dp.toPx()) + handleTravelProgress * bottomExitPx
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            // The disc stays completely hidden for the first second. Its layer is declared
            // before the cover, so the slide always reads as emerging from behind the sleeve.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Split the reveal motion between sleeve and disc. The cover moves
                    // left while the disc moves right, keeping the *pair* centered in the
                    // window instead of leaving the disc hanging off the right side.
                    .offset(
                        x = discTravel * 0.52f * effectiveDiscReveal,
                        y = discVerticalOffset,
                    )
                    .size(discSize)
                    .graphicsLayer {
                        alpha = discAlpha
                        rotationZ = discRotation * effectiveDiscReveal
                    },
            ) {
                Image(
                    painter = painterResource(R.drawable.action_button_disc_realistic),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Fit,
                )
            }

            AsyncImage(
                model = song.thumbnailUrl?.artworkAt(720),
                contentDescription = song.albumName ?: song.title,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = -(discTravel * 0.48f * effectiveDiscReveal))
                    .size(coverSize)
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                        alpha = coverAlpha
                    }
                    .clip(RoundedCornerShape(20.dp)),
            )

            // Track identity belongs to the floating hero, not to the action sheet.
            // Keep the whole title + Explicit seal centered as one unit.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = coverSize + if (compactLandscapeHero) 6.dp else 14.dp)
                    .width(heroWidth - 20.dp)
                    .alpha(coverAlpha),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = song.title,
                        style = if (compactLandscapeHero) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        },
                        color = foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = if (compactLandscapeHero) heroWidth - 32.dp else heroWidth - 64.dp),
                    )
                    if (song.isExplicit) {
                        Spacer(Modifier.width(5.dp))
                        ExplicitBadgeSeal(
                            pageColor = badgePageColor,
                            size = 14.5.dp,
                            contentColorOverride = foreground,
                        )
                    }
                }
                Spacer(Modifier.height(if (compactLandscapeHero) 1.dp else 3.dp))
                Text(
                    text = song.artist,
                    style = if (compactLandscapeHero) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = foreground.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ActionSheetNavigationBar(
    background: Color,
    foreground: Color,
) {
    val view = LocalView.current
    val activityWindow = view.context.findActivity()?.window

    DisposableEffect(view, activityWindow, background, foreground) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val windows = listOfNotNull(dialogWindow, activityWindow).distinct()
        val snapshots = windows.map { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            ActionNavBarSnapshot(
                window = window,
                color = window.navigationBarColor,
                contrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced
                } else {
                    null
                },
                lightIcons = controller.isAppearanceLightNavigationBars,
            )
        }

        windows.forEach { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            window.navigationBarColor = if (dialogWindow != null) Color.Transparent.toArgb() else background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            // Android's "light navigation bars" flag means dark system glyphs.
            controller.isAppearanceLightNavigationBars = foreground.luminance() < 0.5f
        }

        onDispose {
            snapshots.forEach { snapshot ->
                snapshot.window.navigationBarColor = snapshot.color
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && snapshot.contrastEnforced != null) {
                    snapshot.window.isNavigationBarContrastEnforced = snapshot.contrastEnforced
                }
                WindowInsetsControllerCompat(snapshot.window, snapshot.window.decorView)
                    .isAppearanceLightNavigationBars = snapshot.lightIcons
            }
        }
    }
}

private data class ActionNavBarSnapshot(
    val window: android.view.Window,
    val color: Int,
    val contrastEnforced: Boolean?,
    val lightIcons: Boolean,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
internal fun TintedSheet(
    palette: ArtworkPalette,
    imageUrl: String?,
    flatPageBackground: Boolean = false,
    handleTranslationPx: Float = 0f,
    onHandleDragStart: () -> Unit = {},
    onHandleDragDelta: (Float) -> Unit = {},
    onHandleDragStop: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    backdropMode: Boolean = false,
    compactToContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isPhoneLandscape = !isTablet && isLandscape
    // Standard action sheets remain fixed-height; compact collection menus can wrap
    // their contents up to the same height cap. Phone landscape gives the hero
    // half of the short axis; tablets keep the previously approved 54% height.
    val fixedSheetHeight = (
        configuration.screenHeightDp * when {
            isPhoneLandscape -> 0.50f
            isLandscape -> 0.54f
            else -> 0.52f
        }
    ).dp
    // Outer modal dragging is disabled at the host. Internal verticalScroll now
    // handles all deltas, without a parent nested-scroll consumer competing with
    // clicks or scroll gestures when the list reaches either edge.

    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (compactToContent) Modifier.heightIn(max = fixedSheetHeight)
                else Modifier.height(fixedSheetHeight)
            )
            .graphicsLayer { translationY = handleTranslationPx }
            .orbSheetPanelElevation(SHEET_SHAPE)
            .clip(SHEET_SHAPE),
    ) {
        // Do not paint an opaque palette/artwork layer here. The dialog window
        // already blurs the real page underneath; covering it with ArtworkBackdrop
        // turned the sheet into a flat pink/grey panel. The shared Orb glass tint
        // now sits directly over that live blurred backdrop, matching the glass
        // used by detail-page controls and the share-card frame.
        Box(
            Modifier
                .matchParentSize()
                .orbGlassSurface(
                    pageColor = palette.background,
                    cornerRadius = 36.dp,
                    backdropBlur = if (backdropMode) 92.dp else 56.dp,
                    overlayStrength = true,
                    overlayOpacity = ACTION_SHEET_GLASS_OPACITY,
                    lightweightSheetMaterial = true,
                    sheetTintMode = if (backdropMode) OrbSheetTintMode.BACKDROP else OrbSheetTintMode.ARTWORK,
                    // The panel must preserve the same foreground chosen for the
                    // floating title even when the neutral glass tone differs from
                    // the artwork behind it.
                    contentColorHint = palette.onBackground,
                    // Individual rows provide action-specific feedback. A passive
                    // whole-sheet tap observer can race/debounce those pulses.
                    observeTapFeedback = false,
                ),
        )
        Column(if (compactToContent) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            // Drawn rather than taken from BottomSheetDefaults, whose handle
            // carries 22dp of padding on each side — half a row's worth of
            // nothing between the grip and the track it is about.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = rememberDraggableState { delta -> onHandleDragDelta(delta) },
                        orientation = Orientation.Vertical,
                        onDragStarted = { onHandleDragStart() },
                        onDragStopped = { velocity -> onHandleDragStop(velocity) },
                    )
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
            val motionViewport = rememberOrbSheetMotionViewport(actionScroll)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (compactToContent) {
                            // Natural height for short menus; long labels and small
                            // landscape windows still scroll within the usual limit.
                            Modifier.heightIn(max = fixedSheetHeight - 16.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                    )
                    .orbSheetMotionViewport(motionViewport)
                    .verticalScroll(actionScroll),
            ) {
                OrbSheetMotionContent(motionViewport) { content() }
            }
            // Inset is part of the same glass Box and scroll viewport is above it.
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
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
            accent = palette.onBackground,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Resolving -> ActionRow(
            icon = Icons.Rounded.Downloading,
            label = stringResource(R.string.song_action_resolving_download_quality),
            value = stringResource(R.string.song_action_cancel),
            tint = palette.onBackground,
            accent = palette.onBackground,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Running -> ActionRow(
            icon = Icons.Rounded.Downloading,
            label = stringResource(R.string.song_action_downloading),
            value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null,
            tint = palette.onBackground,
            accent = palette.onBackground,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Failed -> ActionRow(
            icon = Icons.Rounded.ErrorOutline,
            // Never expose exception messages (often in English) as action labels.
            label = stringResource(R.string.song_action_download),
            value = stringResource(R.string.song_action_retry),
            tint = palette.onBackground,
            accent = palette.onBackground,
            onClick = onDownload,
        )

        null -> if (file != null) {
            ActionRow(
                icon = Icons.Rounded.DownloadDone,
                label = stringResource(R.string.song_action_saved_to_downloads),
                trailingIcon = Icons.Rounded.Delete,
                trailingContentDescription = stringResource(R.string.song_action_remove_download),
                tint = palette.onBackground,
                accent = palette.onBackground,
            ) {
                scope.launch {
                    if (Downloads.delete(context, song)) onDownloadRemoved?.invoke()
                }
            }
        } else if (!isOffline) {
            ActionRow(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.song_action_download),
                accent = palette.onBackground,
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
        SleepOption(label = stringResource(R.string.song_action_after_this_song), selected = afterTrack, accent = palette.onBackground) {
            SleepTimer.startAfterTrack()
            onBack()
        }
        SleepTimer.PRESETS.forEach { minutes ->
            SleepOption(
                label = if (minutes == 60) stringResource(R.string.song_action_one_hour) else stringResource(R.string.song_action_minutes, minutes),
                selected = minutes == chosen,
                accent = palette.onBackground,
            ) {
                SleepTimer.start(minutes)
                onBack()
            }
        }
        if (chosen != null || afterTrack) {
            ActionRow(Icons.Rounded.Close, stringResource(R.string.song_action_turn_off_timer), accent = palette.onBackground) {
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
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .orbSheetProgressiveItem()
            .clickable {
                OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                onClick()
            }
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
    trailingIcon: ImageVector? = null,
    trailingContentDescription: String? = null,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.onBackground,
    enabled: Boolean = true,
    hapticKind: OrbHaptics.Kind = OrbHaptics.Kind.ACTION,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val contentAlpha = if (enabled) 1f else 0.48f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .orbSheetProgressiveItem()
            .clickable(enabled = enabled) {
                // All action-sheet rows provide the same immediate tactile acknowledgement.
                // OrbHaptics posts the vibrator work, so it never blocks this click callback.
                OrbHaptics.perform(context, hapticKind)
                onClick()
            }
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
        } else if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = trailingContentDescription,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp),
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
            .orbSheetProgressiveItem()
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
                badgePageColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                badgeContentColor = MaterialTheme.colorScheme.onBackground,
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
