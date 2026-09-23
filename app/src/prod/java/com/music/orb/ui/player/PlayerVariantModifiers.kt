package com.music.orb.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Shared interactive lyrics dismissal used by both release channels.
 *
 * The gesture follows the finger continuously. If the user releases before
 * the threshold, the lyrics ease back into place. If the threshold is crossed,
 * the animation finishes smoothly before the lyrics layer is closed.
 */
internal fun Modifier.lyricsDismissSwipe(
    enabled: Boolean,
    onDragProgress: (Float) -> Unit,
    onDismissLyrics: () -> Unit,
): Modifier {
    if (!enabled) return this

    return pointerInput(enabled) {
        val fullTravel = 190.dp.toPx()
        val dismissThreshold = 62.dp.toPx()

        coroutineScope {
            var totalDrag = 0f

            suspend fun settle(
                from: Float,
                to: Float,
                durationMillis: Int,
                onFinished: (() -> Unit)? = null,
            ) {
                animate(
                    initialValue = from,
                    targetValue = to,
                    animationSpec = tween(
                        durationMillis = durationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                ) { value, _ ->
                    onDragProgress(value.coerceIn(0f, 1f))
                }

                onFinished?.invoke()
            }

            detectVerticalDragGestures(
                onDragStart = {
                    totalDrag = 0f
                    onDragProgress(0f)
                },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()

                    totalDrag = (totalDrag + dragAmount).coerceAtLeast(0f)
                    onDragProgress(
                        (totalDrag / fullTravel).coerceIn(0f, 1f),
                    )
                },
                onDragCancel = {
                    val current =
                        (totalDrag / fullTravel).coerceIn(0f, 1f)

                    launch {
                        settle(
                            from = current,
                            to = 0f,
                            durationMillis = 220,
                        )
                    }

                    totalDrag = 0f
                },
                onDragEnd = {
                    val current =
                        (totalDrag / fullTravel).coerceIn(0f, 1f)

                    if (totalDrag >= dismissThreshold) {
                        launch {
                            settle(
                                from = current,
                                to = 1f,
                                durationMillis = 240,
                                onFinished = onDismissLyrics,
                            )
                        }
                    } else {
                        launch {
                            settle(
                                from = current,
                                to = 0f,
                                durationMillis = 240,
                            )
                        }
                    }

                    totalDrag = 0f
                },
            )
        }
    }
}
