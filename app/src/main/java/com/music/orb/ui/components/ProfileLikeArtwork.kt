package com.music.orb.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * The exact lower-edge colour treatment used by the immersive profile page.
 * This intentionally samples the lower field rather than the dominant image
 * colour so the surface below the artwork feels like a continuation of it.
 */
@Composable
fun rememberProfileLikeBottomWash(
    image: String?,
    fallback: Color,
): Color {
    val context = LocalContext.current
    var sampledWash by remember(image, fallback) { mutableStateOf(fallback) }

    LaunchedEffect(image, fallback) {
        sampledWash = fallback
        if (image.isNullOrBlank()) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(image)
            .size(256)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val lowerWash = withContext(Dispatchers.Default) { bitmap.profileLikeBottomWashColor() }
            ?: return@LaunchedEffect
        sampledWash = lerp(fallback, lowerWash, 0.94f)
    }

    return sampledWash
}

@Composable
fun rememberProfileLikeBottomFifteenStrip(
    image: String?,
): ImageBitmap? {
    val context = LocalContext.current
    var strip by remember(image) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(image) {
        strip = null
        if (image.isNullOrBlank()) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(image)
            .size(384)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        strip = withContext(Dispatchers.Default) { bitmap.profileLikeBottomFifteenStrip() }
    }

    return strip
}

private fun Bitmap.profileLikeBottomFifteenStrip(): ImageBitmap? {
    if (width <= 0 || height <= 1) return null
    val top = (height * 0.85f).toInt().coerceIn(0, height - 1)
    val stripHeight = (height - top).coerceAtLeast(1)
    val cropped = Bitmap.createBitmap(this, 0, top, width, stripHeight)
    return cropped.asImageBitmap()
}

private fun Bitmap.profileLikeBottomWashColor(): Color? {
    if (width <= 0 || height <= 0) return null

    val left = (width * 0.06f).toInt().coerceIn(0, width - 1)
    val right = (width * 0.94f).toInt().coerceIn(left + 1, width)
    val top = (height * 0.70f).toInt().coerceIn(0, height - 1)
    val bottom = height
    val sampleWidth = max(1, right - left)
    val sampleHeight = max(1, bottom - top)
    val stepX = max(1, sampleWidth / 72)
    val stepY = max(1, sampleHeight / 72)

    var totalWeight = 0.0
    var red = 0.0
    var green = 0.0
    var blue = 0.0

    for (y in top until bottom step stepY) {
        val yProgress = ((y - top).toFloat() / max(1, sampleHeight - 1)).coerceIn(0f, 1f)
        val yWeight = 0.45f + (yProgress * yProgress * 1.85f)

        for (x in left until right step stepX) {
            val pixel = getPixel(x, y)
            val xProgress = ((x - left).toFloat() / max(1, sampleWidth - 1)).coerceIn(0f, 1f)
            val sideBias = 0.78f + abs(xProgress - 0.5f) * 1.10f
            val weight = (yWeight * sideBias).toDouble()
            red += ((pixel shr 16) and 0xFF) * weight
            green += ((pixel shr 8) and 0xFF) * weight
            blue += (pixel and 0xFF) * weight
            totalWeight += weight
        }
    }

    if (totalWeight <= 0.0) return null

    val r = (red / totalWeight).toInt().coerceIn(0, 255)
    val g = (green / totalWeight).toInt().coerceIn(0, 255)
    val b = (blue / totalWeight).toInt().coerceIn(0, 255)

    return Color(
        red = r,
        green = g,
        blue = b,
    )
}

/**
 * Pixel-for-pixel the same progressive artwork stack used by the profile page:
 * one sharp image, six increasingly blurred replicas and a final 88.dp
 * diffusion layer. The caller controls only the outer geometry, so a release
 * can remain square while using the same visual algorithm.
 */
@Composable
fun ProfileLikeProgressiveArtwork(
    image: String,
    contentDescription: String?,
    wash: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(wash)) {
        // Square album/playlist artwork that follows the profile-page idea but
        // stays sharp for at least 95% of its height before entering the blur stack.
        ProfileLikeArtworkLayer(
            image = image,
            contentDescription = contentDescription,
            blurRadius = 0f,
            stops = arrayOf(
                0.0000f to 1f,
                0.9500f to 1f,
                0.9620f to 0f,
                1.0000f to 0f,
            ),
        )
        ProfileLikeArtworkLayer(image, null, 2f, arrayOf(0.9500f to 0f, 0.9560f to 1f, 0.9640f to 1f, 0.9700f to 0f))
        ProfileLikeArtworkLayer(image, null, 10f, arrayOf(0.9560f to 0f, 0.9620f to 1f, 0.9700f to 1f, 0.9760f to 0f))
        ProfileLikeArtworkLayer(image, null, 20f, arrayOf(0.9620f to 0f, 0.9680f to 1f, 0.9760f to 1f, 0.9820f to 0f))
        ProfileLikeArtworkLayer(image, null, 35f, arrayOf(0.9680f to 0f, 0.9740f to 1f, 0.9830f to 1f, 0.9890f to 0f))
        ProfileLikeArtworkLayer(image, null, 55f, arrayOf(0.9740f to 0f, 0.9810f to 1f, 0.9900f to 1f, 1.0000f to 0.58f))
        ProfileLikeArtworkLayer(image, null, 70f, arrayOf(0.9820f to 0f, 0.9890f to 0.92f, 0.9970f to 0.62f, 1.0000f to 0.16f))
        ProfileLikeArtworkDiffusionLayer(image = image)
    }
}

@Composable
fun ProfileLikeBottomContinuation(
    image: String,
    wash: Color,
    modifier: Modifier = Modifier,
) {
    val strip = rememberProfileLikeBottomFifteenStrip(image)
    Box(modifier = modifier.background(wash)) {
        if (strip != null) {
            // Continuation must melt into the page colour instead of reading as a
            // second block. Keep the top extremely soft and let the wash take over.
            BottomStripMergeBand(strip)
            BottomStripLayer(strip, 20f, arrayOf(0.00f to 0.10f, 0.14f to 0.20f, 0.34f to 0.18f, 0.60f to 0.08f, 1.00f to 0f))
            BottomStripLayer(strip, 35f, arrayOf(0.00f to 0.08f, 0.20f to 0.16f, 0.44f to 0.14f, 0.72f to 0.06f, 1.00f to 0f))
            BottomStripLayer(strip, 55f, arrayOf(0.00f to 0.06f, 0.28f to 0.12f, 0.56f to 0.10f, 0.84f to 0.04f, 1.00f to 0.01f))
            BottomStripLayer(strip, 70f, arrayOf(0.00f to 0.04f, 0.38f to 0.08f, 0.70f to 0.06f, 1.00f to 0.02f))
            BottomStripDiffusionLayer(strip)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to wash.copy(alpha = 0.08f),
                        0.16f to wash.copy(alpha = 0.12f),
                        0.38f to wash.copy(alpha = 0.20f),
                        0.64f to wash.copy(alpha = 0.42f),
                        0.82f to wash.copy(alpha = 0.74f),
                        1.00f to wash,
                    ),
                ),
        )
    }
}

@Composable
private fun BottomStripMergeBand(
    strip: ImageBitmap,
) {
    val stops = arrayOf(
        0.00f to 0.22f,
        0.18f to 0.18f,
        0.40f to 0.08f,
        0.62f to 0.02f,
        1.00f to 0f,
    )
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha.coerceIn(0f, 1f)) }.toTypedArray()
    val positions = stops.map { (position, _) -> position.coerceIn(0f, 1f) }.toFloatArray()
    Image(
        bitmap = strip,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        alignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .blur(26.dp, BlurredEdgeTreatment.Unbounded)
            .graphicsLayer(
                scaleX = 1.01f,
                scaleY = 1.03f,
                compositingStrategy = CompositingStrategy.Offscreen,
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun BottomStripLayer(
    strip: ImageBitmap,
    blurRadius: Float,
    stops: Array<Pair<Float, Float>>,
) {
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha.coerceIn(0f, 1f)) }.toTypedArray()
    val positions = stops.map { (position, _) -> position.coerceIn(0f, 1f) }.toFloatArray()
    Image(
        bitmap = strip,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        alignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (blurRadius > 0f) Modifier.blur(blurRadius.dp, BlurredEdgeTreatment.Unbounded)
                else Modifier,
            )
            .graphicsLayer(
                scaleX = 1.02f,
                scaleY = 1.06f,
                compositingStrategy = CompositingStrategy.Offscreen,
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun BottomStripDiffusionLayer(
    strip: ImageBitmap,
) {
    val stops = arrayOf(
        0.00f to 0.05f,
        0.26f to 0.10f,
        0.54f to 0.12f,
        0.80f to 0.08f,
        1.00f to 0.02f,
    )
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha) }.toTypedArray()
    val positions = stops.map { (position, _) -> position }.toFloatArray()
    Image(
        bitmap = strip,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        alignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .blur(80.dp, BlurredEdgeTreatment.Unbounded)
            .graphicsLayer(
                scaleX = 1.18f,
                scaleY = 1.10f,
                compositingStrategy = CompositingStrategy.Offscreen,
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun ProfileLikeArtworkDiffusionLayer(
    image: String,
) {
    val stops = arrayOf(
        0.0000f to 0f,
        0.9850f to 0f,
        0.9910f to 0.18f,
        0.9960f to 0.52f,
        0.9985f to 0.34f,
        1.0000f to 0.02f,
    )
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha) }.toTypedArray()
    val positions = stops.map { (position, _) -> position }.toFloatArray()

    AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .blur(80.dp, BlurredEdgeTreatment.Unbounded)
            .graphicsLayer(
                scaleX = 1.18f,
                scaleY = 1.10f,
                compositingStrategy = CompositingStrategy.Offscreen,
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun ProfileLikeArtworkLayer(
    image: String,
    contentDescription: String?,
    blurRadius: Float,
    stops: Array<Pair<Float, Float>>,
) {
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha.coerceIn(0f, 1f)) }.toTypedArray()
    val positions = stops.map { (position, _) -> position.coerceIn(0f, 1f) }.toFloatArray()
    AsyncImage(
        model = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (blurRadius > 0f) Modifier.blur(blurRadius.dp, BlurredEdgeTreatment.Unbounded)
                else Modifier,
            )
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}
