@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Camera-anchored, fixed-position gooey pull-to-refresh overlay.
 *
 * The stretch is the silhouette of TWO rounded liquid lobes joined by a
 * cubic-Bezier metaball neck. It is NOT a circle drawn on a stem. The neck
 * pinches off well before the pull reaches full travel, while the camera end
 * recoils independently and the detached blob falls below the app header.
 *
 * This Canvas is an overlay only: the screen's lists and top bars never move.
 * Triggering early while the finger is held is handled by PullToRefresh in
 * Common.kt; both paths call the same refresh callback exactly once.
 */
@Composable
fun OrbRefreshDroplet(
    refreshing: Boolean,
    pullFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val cameraYDp = with(density) {
        (statusBarPx.toDp() * 0.53f).coerceIn(18.dp, 35.dp)
    }
    val colors = MaterialTheme.colorScheme

    val heldStretch = remember { Animatable(0f) }
    val separation = remember { Animatable(0f) }
    val returnHome = remember { Animatable(0f) }
    val wavePhase = remember { Animatable(0f) }
    var detached by remember { mutableStateOf(false) }
    var suppressHeldDrag by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        OrbHaptics.prepareRefreshBubblePop(context)
    }

    LaunchedEffect(refreshing) {
        if (refreshing) {
            // Capture the real stretched length before Material3 resets the
            // pull distance when the callback starts refreshing.
            val captured = max(pullFraction().coerceIn(0f, 1.2f), 0.82f)
            heldStretch.snapTo(captured)
            separation.snapTo(0f)
            returnHome.snapTo(0f)
            detached = true
            suppressHeldDrag = true

            coroutineScope {
                launch {
                    // Short pinch-off (the bridge narrows to ~0 px), then pop.
                    separation.animateTo(0.72f, tween(155, easing = FastOutSlowInEasing))
                    OrbHaptics.refreshBubblePop(context)
                    // Elastic fall of the detached blob. Upper lobe instantly
                    // relaxes back to the camera cutout.
                    separation.animateTo(1f, spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ))
                }
                launch {
                    heldStretch.animateTo(0.86f, spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessHigh,
                    ))
                }
            }
        } else if (detached) {
            // Show the spinner for a perceptible beat if the network finishes
            // unusually fast; then the whole blob springs back to the camera.
            delay(260L)
            returnHome.animateTo(1f, spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
                visibilityThreshold = 0.001f,
            ))
            delay(100L)
            detached = false
            separation.snapTo(0f)
            heldStretch.snapTo(0f)
            returnHome.snapTo(0f)
        }
    }

    LaunchedEffect(suppressHeldDrag) {
        if (suppressHeldDrag) {
            snapshotFlow { pullFraction() }.first { it <= 0.04f }
            suppressHeldDrag = false
        }
    }

    LaunchedEffect(refreshing) {
        if (refreshing) {
            while (true) {
                wavePhase.animateTo(360f, tween(1450, easing = LinearEasing))
                wavePhase.snapTo(0f)
            }
        } else {
            wavePhase.snapTo(0f)
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
        val actualPull = if (suppressHeldDrag) 0f else pullFraction().coerceIn(0f, 1.2f)
        val stretchFraction = if (detached) heldStretch.value else actualPull
        if (stretchFraction < 0.012f && !detached) return@Canvas

        val x = size.width / 2f
        val cameraY = cameraYDp.toPx()
        // A little wider than the cutout, preventing the black material from
        // appearing narrower than the physical camera during the first frames.
        val anchorRadius = 11.dp.toPx()
        val pull = FastOutSlowInEasing.transform(stretchFraction.coerceIn(0f, 1f))
        val split = if (detached) separation.value.coerceIn(0f, 1f) else 0f
        val homing = if (detached) returnHome.value.coerceIn(0f, 1f) else 0f

        // B grows organically while A stays anchored. The lower lobe travels
        // just ~64dp at the early-release threshold (0.82), not 110dp as in
        // the previous cone-shaped implementation.
        val bodyRadius = (11f + 8.8f * pull).dp.toPx()
        val travel = (2f + 72f * pull).dp.toPx()
        val lowerY = cameraY + travel + 49.dp.toPx() * split
        val neckDistance = lowerY - cameraY
        val waist = (anchorRadius * (0.92f - 0.25f * pull)) *
            (1f - smoothStep(0.05f, 0.72f, split) * 0.997f)

        if (!detached || split < 0.72f) {
            if (neckDistance <= anchorRadius + bodyRadius - 3.dp.toPx()) {
                // At the start both lobes overlap and look like one soft oval.
                // Switching to the Bezier neck only after they separate enough
                // avoids sharp cusp artifacts when the pull is nearly zero.
                val left = x - max(anchorRadius, bodyRadius)
                val top = cameraY - anchorRadius
                val right = x + max(anchorRadius, bodyRadius)
                val bottom = lowerY + bodyRadius
                drawOval(Color.Black, topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top))
            } else {
                val path = gooeyBridgePath(
                    cx = x,
                    anchorY = cameraY,
                    anchorRadius = anchorRadius,
                    dropY = lowerY,
                    dropRadius = bodyRadius,
                    neckHalfWidth = waist.coerceAtLeast(0.03.dp.toPx()),
                )
                drawPath(path, Color.Black)
            }
        } else {
            // The neck has snapped. The camera stays a clean black disc while
            // the free blob bounces down; no residual bar/outline remains.
            drawCircle(Color.Black, anchorRadius, Offset(x, cameraY))
        }

        if (detached && split >= 0.72f) {
            val emergence = smoothStep(0.72f, 1f, split)
            val bottomRest = cameraY + 145.dp.toPx()
            val freeY = lerpFloat(lowerY, bottomRest, emergence)
            val cy = lerpFloat(freeY, cameraY, homing)
            val freeRadius = lerpFloat(bodyRadius, 29.dp.toPx(), emergence)
            val radius = lerpFloat(freeRadius, anchorRadius, homing)
            val waveStrength = (1f - homing) * emergence
            val phase = wavePhase.value * (PI / 180.0)
            val outline = Path()
            val points = 128
            repeat(points) { i ->
                val angle = 2.0 * PI * i / points
                // Entire filled contour oscillates, not a colored border.
                val ripple = sin(9.0 * angle - 2.0 * phase)
                val r = radius + (2.45.dp.toPx() * waveStrength * ripple).toFloat()
                val px = x + r * cos(angle).toFloat()
                val py = cy + r * sin(angle).toFloat()
                if (i == 0) outline.moveTo(px, py) else outline.lineTo(px, py)
            }
            outline.close()
            drawPath(outline, lerp(colors.primaryContainer, Color.Black, homing))

            // Restore the pair of rounded vertical marks requested for the
            // working state; fade them away during the return to the camera.
            val alpha = (1f - smoothStep(0.10f, 0.92f, homing)) * emergence
            if (alpha > 0.01f) {
                val ink = colors.onPrimaryContainer.copy(alpha = alpha)
                val barH = (7.2.dp.toPx() + (1.0.dp.toPx() * sin(phase * 2.0)).toFloat()) *
                    (1f - homing)
                val barX = 4.1.dp.toPx()
                for (dir in listOf(-1f, 1f)) {
                    drawLine(
                        color = ink,
                        start = Offset(x + dir * barX, cy - barH / 2f),
                        end = Offset(x + dir * barX, cy + barH / 2f),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * A pair of rounded lobes with two cubic Beziers on each side.
 *
 * The contour follows the top circle to its lower shoulder, eases toward a
 * narrow middle, then flares into the lower circle. A genuine constriction is
 * created by tangent handles, NOT by joining two circles with straight lines.
 * Both caps are real circular arcs and no part of the shape has pointed tips.
 */
private fun gooeyBridgePath(
    cx: Float,
    anchorY: Float,
    anchorRadius: Float,
    dropY: Float,
    dropRadius: Float,
    neckHalfWidth: Float,
): Path {
    val a = Rect(cx - anchorRadius, anchorY - anchorRadius,
        cx + anchorRadius, anchorY + anchorRadius)
    val b = Rect(cx - dropRadius, dropY - dropRadius,
        cx + dropRadius, dropY + dropRadius)
    val leftA = Offset(cx - 0.940f * anchorRadius, anchorY + 0.342f * anchorRadius)
    val rightA = Offset(cx + 0.940f * anchorRadius, anchorY + 0.342f * anchorRadius)
    val leftB = Offset(cx - 0.866f * dropRadius, dropY - 0.500f * dropRadius)
    val rightB = Offset(cx + 0.866f * dropRadius, dropY - 0.500f * dropRadius)
    val span = (leftB.y - leftA.y).coerceAtLeast(1f)
    val middleY = leftA.y + 0.50f * span
    val leftNeck = cx - neckHalfWidth
    val rightNeck = cx + neckHalfWidth

    return Path().apply {
        moveTo(cx, anchorY - anchorRadius)
        arcTo(a, 270f, -110f, forceMoveTo = false)
        cubicTo(leftA.x + anchorRadius * 0.12f, leftA.y + span * 0.27f,
            leftNeck, middleY - span * 0.18f, leftNeck, middleY)
        cubicTo(leftNeck, middleY + span * 0.18f,
            leftB.x + dropRadius * 0.10f, leftB.y - span * 0.25f,
            leftB.x, leftB.y)
        arcTo(b, 210f, -240f, forceMoveTo = false)
        cubicTo(rightB.x - dropRadius * 0.10f, rightB.y - span * 0.25f,
            rightNeck, middleY + span * 0.18f, rightNeck, middleY)
        cubicTo(rightNeck, middleY - span * 0.18f,
            rightA.x - anchorRadius * 0.12f, rightA.y + span * 0.27f,
            rightA.x, rightA.y)
        arcTo(a, 20f, -110f, forceMoveTo = false)
        close()
    }
}

private fun lerpFloat(a: Float, b: Float, fraction: Float): Float = a + (b - a) * fraction

private fun smoothStep(a: Float, b: Float, value: Float): Float {
    val t = ((value - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
