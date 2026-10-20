package com.music.orb.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Progressive entrance used by every Orb action sheet.
 *
 * This deliberately mirrors the artist-ranking motion language: items already
 * visible when the sheet opens arrive top-to-bottom, while items below the
 * viewport stay dormant until the user scrolls them into view. Once revealed,
 * an item stays settled so scrolling back up never replays the entrance.
 */
@Composable
internal fun ProgressiveActionSheetItem(
    index: Int,
    itemKey: Any,
    modifier: Modifier = Modifier,
    initiallyRevealed: Boolean = false,
    onRevealed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val progress = remember(itemKey) { Animatable(if (initiallyRevealed) 1f else 0f) }
    var enteredViewport by remember(itemKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    val view = LocalView.current
    val travelPx = with(density) { 8.dp.toPx() }

    LaunchedEffect(enteredViewport, itemKey) {
        if (!enteredViewport || progress.value >= 1f) return@LaunchedEffect

        // The first rows cascade exactly like the ranking. Rows that were below
        // the fold wait for scrolling, then enter promptly instead of inheriting
        // a large delay just because their absolute menu index is high.
        val delayMs = if (index <= 5) index * 12L else 0L
        delay(delayMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = 560f,
                visibilityThreshold = 0.001f,
            ),
        )
        onRevealed()
    }

    val p = progress.value.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                if (enteredViewport) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                val rootHeight = view.height.toFloat()
                if (rootHeight > 0f && bounds.bottom > 0f && bounds.top < rootHeight) {
                    enteredViewport = true
                }
            }
            // Keep the actual hit target at its final position from the first frame.
            // Only the pixels move a few dp during the cascade, so an option can be
            // tapped immediately while the visual entrance is still settling.
            .orbSheetProgressiveItem()
            .graphicsLayer {
                alpha = p
                translationY = travelPx * (1f - p)
            },
    ) {
        content()
    }
}
