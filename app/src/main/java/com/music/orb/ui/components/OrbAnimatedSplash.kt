package com.music.orb.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import com.music.orb.R
import org.xmlpull.v1.XmlPullParser
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Resolution-independent recreation of the approved Orb splash film, now accelerated to 4.5 seconds.
 *
 * The original vector paths are kept in orb_splash_layers.xml and painted by
 * Compose, without scaling up a 720p video (which blurred badly on tablets).
 * Timing and the end-state geometry are calibrated against that film:
 *   dot -> main ring -> red signal -> outer signal -> shrink -> "rb".
 * The small central symbol IS the 'o' in the final wordmark.
 *
 * This intentionally retains the warm-pink background even in night mode:
 * the approved film did not change appearance with the device theme.
 */
@Composable
fun OrbAnimatedSplash(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean,
    onFinished: () -> Unit,
) {
    val latestFinished = rememberUpdatedState(onFinished)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Parsing once, instead of on every draw, keeps the frames allocation-free.
    val paths = remember(context) { loadSplashVectorPaths(context) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(paths) {
        // Honor Android's global accessibility setting for disabled animations.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            latestFinished.value()
            return@LaunchedEffect
        }
        if (paths.size < 5) {
            latestFinished.value()
            return@LaunchedEffect
        }
        // Compose animation clock: changes invalidate drawing, not composition.
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 4_500, easing = LinearEasing),
        )
        latestFinished.value()
    }
    BackHandler { }

    val largeRing = paths["ring"]
    val centerDot = paths["dot"]
    val redRing = paths["red"]
    val signalRing = paths["outer"]
    val letters = paths["letters"]
    val majorWedge = remember { Path() }
    val redWedge = remember { Path() }
    val signalWedge = remember { Path() }
    val mainInk = Color(0xFF080808)
    val redInk = Color(0xFFF10B10)

    Canvas(Modifier.fillMaxSize()) {
        // Continuous full-screen shaders; no rectangular blur, texture or bitmap.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFFF3E8E5),
                0.45f to Color(0xFFF1E6E1),
                0.72f to Color(0xFFEDE0D9),
                1f to Color(0xFFE8D8D2),
            ),
        )
        val softRadius = size.maxDimension * 0.78f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x36FFF9F6), Color.Transparent),
                center = Offset(size.width * 0.40f, size.height * 0.40f),
                radius = softRadius,
            ),
            radius = softRadius,
            center = Offset(size.width * 0.40f, size.height * 0.40f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x14C8A19F), Color.Transparent),
                center = Offset(size.width * 0.91f, size.height * 0.88f),
                radius = softRadius * 0.75f,
            ),
            center = Offset(size.width * 0.91f, size.height * 0.88f),
            radius = softRadius * 0.75f,
        )

        val time = progress.value * 7f
        // The approved clip uses a 720x1280 design plane and the final symbol
        // occupies about 454px. Clamp to 320dp on tablets; never upscale raster.
        val factor = min(
            min(size.width / 720f, size.height / 1280f),
            320.dp.toPx() / 454f,
        )
        val baseX = (size.width - 720f * factor) / 2f
        val baseY = (size.height - 1280f * factor) / 2f

        // Android's launch window is deliberately plain. The symbol is first
        // drawn by Compose, so no static black dot precedes the animation.
        val dotAppear = phase(time, 0.02f, 0.34f)
        val largeAppear = phase(time, 0.56f, 1.22f)
        val redAppear = phase(time, 1.72f, 2.23f)
        val signalAppear = phase(time, 2.25f, 2.72f)
        val signalOpacity = phase(time, 2.25f, 2.38f)
        // The symbol remains exactly the same size from the opening frame to
        // the finished 'orb'. Only its horizontal position animates to make
        // room for the original, vector-drawn 'rb' characters.
        val wordmarkShift = phase(time, 4.60f, 5.54f)
        val lettersAppear = phase(time, 5.18f, 5.98f)

        // The final logo size is the opening logo size. Center the symbol
        // before the lettering appears, then translate left to its wordmark
        // position without any vertical motion or scale change.
        val svgScale = 0.190f
        val finalX = 145f
        val finalY = 455.5f
        val openingX = 360f - 491f * svgScale
        val svgX = interpolate(openingX, finalX, wordmarkShift)
        val svgY = finalY
        withTransform({
            translate(
                left = baseX + svgX * factor,
                top = baseY + svgY * factor,
            )
            scale(scaleX = svgScale * factor, scaleY = svgScale * factor, pivot = Offset.Zero)
        }) {
            centerDot?.let { dot ->
                if (dotAppear > 0f) drawPath(dot, mainInk, alpha = dotAppear)
            }

            largeRing?.let { ring ->
                when {
                    largeAppear >= 1f -> drawPath(ring, mainInk)
                    largeAppear > 0f -> {
                        // From the top tip around the left and bottom to the opening.
                        splashSector(majorWedge, Offset(491f, 971f), 860f, -82f, -240f * largeAppear)
                        clipPath(majorWedge) { drawPath(ring, mainInk) }
                    }
                }
            }

            redRing?.let { red ->
                if (redAppear > 0f) {
                    // Subtle native glow matching the source animation (no blur layer).
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(redInk.copy(alpha = 0.10f * redAppear), Color.Transparent),
                            center = Offset(700f, 923f),
                            radius = 160f,
                        ),
                        radius = 160f,
                        center = Offset(700f, 923f),
                    )
                    if (redAppear >= 1f) {
                        drawPath(red, redInk)
                    } else {
                        // The red signal grows from the upper tip downward.
                        splashSector(redWedge, Offset(491f, 971f), 860f, -55f, 76f * redAppear)
                        clipPath(redWedge) { drawPath(red, redInk) }
                    }
                }
            }

            signalRing?.let { signal ->
                when {
                    signalAppear >= 1f -> drawPath(signal, mainInk)
                    signalAppear > 0f -> {
                        // Short, gentle, outward signal pulse during its reveal.
                        splashSector(signalWedge, Offset(491f, 971f), 860f, -62f, 89f * signalAppear)
                        clipPath(signalWedge) {
                            drawPath(signal, mainInk, alpha = signalOpacity)
                        }
                    }
                }
            }
        }

        // The film reveals 'rb' left-to-right after the symbol has shrunk.
        // It uses the original wordmark outlines, not a substitute font.
        letters?.let { wordmark ->
            if (lettersAppear > 0f) {
                withTransform({
                    translate(
                        left = baseX + 145f * factor,
                        top = baseY + 455.5f * factor,
                    )
                    scale(scaleX = 0.190f * factor, scaleY = 0.190f * factor, pivot = Offset.Zero)
                }) {
                    clipRect(
                        left = 895f,
                        top = 470f,
                        right = 895f + 970f * lettersAppear,
                        bottom = 1350f,
                    ) {
                        drawPath(wordmark, mainInk)
                    }
                }
            }
        }
    }
}

private fun splashSector(path: Path, center: Offset, radius: Float, start: Float, sweep: Float) {
    path.reset()
    val radians = Math.toRadians(start.toDouble())
    path.moveTo(center.x, center.y)
    path.lineTo(
        center.x + cos(radians).toFloat() * radius,
        center.y + sin(radians).toFloat() * radius,
    )
    path.arcTo(
        Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
        startAngleDegrees = start,
        sweepAngleDegrees = sweep,
        forceMoveTo = false,
    )
    path.close()
}

private fun phase(time: Float, from: Float, until: Float): Float {
    val t = ((time - from) / (until - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun interpolate(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun loadSplashVectorPaths(context: Context): Map<String, Path> {
    val result = linkedMapOf<String, Path>()
    val parser = context.resources.getXml(R.drawable.orb_splash_layers)
    try {
        val ns = "http://schemas.android.com/apk/res/android"
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                val name = parser.getAttributeValue(ns, "name")
                val data = parser.getAttributeValue(ns, "pathData")
                if (!name.isNullOrBlank() && !data.isNullOrBlank()) {
                    PathParser.createPathFromPathData(data)?.let { native ->
                        result[name] = native.asComposePath()
                    }
                }
            }
            parser.next()
        }
    } finally {
        parser.close()
    }
    return result
}
