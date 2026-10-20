package com.music.orb.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.music.orb.data.model.Song
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBars
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.DetailPage

/** The collection counterpart of the song action sheet, using the same glass rows and sheet host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionActionsSheet(
    page: DetailPage,
    pageSurfaceColor: Color,
    pagePrimaryTextColor: Color,
    pageSecondaryTextColor: Color,
    savedInLibrary: Boolean,
    libraryActionAvailable: Boolean,
    ratingAvailable: Boolean,
    artistAvailable: Boolean,
    onRate: () -> Unit,
    onDownload: () -> Unit,
    onToggleLibrary: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenArtist: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    // Library shelves use this exact component/hero/motion too. These
    // optional rows are library-specific affordances, not alternate sheets.
    onOpen: (() -> Unit)? = null,
    onManagePlaylist: (() -> Unit)? = null,
) {
    RegisterSheetBackdrop()
    // Reuse the song sheet's physical host, gesture model, material surface and
    // EXACT same ActionSheetAlbumHero for symmetrical entry/exit paths.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val phoneLandscape = isLandscape && !isTablet
    val sheetFraction = if (phoneLandscape) .50f else if (isLandscape) .54f else .52f
    val thresholdPx = with(density) { 72.dp.toPx() }
    val travelPx = with(density) { (configuration.screenHeightDp * sheetFraction).dp.toPx() }
    var entered by remember(page.browseId) { mutableStateOf(false) }
    var sheetOpened by remember(page.browseId) { mutableStateOf(false) }
    var closing by remember(page.browseId) { mutableStateOf(false) }
    var panelLeaving by remember(page.browseId) { mutableStateOf(false) }
    var handleDragging by remember(page.browseId) { mutableStateOf(false) }
    var handleDismissCommitted by remember(page.browseId) { mutableStateOf(false) }
    var handleDragTargetPx by remember(page.browseId) { mutableFloatStateOf(0f) }
    var sheetTopPx by remember(page.browseId) { mutableFloatStateOf(0f) }
    var deferredAction by remember(page.browseId) { mutableStateOf<(() -> Unit)?>(null) }
    val handleDragVisualPx by androidx.compose.animation.core.animateFloatAsState(
        targetValue = handleDragTargetPx,
        animationSpec = if (handleDragging) androidx.compose.animation.core.snap() else
            androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 430f),
        label = "collectionHandleDrag",
    )
    val handleDismissProgress = (handleDragVisualPx / thresholdPx).coerceIn(0f, 1f)
    LaunchedEffect(page.browseId) { entered = true }
    // Entry keeps the existing 20dp reveal. Do not reverse it on dismissal:
    // that was fading the whole sheet rather than moving it off screen.
    val sheetMotion by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = .88f, stiffness = 290f),
        finishedListener = { value -> sheetOpened = value == 1f && !closing },
        label = "collectionSheetEntrance",
    )
    // When the CD has retracted and the sleeve has moved behind the surface,
    // the PANEL physically descends. The glass stays opaque for its entire exit.
    val panelExitPx by animateFloatAsState(
        targetValue = if (panelLeaving) travelPx else 0f,
        animationSpec = tween(durationMillis = 390, easing = FastOutSlowInEasing),
        label = "collectionPanelSlideOut",
    )
    LaunchedEffect(closing) {
        if (closing) {
            // ActionSheetAlbumHero: disc retracts (300ms), then sleeve hides
            // behind the sheet (340ms). Keep the panel still until this ends.
            delay(640L)
            // Drag-dismiss already animates the panel to the bottom via the
            // shared TintedSheet handle, so don't translate it a second time.
            if (!handleDismissCommitted) panelLeaving = true
            delay(430L)
            onDismiss()
            deferredAction?.invoke()
        }
    }
    val requestClose: ((() -> Unit)?) -> Unit = { action ->
        if (!closing) {
            deferredAction = action
            closing = true
        }
    }
    val heroSong = remember(page.browseId, page.title, page.subtitle, page.thumbnailUrl) {
        Song(videoId = page.browseId, title = page.title, artist = page.subtitle,
            thumbnailUrl = page.thumbnailUrl, albumName = page.title, isExplicit = page.isExplicit)
    }
    val glassPalette = com.music.orb.ui.theme.rememberArtworkPalette(page.thumbnailUrl).copy(
        background = pageSurfaceColor,
        onBackground = pagePrimaryTextColor,
        onBackgroundVariant = pageSecondaryTextColor,
        accent = pagePrimaryTextColor,
    )
    OrbFixedSheetHost(containerColor = Color.Transparent) {
        OrbModalBackHandler { requestClose(null) }
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                onBackground = pagePrimaryTextColor,
                onSurface = pagePrimaryTextColor,
                onSurfaceVariant = pageSecondaryTextColor,
                primary = pagePrimaryTextColor,
            ),
        ) {
            ActionSheetAlbumHero(
                song = heroSong,
                foreground = pagePrimaryTextColor,
                badgePageColor = pageSurfaceColor,
                closing = closing,
                gestureDismissProgress = handleDismissProgress,
                sheetTopPx = sheetTopPx,
                openingReady = sheetOpened,
                handleTravelProgress = ((handleDragVisualPx - thresholdPx) /
                    (travelPx - thresholdPx).coerceAtLeast(1f)).coerceIn(0f, 1f),
                exitThroughBottom = handleDismissCommitted,
                sheetDragOffsetPx = handleDragVisualPx,
            )
            TintedSheet(
                palette = glassPalette,
                imageUrl = page.thumbnailUrl,
                backdropMode = true,
                handleTranslationPx = handleDragVisualPx + panelExitPx,
                compactToContent = page.type == BrowseType.PLAYLIST,
                onHandleDragStart = { handleDragging = true },
                onHandleDragDelta = { delta ->
                    handleDragTargetPx = (handleDragTargetPx + delta).coerceIn(0f, travelPx)
                },
                onHandleDragStop = { velocity ->
                    handleDragging = false
                    if (handleDragTargetPx >= thresholdPx || velocity > 1_150f) {
                        handleDismissCommitted = true
                        handleDragTargetPx = travelPx
                        requestClose(null)
                    } else {
                        handleDragTargetPx = 0f
                    }
                },
                modifier = Modifier
                    .offset {
                        val revealPx = with(density) { 20.dp.toPx() }
                        androidx.compose.ui.unit.IntOffset(0, ((1f - sheetMotion) * revealPx).toInt())
                    }
                    // Preserve the entrance fade; close by translation, never fade.
                    .graphicsLayer { alpha = .88f + .12f * sheetMotion }
                    .onGloballyPositioned { sheetTopPx = it.boundsInWindow().top },
            ) {
            if (onOpen != null) {
                ActionRow(
                    icon = Icons.Rounded.ChevronRight,
                    label = stringResource(R.string.library_collection_open),
                    onClick = { requestClose(onOpen) },
                )
            }
            if (page.type == BrowseType.ALBUM) {
                ActionRow(
                    icon = Icons.Rounded.Star,
                    label = stringResource(R.string.album_rate),
                    enabled = ratingAvailable,
                    onClick = { requestClose(onRate) },
                )
            }
            ActionRow(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.collection_actions_download),
                onClick = { requestClose(onDownload) },
            )
            ActionRow(
                icon = if (savedInLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
                label = stringResource(
                    if (savedInLibrary) R.string.song_action_remove_from_library
                    else R.string.song_action_add_to_library,
                ),
                enabled = libraryActionAvailable,
                onClick = { requestClose(onToggleLibrary) },
            )
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                label = stringResource(R.string.collection_actions_play_next),
                onClick = { requestClose(onPlayNext) },
            )
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = stringResource(R.string.collection_actions_add_to_queue),
                onClick = { requestClose(onAddToQueue) },
            )
            if (page.type == BrowseType.ALBUM) {
                ActionRow(
                    icon = Icons.Rounded.Person,
                    label = stringResource(R.string.collection_actions_open_artist),
                    enabled = artistAvailable,
                    onClick = { requestClose(onOpenArtist) },
                )
            }
            if (onManagePlaylist != null) {
                ActionRow(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.library_collection_manage_playlist),
                    onClick = { requestClose(onManagePlaylist) },
                )
            }
            ActionRow(
                icon = Icons.Rounded.Share,
                label = stringResource(R.string.collection_action_share),
                onClick = { requestClose(onShare) },
            )

            }
        }
    }
}
