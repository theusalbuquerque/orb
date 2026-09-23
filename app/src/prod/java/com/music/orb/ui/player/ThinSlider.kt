package com.music.orb.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.settings.AppSettings
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Dev / Material 3 Expressive progress control.
 *
 * The inactive rail stays calm and straight, while the played portion behaves
 * like a small elastic "worm": rounded, animated and with a stable leading
 * playhead.
 *
 * The moving body keeps the same thickness from beginning to end.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    mixing: Boolean = false,
    transitionWindow: ClosedFloatingPointRange<Float>? = null,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    contentColor: Color = Color.White,
) {
    val activeColor = contentColor.copy(alpha = 0.94f)
    val inactiveColor = contentColor.copy(alpha = 0.22f)
    val markerColor = contentColor.copy(alpha = 0.48f)

    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    var dragging by remember {
        mutableStateOf(false)
    }

    val strokeHeight by animateDpAsState(
        targetValue = if (dragging) {
            activeHeight
        } else {
            idleHeight
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveSliderThickness",
    )

    // Keep animation state out of composition. Reading `.value` only inside Canvas means
    // every phase tick invalidates the draw pass, not the whole slider composable.
    val wavePhase = if (reduceAnimation) {
        remember { mutableStateOf(0f) }
    } else {
        val transition = rememberInfiniteTransition(
            label = "expressiveSliderWave",
        )
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1_450,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "expressiveSliderWavePhase",
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                    )

                    dragging = true

                    onValueChange(
                        (down.position.x / size.width)
                            .coerceIn(0f, 1f),
                    )

                    while (true) {
                        val event = awaitPointerEvent()

                        val pointer = event.changes
                            .firstOrNull { it.id == down.id }
                            ?: break

                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }

                        if (pointer.positionChanged()) {
                            onValueChange(
                                (pointer.position.x / size.width)
                                    .coerceIn(0f, 1f),
                            )

                            pointer.consume()
                        }
                    }

                    dragging = false
                    onValueChangeFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(activeHeight + 14.dp),
        ) {
            val centerY = size.height / 2f

            /*
             * Static inactive rail.
             *
             * This remains independent from the moving worm.
             */
            val railHeight = max(
                3.dp.toPx(),
                idleHeight.toPx() * 0.56f,
            )

            val railRadius = CornerRadius(
                railHeight / 2f,
            )

            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(
                    x = 0f,
                    y = centerY - railHeight / 2f,
                ),
                size = Size(
                    width = size.width,
                    height = railHeight,
                ),
                cornerRadius = railRadius,
            )

            /*
             * Crossfade transition marker.
             */
            transitionWindow?.let { window ->
                val from = size.width *
                        window.start.coerceIn(0f, 1f)

                val to = size.width *
                        window.endInclusive.coerceIn(0f, 1f)

                if (to > from) {
                    drawRoundRect(
                        color = markerColor,
                        topLeft = Offset(
                            x = from,
                            y = centerY - railHeight / 2f,
                        ),
                        size = Size(
                            width = to - from,
                            height = railHeight,
                        ),
                        cornerRadius = railRadius,
                    )
                }
            }

            val filled = size.width *
                    value.coerceIn(0f, 1f)

            if (filled > 0f && !mixing) {
                val strokePx = strokeHeight.toPx()

                /*
                 * Slightly smaller leading playhead.
                 *
                 * Dragging: 0.67
                 * Idle:     0.59
                 */
                val headRadius = strokePx *
                        if (dragging) {
                            0.67f
                        } else {
                            0.59f
                        }

                val bodyEnd = (filled - headRadius)
                    .coerceAtLeast(0f)

                /*
                 * Dense continuous travelling wave.
                 */
                val waveLength = max(
                    22.dp.toPx(),
                    max(bodyEnd, 1f) / 6.5f,
                )

                /*
                 * Lower vertical amplitude.
                 *
                 * Previous:
                 * 3.3.dp / 0.40f
                 *
                 * New:
                 * 2.45.dp / 0.30f
                 */
                val baseAmplitude = minOf(
                    2.45.dp.toPx(),
                    strokePx * 0.30f,
                )

                val amplitude = if (reduceAnimation) {
                    0f
                } else {
                    baseAmplitude *
                            if (dragging) {
                                1.08f
                            } else {
                                1f
                            }
                }

                val phaseRadians = wavePhase.value.toDouble() *
                        (PI * 2.0)

                /*
                 * Uniform thickness for the whole moving worm.
                 *
                 * There is no taper anymore.
                 */
                val wormStrokePx = strokePx * 0.72f

                /*
                 * Small drawing steps keep the curve smooth.
                 */
                val step = max(
                    1.6.dp.toPx(),
                    1.5f,
                )

                val wormPath = Path()
                var x = 0f
                var lastX = 0f
                var lastY = centerY
                var firstPoint = true

                while (x <= bodyEnd) {
                    val fraction = if (bodyEnd <= 0f) {
                        0f
                    } else {
                        (x / bodyEnd).coerceIn(0f, 1f)
                    }

                    val waveEnvelope =
                        0.48f +
                            0.52f *
                            sin(PI * fraction)
                                .toFloat()
                                .coerceAtLeast(0f)

                    val headFade = ((1f - fraction) / 0.075f).coerceIn(0f, 1f)
                    val smoothHeadFade = headFade * headFade * (3f - 2f * headFade)
                    val wave = sin(
                        (x / waveLength).toDouble() * (PI * 2.0) - phaseRadians,
                    ).toFloat()
                    val y = centerY + amplitude * wave * waveEnvelope * smoothHeadFade

                    if (firstPoint) {
                        wormPath.moveTo(x, y)
                        firstPoint = false
                    } else {
                        wormPath.lineTo(x, y)
                    }
                    lastX = x
                    lastY = y
                    x += step
                }

                if (bodyEnd > 0f) {
                    if (firstPoint) wormPath.moveTo(0f, centerY)
                    if (lastX < bodyEnd || lastY != centerY) {
                        wormPath.lineTo(bodyEnd, centerY)
                    }
                    drawPath(
                        path = wormPath,
                        color = activeColor,
                        style = Stroke(
                            width = wormStrokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }

                /*
                 * Stable leading playhead.
                 *
                 * It moves only horizontally.
                 */
                drawCircle(
                    color = activeColor,
                    radius = headRadius,
                    center = Offset(
                        x = filled,
                        y = centerY,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = mixing,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = 420,
                ),
            ),
            exit = fadeOut(
                animationSpec = tween(
                    durationMillis = 520,
                ),
            ),
        ) {
            MixSheen(
                height = strokeHeight,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun MixSheen(
    height: Dp,
    color: Color,
) {
    val transition = rememberInfiniteTransition(
        label = "mixSheen",
    )

    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 500,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mixSheenPhase",
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val band = size.width * BAND_FRACTION

        val centre =
            -band +
                    (size.width + band * 2f) *
                    phase.value

        drawRoundRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to color.copy(alpha = 0.95f),
                    1f to Color.Transparent,
                ),
                start = Offset(
                    x = centre - band / 2f,
                    y = 0f,
                ),
                end = Offset(
                    x = centre + band / 2f,
                    y = 0f,
                ),
            ),
            cornerRadius = CornerRadius(
                size.height / 2f,
            ),
        )
    }
}

private const val BAND_FRACTION = 0.7f