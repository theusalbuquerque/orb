package com.music.orb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.AppSettings
import com.music.orb.ui.flavor.OrbFlavorUi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlin.math.abs

private val GLYPH_SLOT = 40.dp
private val ROW_PADDING = 5.dp
private val ART_CORNER = 7.dp
private val BAR_CORNER = ART_CORNER + ROW_PADDING

/** Frosted mini player that rides just above the floating tab bar. */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onClearQueue: () -> Unit,
    controlsEnabled: Boolean = true,
    controlsVisible: Boolean = true,
    onBoundsChanged: (Rect) -> Unit = {},
    onArtworkBoundsChanged: (Rect) -> Unit = {},
    onIdentityBoundsChanged: (Rect) -> Unit = {},
    onPlayPauseBoundsChanged: (Rect) -> Unit = {},
    onNextBoundsChanged: (Rect) -> Unit = {},
    artworkVisible: Boolean = true,
    contentVisible: Boolean = true,
    /** Lock screen uses this very same layout without a second album thumbnail. */
    includeArtwork: Boolean = true,
    /** Solid artwork-derived tint; null keeps the app's frosted glass material. */
    solidBackgroundColor: Color? = null,
    solidContentColor: Color? = null,
    enableSwipeToClear: Boolean = true,
    /** Reuses the same expressive glass material as Now Playing's bottom actions. */
    lockscreenGlass: Boolean = false,
    /** In OLED saver mode draw a white contour over a transparent transport surface. */
    lockscreenOutlinedControls: Boolean = false,
    /** Artwork-derived fill is limited to the play/pause button when locked. */
    playPauseBackgroundColor: Color? = null,
    playPauseForegroundColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    // MiniPlayer dismissal is intentionally strict about direction. A vertical
    // page scroll must never become a destructive sideways clear just because
    // the finger wobbled a few pixels horizontally.
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffsetTarget by remember(song.videoId) { mutableFloatStateOf(0f) }
    var horizontalDragging by remember(song.videoId) { mutableStateOf(false) }
    var skipRequested by remember { mutableStateOf(false) }
    // Only the user's Skip Next gesture animates the artwork/metadata sideways.
    val animatedNext = {
        skipRequested = true
        onNext()
    }
    LaunchedEffect(song.videoId) {
        if (skipRequested) { delay(460L); skipRequested = false }
    }
    LaunchedEffect(skipRequested) {
        if (skipRequested) { delay(5_000L); skipRequested = false }
    }
    val swipeOffsetPx by animateFloatAsState(
        targetValue = swipeOffsetTarget,
        animationSpec = if (horizontalDragging) snap() else spring(dampingRatio = 0.82f, stiffness = 500f),
        label = "miniPlayerSwipeOffset",
    )

    Box(
        modifier = modifier
            .onSizeChanged { boxWidth = it.width.toFloat() }
            .then(if (enableSwipeToClear) Modifier.pointerInput(song.videoId, boxWidth) {
                detectOrbHorizontalDragGestures(
                    horizontalDominance = 1.45f,
                    onDragStart = { horizontalDragging = true },
                    onHorizontalDrag = { deltaX ->
                        if (boxWidth > 0f) {
                            swipeOffsetTarget = (swipeOffsetTarget + deltaX)
                                .coerceIn(-boxWidth * 0.62f, boxWidth * 0.62f)
                        }
                    },
                    onDragEnd = {
                        horizontalDragging = false
                        if (boxWidth > 0f && abs(swipeOffsetTarget) >= boxWidth * 0.45f) {
                            onClearQueue()
                        } else {
                            swipeOffsetTarget = 0f
                        }
                    },
                    onDragCancel = {
                        horizontalDragging = false
                        swipeOffsetTarget = 0f
                    },
                )
            } else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = if (enableSwipeToClear) swipeOffsetPx else 0f }) {
        if (OrbFlavorUi.expressive) {
            ExpressiveMiniPlayer(
                song = song,
                isPlaying = isPlaying,
                isLoading = isLoading,
                hazeState = hazeState,
                onPlayPause = onPlayPause,
                onNext = animatedNext,
                onExpand = onExpand,
                controlsEnabled = controlsEnabled,
                controlsVisible = controlsVisible,
                onBoundsChanged = onBoundsChanged,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
                onIdentityBoundsChanged = onIdentityBoundsChanged,
                onPlayPauseBoundsChanged = onPlayPauseBoundsChanged,
                onNextBoundsChanged = onNextBoundsChanged,
                artworkVisible = artworkVisible,
                contentVisible = contentVisible,
                includeArtwork = includeArtwork,
                solidBackgroundColor = solidBackgroundColor,
                solidContentColor = solidContentColor,
                lockscreenGlass = lockscreenGlass,
                lockscreenOutlinedControls = lockscreenOutlinedControls,
                playPauseBackgroundColor = playPauseBackgroundColor,
                playPauseForegroundColor = playPauseForegroundColor,
                animateSongChange = skipRequested,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ClassicMiniPlayer(
                song = song,
                isPlaying = isPlaying,
                isLoading = isLoading,
                hazeState = hazeState,
                onPlayPause = onPlayPause,
                onNext = animatedNext,
                onExpand = onExpand,
                controlsEnabled = controlsEnabled,
                controlsVisible = controlsVisible,
                onBoundsChanged = onBoundsChanged,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
                onIdentityBoundsChanged = onIdentityBoundsChanged,
                onPlayPauseBoundsChanged = onPlayPauseBoundsChanged,
                onNextBoundsChanged = onNextBoundsChanged,
                artworkVisible = artworkVisible,
                contentVisible = contentVisible,
                includeArtwork = includeArtwork,
                solidBackgroundColor = solidBackgroundColor,
                solidContentColor = solidContentColor,
                lockscreenGlass = lockscreenGlass,
                lockscreenOutlinedControls = lockscreenOutlinedControls,
                playPauseBackgroundColor = playPauseBackgroundColor,
                playPauseForegroundColor = playPauseForegroundColor,
                animateSongChange = skipRequested,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        }
    }
}

/**
 * The album thumbnail rotates as a two-sided card, only on a user-requested Skip Next.
 * It keeps its layout slot fixed while the next artwork is revealed at the edge-on
 * midpoint. Automatic changes remain immediate and the text retains its own slide.
 */
@Composable
private fun FlippingMiniPlayerArtwork(
    song: Song,
    artSize: Dp,
    artShape: Shape,
    artworkVisible: Boolean,
    animateSongChange: Boolean,
    onArtworkBoundsChanged: (Rect) -> Unit,
) {
    var displayedArtworkSong by remember { mutableStateOf(song) }
    // Keep the last successfully decoded image until the new one is drawable.
    // A new track can arrive before its CDN cover is cached (especially after a skip).
    var readyArtworkSong by remember { mutableStateOf(song) }
    val context = LocalContext.current
    val flip = remember { Animatable(0f) }

    LaunchedEffect(song.videoId, song.artworkAt(ROW_ART_PX)) {
        song.artworkAt(ROW_ART_PX)?.let { url ->
            SingletonImageLoader.get(context).enqueue(
                ImageRequest.Builder(context).data(url).build()
            )
        }
        if (animateSongChange && displayedArtworkSong.videoId != song.videoId) {
            // A continuous 0 -> 180 degree turn. Swap at 90 degrees, where
            // the thumbnail is edge-on, then draw the incoming face forwards.
            flip.snapTo(0f)
            flip.animateTo(
                targetValue = 180f,
                animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing),
            ) {
                if (value >= 90f && displayedArtworkSong.videoId != song.videoId) {
                    displayedArtworkSong = song
                }
            }
        }
        displayedArtworkSong = song
        flip.snapTo(0f)
    }

    val coverReady = displayedArtworkSong.artworkAt(ROW_ART_PX) ==
        readyArtworkSong.artworkAt(ROW_ART_PX)
    Box(
        modifier = Modifier
            .size(artSize)
            .onGloballyPositioned { onArtworkBoundsChanged(it.boundsInRoot()) }
            .graphicsLayer {
                rotationY = if (flip.value <= 90f) flip.value else flip.value - 180f
                cameraDistance = 12f * density
                alpha = if (artworkVisible) 1f else 0f
            }
            .clip(artShape)
            .thumbnailBorder(artShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!coverReady) {
            AsyncImage(
                model = readyArtworkSong.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = displayedArtworkSong.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (coverReady) 1f else 0f },
            onSuccess = { readyArtworkSong = displayedArtworkSong },
        )
    }
}

/**
 * Shared identity geometry in both app miniplayer variants and Orb Lock.
 * On Skip Next only the metadata slides horizontally; the artwork flips in its
 * own fixed slot, so the transport controls and mini-player chassis do not move.
 */
@Composable
private fun AnimatedMiniPlayerIdentity(
    song: Song,
    includeArtwork: Boolean,
    artworkVisible: Boolean,
    expressive: Boolean,
    animateSongChange: Boolean,
    titleColor: Color,
    artistColor: Color,
    badgeMaterialColor: Color,
    onArtworkBoundsChanged: (Rect) -> Unit,
    onIdentityBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artSize = if (expressive) 50.dp else 40.dp
    val artShape = if (expressive) RoundedCornerShape(percent = MINI_ARTWORK_CORNER_PERCENT)
        else RoundedCornerShape(ART_CORNER)

    Row(modifier = modifier.height(artSize), verticalAlignment = Alignment.CenterVertically) {
        if (includeArtwork) {
            FlippingMiniPlayerArtwork(
                song = song,
                artSize = artSize,
                artShape = artShape,
                artworkVisible = artworkVisible,
                animateSongChange = animateSongChange,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
            )
            Spacer(Modifier.width(if (expressive) 12.dp else 10.dp))
        }
        AnimatedContent(
            targetState = song,
            contentKey = { it.videoId },
            transitionSpec = {
                if (animateSongChange) {
                    (slideInHorizontally(animationSpec = tween(320)) { full -> full / 2 } +
                        fadeIn(animationSpec = tween(260))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(320)) { full -> -full / 2 } +
                            fadeOut(animationSpec = tween(230)))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            modifier = Modifier.weight(1f)
                .onGloballyPositioned { onIdentityBoundsChanged(it.boundsInRoot()) },
            label = "miniPlayerSkipMetadata",
        ) { displayed ->
            Column {
                ExplicitTitle(
                    text = displayed.title,
                    isExplicit = displayed.isExplicit,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    keepBadgeVisible = true,
                    badgePageColor = badgeMaterialColor,
                )
                Text(
                    text = displayed.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = artistColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ExpressiveMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    controlsEnabled: Boolean,
    controlsVisible: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onArtworkBoundsChanged: (Rect) -> Unit,
    onIdentityBoundsChanged: (Rect) -> Unit,
    onPlayPauseBoundsChanged: (Rect) -> Unit,
    onNextBoundsChanged: (Rect) -> Unit,
    artworkVisible: Boolean,
    contentVisible: Boolean,
    includeArtwork: Boolean,
    solidBackgroundColor: Color?,
    solidContentColor: Color?,
    lockscreenGlass: Boolean,
    lockscreenOutlinedControls: Boolean,
    playPauseBackgroundColor: Color?,
    playPauseForegroundColor: Color?,
    animateSongChange: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(26.dp)
    val container = solidBackgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val titleColor = solidContentColor ?: MaterialTheme.colorScheme.onSurface
    val secondaryColor = solidContentColor?.copy(alpha = 0.80f) ?: MaterialTheme.colorScheme.onSurfaceVariant
    val badgeMaterialColor = solidBackgroundColor ?: MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clip(shape)
            .then(
                if (lockscreenGlass) {
                    Modifier.orbGlassSurface(
                        pageColor = container,
                        cornerRadius = 26.dp,
                        drawRim = false,
                        overlayOpacity = 0.24f,
                        backdropBlur = 56.dp,
                        manageDialogBackdrop = false,
                        observeTapFeedback = false,
                    )
                } else if (solidBackgroundColor != null || reduceDynamicBlur) {
                    Modifier.background(container)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(container),
                    )
                },
            )
            .then(if (lockscreenGlass) Modifier else Modifier.border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape,
            ))
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer { alpha = if (contentVisible) 1f else 0f }
                .fillMaxWidth()
                .padding(horizontal = if (lockscreenGlass) 16.dp else 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedMiniPlayerIdentity(
                song = song,
                includeArtwork = includeArtwork,
                artworkVisible = artworkVisible,
                expressive = true,
                animateSongChange = animateSongChange,
                titleColor = titleColor,
                artistColor = secondaryColor,
                badgeMaterialColor = badgeMaterialColor,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
                onIdentityBoundsChanged = onIdentityBoundsChanged,
                modifier = Modifier.weight(1f),
            )
            if (controlsVisible) {
            Spacer(Modifier.width(10.dp))
            ExpressiveMiniTransportCluster(
                isPlaying = isPlaying,
                isLoading = isLoading,
                enabled = controlsEnabled || com.music.orb.data.rooms.RoomRepository.isGuest(),
                onPlayPause = onPlayPause,
                onNext = onNext,
                playPauseBackgroundColor = playPauseBackgroundColor,
                playPauseForegroundColor = playPauseForegroundColor,
                lockscreenGlass = lockscreenGlass,
                lockscreenOutlinedControls = lockscreenOutlinedControls,
                onPlayPauseBoundsChanged = onPlayPauseBoundsChanged,
                onNextBoundsChanged = onNextBoundsChanged,
            )
            }
        }
    }
}

@Composable
private fun ExpressiveMiniTransportCluster(
    isPlaying: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    playPauseBackgroundColor: Color?,
    playPauseForegroundColor: Color?,
    lockscreenGlass: Boolean,
    lockscreenOutlinedControls: Boolean,
    onPlayPauseBoundsChanged: (Rect) -> Unit,
    onNextBoundsChanged: (Rect) -> Unit,
) {
    val slotSize = 42.dp
    val playPauseCorner by animateDpAsState(
        targetValue = if (isPlaying) 10.dp else slotSize / 2f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "miniPlayPauseCorner",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniTransportSlot(
            modifier = Modifier.size(slotSize)
                .onGloballyPositioned { onPlayPauseBoundsChanged(it.boundsInRoot()) },
            selected = true,
            enabled = enabled,
            cornerRadius = playPauseCorner,
            onClick = com.music.orb.ui.components.OrbHaptics.transportClick(onPlayPause),
        ) {
            PlayerTransportVisual(
                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                slotSize = slotSize,
                selected = true,
                isLoading = isLoading,
                containerColor = when {
                    lockscreenOutlinedControls -> Color.Transparent
                    lockscreenGlass -> playPauseBackgroundColor?.copy(alpha = 0.12f)
                    else -> playPauseBackgroundColor
                },
                foregroundColor = if (lockscreenOutlinedControls) Color.White else if (lockscreenGlass) playPauseBackgroundColor else playPauseForegroundColor,
                solidContainerColor = lockscreenOutlinedControls || playPauseBackgroundColor != null,
                containerCornerRadius = playPauseCorner,
                outlineColor = if (lockscreenOutlinedControls) Color.White else if (lockscreenGlass) playPauseBackgroundColor else null,
            )
        }
        MiniTransportSlot(
            modifier = Modifier.size(slotSize)
                .onGloballyPositioned { onNextBoundsChanged(it.boundsInRoot()) },
            selected = false,
            enabled = enabled && !com.music.orb.data.rooms.RoomRepository.isGuest(),
            onClick = com.music.orb.ui.components.OrbHaptics.transportClick(onNext),
        ) {
            PlayerTransportVisual(
                icon = Icons.Rounded.SkipNext,
                slotSize = slotSize,
                selected = false,
                // The skip icon gets the accent, never an outline.
                foregroundColor = if (lockscreenOutlinedControls) Color.White else if (lockscreenGlass) playPauseBackgroundColor else null,
            )
        }
    }
}

/** Geometry and typography captured at the real expanded endpoint. */
data class PlayerIdentityAppearance(
    val titleStyle: TextStyle,
    val artistStyle: TextStyle,
    val color: Color,
    val transportColor: Color,
    val transportContainerColor: Color,
)

/** Shared shape, without another outer mask that could turn the compact button into a circle. */
val PlayerPlayPauseShape = RoundedCornerShape(percent = 22)
const val MINI_ARTWORK_CORNER_PERCENT = 34

/** A single visual definition for the MiniPlayer, Now Playing and motion bridge. */
@Composable
fun PlayerTransportVisual(
    icon: ImageVector,
    slotSize: Dp,
    selected: Boolean,
    isLoading: Boolean = false,
    foregroundColor: Color? = null,
    containerColor: Color? = null,
    containerCornerRadius: Dp? = null,
    solidContainerColor: Boolean = false,
    drawContainer: Boolean = true,
    outlineColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val glassPageColor = if (selected) {
        containerColor ?: MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val tint = foregroundColor ?: if (selected) {
        if (containerColor == null) MaterialTheme.colorScheme.onPrimaryContainer
        else orbExpressiveGlassContentColor(glassPageColor)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerShape = containerCornerRadius?.let { RoundedCornerShape(it) } ?: PlayerPlayPauseShape
    Box(
        modifier = modifier
            .size(slotSize)
            .then(
                if (selected && drawContainer) {
                    Modifier
                        .clip(containerShape)
                        .then(
                            if (containerColor == null || solidContainerColor) Modifier.background(glassPageColor)
                            else Modifier.orbGlassSurface(
                                pageColor = glassPageColor,
                                cornerRadius = containerCornerRadius ?: (slotSize * 0.22f),
                                drawRim = false,
                                overlayStrength = true,
                                overlayOpacity = 0.50f,
                                observeTapFeedback = false,
                                manageDialogBackdrop = false,
                            ),
                        )
                        .then(if (outlineColor != null) Modifier.border(1.5.dp, outlineColor, containerShape) else Modifier)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = slotSize * (2.25f / 42f),
                modifier = Modifier.size(slotSize * (20f / 42f)),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(slotSize * (26f / 42f)),
            )
        }
    }
}

@Composable
private fun MiniTransportSlot(
    modifier: Modifier = Modifier,
    selected: Boolean,
    enabled: Boolean,
    cornerRadius: Dp? = null,
    onClick: () -> Unit,
    content: @Composable (selected: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && enabled) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "miniTransportSlotScale",
    )
    val slotShape = cornerRadius?.let { RoundedCornerShape(it) } ?: PlayerPlayPauseShape
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(slotShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!selected && pressed && enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .orbGlassSurface(
                        pageColor = MaterialTheme.colorScheme.primaryContainer,
                        cornerRadius = 21.dp,
                        drawRim = false,
                        overlayStrength = true,
                        overlayOpacity = 0.46f,
                        observeTapFeedback = false,
                        manageDialogBackdrop = false,
                    ),
            )
        }
        content(selected)
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ClassicMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    controlsEnabled: Boolean,
    controlsVisible: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onArtworkBoundsChanged: (Rect) -> Unit,
    onIdentityBoundsChanged: (Rect) -> Unit,
    onPlayPauseBoundsChanged: (Rect) -> Unit,
    onNextBoundsChanged: (Rect) -> Unit,
    artworkVisible: Boolean,
    contentVisible: Boolean,
    includeArtwork: Boolean,
    solidBackgroundColor: Color?,
    solidContentColor: Color?,
    lockscreenGlass: Boolean,
    lockscreenOutlinedControls: Boolean,
    playPauseBackgroundColor: Color?,
    playPauseForegroundColor: Color?,
    animateSongChange: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(BAR_CORNER)
    val transportTint = (solidContentColor ?: MaterialTheme.colorScheme.onBackground).copy(
        alpha = if (controlsEnabled) 1f else 0.38f,
    )
    Box(
        modifier = modifier
            .padding(horizontal = PAGE_GUTTER)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clip(shape)
            .then(
                if (lockscreenGlass) {
                    Modifier.orbGlassSurface(
                        pageColor = solidBackgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = BAR_CORNER,
                        drawRim = false,
                        overlayOpacity = 0.24f,
                        observeTapFeedback = false,
                        manageDialogBackdrop = false,
                    )
                } else if (solidBackgroundColor != null || reduceDynamicBlur) {
                    Modifier.background(solidBackgroundColor ?: MaterialTheme.colorScheme.surface)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                    )
                },
            )
            .then(if (lockscreenGlass) Modifier else Modifier.border(0.5.dp, Color.White.copy(alpha = 0.10f), shape))
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer { alpha = if (contentVisible) 1f else 0f }
                .fillMaxWidth()
                .padding(ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedMiniPlayerIdentity(
                song = song,
                includeArtwork = includeArtwork,
                artworkVisible = artworkVisible,
                expressive = false,
                animateSongChange = animateSongChange,
                titleColor = solidContentColor ?: MaterialTheme.colorScheme.onBackground,
                artistColor = solidContentColor?.copy(alpha = 0.80f) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                badgeMaterialColor = solidBackgroundColor ?: MaterialTheme.colorScheme.surface,
                onArtworkBoundsChanged = onArtworkBoundsChanged,
                onIdentityBoundsChanged = onIdentityBoundsChanged,
                modifier = Modifier.weight(1f),
            )
            if (controlsVisible) {
            if (isLoading) {
                Box(
                    Modifier
                        .size(GLYPH_SLOT)
                        .onGloballyPositioned { onPlayPauseBoundsChanged(it.boundsInRoot()) },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = solidContentColor ?: MaterialTheme.colorScheme.onBackground,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = com.music.orb.ui.components.OrbHaptics.transportClick(onPlayPause),
                    enabled = controlsEnabled || com.music.orb.data.rooms.RoomRepository.isGuest(),
                    modifier = Modifier
                        .size(GLYPH_SLOT)
                        .then(if (playPauseBackgroundColor != null) Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (lockscreenOutlinedControls) Color.Transparent else if (lockscreenGlass) playPauseBackgroundColor.copy(alpha = 0.12f) else playPauseBackgroundColor)
                            .then(if (lockscreenOutlinedControls) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(10.dp)) else if (lockscreenGlass) Modifier.border(1.5.dp, playPauseBackgroundColor, RoundedCornerShape(10.dp)) else Modifier)
                            else Modifier)
                        .onGloballyPositioned { onPlayPauseBoundsChanged(it.boundsInRoot()) },
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (lockscreenOutlinedControls) Color.White else if (lockscreenGlass) playPauseBackgroundColor ?: transportTint else playPauseForegroundColor ?: transportTint,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            IconButton(
                onClick = com.music.orb.ui.components.OrbHaptics.transportClick(onNext),
                enabled = controlsEnabled,
                modifier = Modifier
                    .size(GLYPH_SLOT)
                    .onGloballyPositioned { onNextBoundsChanged(it.boundsInRoot()) },
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = if (lockscreenOutlinedControls) Color.White else if (lockscreenGlass) playPauseBackgroundColor ?: transportTint else transportTint,
                    modifier = Modifier.size(28.dp),
                )
            }
            }
        }
    }
}
