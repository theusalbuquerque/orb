package com.music.orb.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

/**
 * Horizontal drag detector with an explicit axis lock.
 *
 * Compose's stock horizontal drag/swipe detectors can win a gesture after a
 * small sideways wobble even when the user's intent is clearly a vertical
 * scroll. Orb has many dense, vertically scrolling music lists, so we only
 * claim the pointer after horizontal movement clears touch slop *and* is
 * materially stronger than vertical movement. Once vertical movement clears
 * slop first, this detector permanently yields the gesture to the parent
 * scroller.
 */
internal suspend fun PointerInputScope.detectOrbHorizontalDragGestures(
    horizontalDominance: Float = 1.35f,
    onDragStart: () -> Unit = {},
    onHorizontalDrag: (deltaX: Float) -> Unit,
    onDragEnd: (totalDeltaX: Float) -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val dominance = horizontalDominance.coerceAtLeast(1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val pointerId = down.id
        var lastPosition = down.position
        var total = Offset.Zero
        var horizontalLocked = false
        var verticalRejected = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change == null) {
                if (horizontalLocked) onDragCancel()
                break
            }
            val delta = change.position - lastPosition
            lastPosition = change.position
            total += delta

            if (!horizontalLocked && !verticalRejected) {
                val x = abs(total.x)
                val y = abs(total.y)
                when {
                    x >= viewConfiguration.touchSlop && x >= y * dominance -> {
                        horizontalLocked = true
                        onDragStart()
                    }
                    y >= viewConfiguration.touchSlop -> {
                        // Vertical intent wins permanently for this pointer.
                        verticalRejected = true
                    }
                }
            }

            if (horizontalLocked) {
                // Consume only after the axis decision. Until this point the
                // LazyColumn/verticalScroll above us remains free to claim the
                // gesture immediately.
                val changed = change.position - change.previousPosition
                if (changed != Offset.Zero) {
                    change.consume()
                    onHorizontalDrag(changed.x)
                }
            }

            if (!change.pressed) {
                if (horizontalLocked) onDragEnd(total.x) else onDragCancel()
                break
            }

            if (verticalRejected) {
                // Do not consume anything. Keep observing until up so a late
                // sideways wobble can never turn this vertical scroll into a
                // horizontal swipe.
                continue
            }
        }
    }
}
