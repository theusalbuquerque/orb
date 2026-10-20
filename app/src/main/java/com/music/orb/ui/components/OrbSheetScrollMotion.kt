package com.music.orb.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Visual-only transform: never installs pointerInput or nestedScroll and never
 * consumes vertical gestures. Scrolling remains owned by the child list.
 *
 * The viewport wrapper belongs to the actual scrolling box, not the fixed
 * sheet handle, title, navigation bar, or blurred backdrop.
 */
@Stable
class OrbSheetMotionViewport internal constructor(val scroll: ScrollState) {
    var topPx by mutableFloatStateOf(Float.NaN)
    var bottomPx by mutableFloatStateOf(Float.NaN)
}

private val LocalOrbSheetMotionViewport = compositionLocalOf<OrbSheetMotionViewport?> { null }

@Composable
fun rememberOrbSheetMotionViewport(scroll: ScrollState): OrbSheetMotionViewport =
    remember(scroll) { OrbSheetMotionViewport(scroll) }

@Composable
fun OrbSheetMotionContent(
    viewport: OrbSheetMotionViewport,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOrbSheetMotionViewport provides viewport, content = content)
}

fun Modifier.orbSheetMotionViewport(viewport: OrbSheetMotionViewport): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        if (viewport.topPx != bounds.top) viewport.topPx = bounds.top
        if (viewport.bottomPx != bounds.bottom) viewport.bottomPx = bounds.bottom
    }

/**
 * Non-lazy sheet row. The outgoing row shrinks/fades over the top 88dp of the
 * viewport; scrolling back restores its full size. Rows are fully opaque when
 * the list is first opened, and nothing animates outside a scrollable viewport.
 */
fun Modifier.orbSheetProgressiveItem(): Modifier = composed {
    val viewport = LocalOrbSheetMotionViewport.current
    if (viewport == null) return@composed this
    val density = LocalDensity.current
    val edgePx = with(density) { 84.dp.toPx() }
    var itemTop by remember { mutableFloatStateOf(Float.NaN) }
    var itemBottom by remember { mutableFloatStateOf(Float.NaN) }
    this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        if (itemTop != bounds.top) itemTop = bounds.top
        if (itemBottom != bounds.bottom) itemBottom = bounds.bottom
    }.graphicsLayer {
        val factor = if (viewport.scroll.value <= 0 ||
            viewport.topPx.isNaN() || itemBottom.isNaN() || itemTop.isNaN()
        ) 1f else {
            val top = ((itemBottom - viewport.topPx) / edgePx).coerceIn(0f, 1f)
            val bottom = ((viewport.bottomPx - itemTop) / edgePx).coerceIn(0f, 1f)
            minOf(top, bottom)
        }
        alpha = factor
        scaleX = 0.93f + 0.07f * factor
        scaleY = 0.93f + 0.07f * factor
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

/** LazyColumn equivalent with reversible fading at both edges. */
fun Modifier.orbSheetProgressiveLazyItem(state: LazyListState, index: Int): Modifier = composed {
    val density = LocalDensity.current
    val edgePx = with(density) { 84.dp.toPx() }
    this.graphicsLayer {
        val scrolled = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val fraction = if (!scrolled || info == null) 1f else {
            val top = ((info.offset + info.size - state.layoutInfo.viewportStartOffset) / edgePx).coerceIn(0f, 1f)
            val bottom = ((state.layoutInfo.viewportEndOffset - info.offset) / edgePx).coerceIn(0f, 1f)
            minOf(top, bottom)
        }
        alpha = fraction
        scaleX = 0.93f + 0.07f * fraction
        scaleY = 0.93f + 0.07f * fraction
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

/** Grid equivalent used by queue/playlist sheets with tiled artwork. */
fun Modifier.orbSheetProgressiveGridItem(state: LazyGridState, index: Int): Modifier = composed {
    val density = LocalDensity.current
    val edgePx = with(density) { 88.dp.toPx() }
    this.graphicsLayer {
        val scrolled = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val fraction = if (!scrolled || info == null) 1f else {
            val top = ((info.offset.y + info.size.height - state.layoutInfo.viewportStartOffset) / edgePx).coerceIn(0f, 1f)
            val bottom = ((state.layoutInfo.viewportEndOffset - info.offset.y) / edgePx).coerceIn(0f, 1f)
            minOf(top, bottom)
        }
        alpha = fraction
        scaleX = 0.92f + 0.08f * fraction
        scaleY = 0.92f + 0.08f * fraction
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}
