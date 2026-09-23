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
    content: @Composable () -> Unit,
) {
    val progress = remember(itemKey) { Animatable(0f) }
    var enteredViewport by remember(itemKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    val view = LocalView.current
    val travelPx = with(density) { 42.dp.toPx() }

    LaunchedEffect(enteredViewport, itemKey) {
        if (!enteredViewport || progress.value >= 1f) return@LaunchedEffect

        // The first rows cascade exactly like the ranking. Rows that were below
        // the fold wait for scrolling, then enter promptly instead of inheriting
        // a large delay just because their absolute menu index is high.
        val delayMs = if (index <= 5) index * 72L else 36L
        delay(delayMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = 260f,
                visibilityThreshold = 0.001f,
            ),
        )
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
            .graphicsLayer {
                alpha = p
                translationY = travelPx * (1f - p)
                val scale = 0.94f + (0.06f * p)
                scaleX = scale
                scaleY = scale
            },
    ) {
        content()
    }
}
