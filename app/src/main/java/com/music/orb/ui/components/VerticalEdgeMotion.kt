package com.music.orb.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scroll geometry stays independent of the entrance animation's transforms. */
@Composable
internal fun verticalEdgeMotion(
    listState: LazyListState,
    itemKey: Any,
    topOcclusion: Dp = 0.dp,
): Modifier {
    val density = LocalDensity.current
    val topInset = with(density) { topOcclusion.toPx() }
    val minDistance = with(density) { 78.dp.toPx() }
    val travel = with(density) { 8.dp.toPx() }
    return Modifier.graphicsLayer {
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
        if (item == null) {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            translationY = 0f
        } else {
            val start = item.offset.toFloat()
            val height = item.size.coerceAtLeast(1).toFloat()
            val top = layout.viewportStartOffset + topInset
            val bottom = layout.viewportEndOffset.toFloat()
            val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            val topProgress = if (!scrolled || start >= top) 1f
                else ((start + height - top) / height).coerceIn(0f, 1f)
            val distance = maxOf(minDistance, height * 0.9f)
            val bottomProgress = ((bottom - start) / distance).coerceIn(0f, 1f)
            val raw = minOf(topProgress, bottomProgress)
            val edge = (raw * raw * (3f - 2f * raw)).coerceAtLeast(0.04f)
            val direction = if (scrolled && start < top) -1f
                else if (start > bottom - distance) 1f else 0f
            alpha = edge
            scaleX = 0.84f + 0.16f * edge
            scaleY = scaleX
            translationY = travel * direction * (1f - edge)
        }
    }
}
