package com.music.orb.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Samples the lower field of the source artwork and turns it into the surface
 * colour used immediately below the image.
 *
 * The defaults preserve the immersive-profile behaviour. Release pages can use
 * a narrower sample window and a 100% artwork weight so their page surface is
 * taken directly from the visible lower edge of the cover.
 */
@Composable
fun rememberArtworkBottomWash(
    image: String?,
    fallback: Color,
    sampleStartFraction: Float = 0.72f,
    artworkWeight: Float = 0.78f,
): Color {
    val context = LocalContext.current
    val safeStart = sampleStartFraction.coerceIn(0f, 0.995f)
    val safeWeight = artworkWeight.coerceIn(0f, 1f)
    var sampledWash by remember(image, fallback, safeStart, safeWeight) {
        mutableStateOf(fallback)
    }

    LaunchedEffect(image, fallback, safeStart, safeWeight) {
        sampledWash = fallback
        if (image.isNullOrBlank()) return@LaunchedEffect

        val request = ImageRequest.Builder(context)
            .data(image)
            .size(if (safeStart >= 0.95f) 512 else 256)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val lowerWash = withContext(Dispatchers.Default) {
            bitmap.bottomWashColor(safeStart)
        } ?: return@LaunchedEffect

        sampledWash = if (safeWeight >= 0.999f) {
            lowerWash
        } else {
            lerp(fallback, lowerWash, safeWeight)
        }
    }

    return sampledWash
}

data class ArtworkBottomField(
    val strip: ImageBitmap?,
    val solidColor: Color,
)

/**
 * Keeps the actual colours from the lower edge of the artwork instead of
 * reducing the transition to one representative colour immediately.
 *
 * [strip] is a literal crop of the requested bottom portion of the source
 * image. Release pages stretch and progressively blur this strip below the
 * cover so the page looks like the artwork is physically smearing/dissolving
 * downward. [solidColor] is taken only from the very last rows and is used
 * after that field has fully diffused.
 */
@Composable
fun rememberArtworkBottomField(
    image: String?,
    fallback: Color,
    sampleStartFraction: Float = 0.95f,
): ArtworkBottomField {
    val context = LocalContext.current
    val safeStart = sampleStartFraction.coerceIn(0f, 0.995f)
    var field by remember(image, fallback, safeStart) {
        mutableStateOf(ArtworkBottomField(strip = null, solidColor = fallback))
    }

    LaunchedEffect(image, fallback, safeStart) {
        field = ArtworkBottomField(strip = null, solidColor = fallback)
        if (image.isNullOrBlank()) return@LaunchedEffect

        val request = ImageRequest.Builder(context)
            .data(image)
            .size(512)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect

        val computed = withContext(Dispatchers.Default) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext null
            val top = (bitmap.height * safeStart).toInt().coerceIn(0, bitmap.height - 1)
            val cropHeight = max(1, bitmap.height - top)
            val stripBitmap = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, cropHeight)
            // The final solid surface is derived only from the extreme bottom
            // rows. The visible transition itself still keeps all horizontal
            // colour variation from the literal bottom crop.
            val finalSampleStart = max(safeStart, 0.985f)
            val solid = bitmap.bottomWashColor(finalSampleStart) ?: fallback
            ArtworkBottomField(strip = stripBitmap.asImageBitmap(), solidColor = solid)
        } ?: return@LaunchedEffect

        field = computed
    }

    return field
}

private fun Bitmap.bottomWashColor(sampleStartFraction: Float): Color? {
    if (width <= 0 || height <= 0) return null

    // Album/playlist pages deliberately use only the final 5% of the artwork.
    // Downscaling that literal strip to 1x1 is effectively the endpoint of a
    // very strong blur: no dominant-colour extraction and no palette tinting.
    if (sampleStartFraction >= 0.95f) {
        val top = (height * sampleStartFraction).toInt().coerceIn(0, height - 1)
        val cropHeight = max(1, height - top)
        val strip = Bitmap.createBitmap(this, 0, top, width, cropHeight)
        val endpoint = Bitmap.createScaledBitmap(strip, 1, 1, true)
        val pixel = endpoint.getPixel(0, 0)
        if (endpoint !== strip) endpoint.recycle()
        strip.recycle()
        return Color(
            red = (pixel shr 16) and 0xFF,
            green = (pixel shr 8) and 0xFF,
            blue = pixel and 0xFF,
        )
    }

    val left = (width * 0.06f).toInt().coerceIn(0, width - 1)
    val right = (width * 0.94f).toInt().coerceIn(left + 1, width)
    val top = (height * sampleStartFraction).toInt().coerceIn(0, height - 1)
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
        red = min(255, (r * 0.96f + 8f).toInt()),
        green = min(255, (g * 0.98f + 6f).toInt()),
        blue = min(255, (b * 1.03f + 10f).toInt()),
    )
}
