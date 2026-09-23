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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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
    onBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A deliberate horizontal dismissal is the one destructive MiniPlayer
    // gesture: it clears the whole playback session, including the item that
    // is sounding. Keep a real distance threshold so a short sideways wobble
    // while tapping Play/Next can never erase the queue.
    val swipeStateHolder = remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var clearTriggered by remember(song.videoId) { mutableStateOf(false) }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !clearTriggered) {
                val offset = try {
                    swipeStateHolder.value?.requireOffset() ?: 0f
                } catch (_: Exception) {
                    0f
                }
                if (boxWidth > 0f && abs(offset) >= boxWidth * 0.45f) {
                    clearTriggered = true
                    onClearQueue()
                }
            }
            // If the queue survives for any reason, keep the MiniPlayer in its
            // normal position. In the normal path onClearQueue removes the
            // current item and this composable disappears immediately.
            false
        },
        positionalThreshold = { distance -> distance * 0.5f },
    )
    swipeStateHolder.value = swipeState

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.onSizeChanged { boxWidth = it.width.toFloat() },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {},
    ) {
        if (OrbFlavorUi.expressive) {
            ExpressiveMiniPlayer(
                song = song,
                isPlaying = isPlaying,
                isLoading = isLoading,
                hazeState = hazeState,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onExpand = onExpand,
                controlsEnabled = controlsEnabled,
                onBoundsChanged = onBoundsChanged,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ClassicMiniPlayer(
                song = song,
                isPlaying = isPlaying,
                isLoading = isLoading,
                hazeState = hazeState,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onExpand = onExpand,
                controlsEnabled = controlsEnabled,
                onBoundsChanged = onBoundsChanged,
                modifier = Modifier.fillMaxWidth(),
            )
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
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(26.dp)
    val container = MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clip(shape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(container)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(container),
                    )
                },
            )
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape,
            )
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .thumbnailBorder(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ExplicitTitle(
                    text = song.title,
                    isExplicit = song.isExplicit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    keepBadgeVisible = true,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            ExpressiveMiniTransportCluster(
                isPlaying = isPlaying,
                isLoading = isLoading,
                enabled = controlsEnabled,
                onPlayPause = onPlayPause,
                onNext = onNext,
            )
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
) {
    var activeSlot by remember { mutableStateOf(0) }
    val slotSize = 42.dp
    val slotGap = 8.dp

    LaunchedEffect(activeSlot) {
        if (activeSlot == 1) {
            delay(300)
            activeSlot = 0
        }
    }

    val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (activeSlot == 0) 0.dp else slotSize + slotGap,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "miniTransportIndicatorOffset",
    )
    val indicatorCorner by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (activeSlot == 0) 15.dp else 19.dp,
        animationSpec = spring(
            dampingRatio = 0.76f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "miniTransportIndicatorCorner",
    )
    val indicatorWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (activeSlot == 0) 42.dp else 39.dp,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "miniTransportIndicatorWidth",
    )
    val selectedContainer = MaterialTheme.colorScheme.primaryContainer
    val selectedTint = MaterialTheme.colorScheme.onPrimaryContainer
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier.size(width = slotSize * 2 + slotGap, height = slotSize),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(
                    x = indicatorOffset + (slotSize - indicatorWidth) / 2,
                )
                .size(width = indicatorWidth, height = slotSize)
                .clip(RoundedCornerShape(indicatorCorner))
                .background(selectedContainer),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(slotGap)) {
            MiniTransportSlot(
                modifier = Modifier.size(slotSize),
                selected = activeSlot == 0,
                enabled = enabled,
                onClick = {
                    activeSlot = 0
                    onPlayPause()
                },
            ) { selected ->
                if (isLoading) {
                    CircularProgressIndicator(
                        color = if (selected) selectedTint else idleTint,
                        strokeWidth = 2.25.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (selected) selectedTint else idleTint,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            MiniTransportSlot(
                modifier = Modifier.size(slotSize),
                selected = activeSlot == 1,
                enabled = enabled,
                onClick = {
                    activeSlot = 1
                    onNext()
                },
            ) { selected ->
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = if (selected) selectedTint else idleTint,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniTransportSlot(
    modifier: Modifier = Modifier,
    selected: Boolean,
    enabled: Boolean,
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
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(RoundedCornerShape(19.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
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
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(BAR_CORNER)
    val transportTint = MaterialTheme.colorScheme.onBackground.copy(
        alpha = if (controlsEnabled) 1f else 0.38f,
    )
    Box(
        modifier = modifier
            .padding(horizontal = PAGE_GUTTER)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clip(shape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                    )
                },
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(ART_CORNER))
                    .thumbnailBorder(RoundedCornerShape(ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ExplicitTitle(
                    text = song.title,
                    isExplicit = song.isExplicit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    keepBadgeVisible = true,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onBackground,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = onPlayPause,
                    enabled = controlsEnabled,
                    modifier = Modifier.size(GLYPH_SLOT),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = transportTint,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            IconButton(
                onClick = onNext,
                enabled = controlsEnabled,
                modifier = Modifier.size(GLYPH_SLOT),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = transportTint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
