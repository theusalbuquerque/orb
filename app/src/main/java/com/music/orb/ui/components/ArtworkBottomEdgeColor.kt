package com.music.orb.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import java.util.LinkedHashMap

/**
 * A colour field sampled only from the extreme bottom edge of an artwork.
 *
 * [edgeFlow] is not a resized/cropped duplicate of the cover. It is a newly
 * generated colour field: at the top it preserves the horizontal colours that
 * physically touch the bottom edge of the artwork, then those colours are
 * progressively mixed as the field moves downward until they become the single
 * [solidColor] used by the rest of the surface.
 */
data class ArtworkBottomEdgeField(
    val edgeFlow: ImageBitmap?,
    val solidColor: Color,
)

private const val EDGE_FIELD_CACHE_ENTRIES = 24
private val edgeFieldCacheLock = Any()
private val edgeFieldCache = object : LinkedHashMap<String, ArtworkBottomEdgeField>(
    EDGE_FIELD_CACHE_ENTRIES + 1,
    0.75f,
    true,
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkBottomEdgeField>?): Boolean =
        size > EDGE_FIELD_CACHE_ENTRIES
}

private fun edgeFieldCacheKey(image: String, sampleStartFraction: Float, edge: String = "bottom"): String =
    "$edge:${(sampleStartFraction * 10_000f).toInt()}:$image"

private fun cachedEdgeField(key: String): ArtworkBottomEdgeField? =
    synchronized(edgeFieldCacheLock) { edgeFieldCache[key] }

private fun cacheEdgeField(key: String, field: ArtworkBottomEdgeField) {
    synchronized(edgeFieldCacheLock) { edgeFieldCache[key] = field }
}

@Composable
fun rememberArtworkBottomEdgeField(
    image: String?,
    fallback: Color,
    sampleStartFraction: Float = 0.985f,
): ArtworkBottomEdgeField {
    val context = LocalContext.current
    val safeStart = sampleStartFraction.coerceIn(0.95f, 0.998f)
    if (image.isNullOrBlank()) {
        return ArtworkBottomEdgeField(edgeFlow = null, solidColor = fallback)
    }

    val cacheKey = remember(image, safeStart) { edgeFieldCacheKey(image, safeStart, edge = "bottom") }
    val latestFallback = rememberUpdatedState(fallback)
    var field by remember(image, safeStart) {
        mutableStateOf(
            cachedEdgeField(cacheKey)
                ?: ArtworkBottomEdgeField(edgeFlow = null, solidColor = fallback),
        )
    }

    // Crucially, fallback is NOT a key. rememberArtworkPalette() may publish a
    // better fallback one frame later; making that restart image decoding and
    // edge-field generation was doubling the work whenever a detail page opened.
    LaunchedEffect(image, safeStart) {
        cachedEdgeField(cacheKey)?.let { cached ->
            field = cached
            return@LaunchedEffect
        }

        val request = ImageRequest.Builder(context)
            .data(image)
            .size(384)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect

        val computed = withContext(Dispatchers.Default) {
            createBottomEdgeField(bitmap, safeStart, latestFallback.value)
        } ?: return@LaunchedEffect

        cacheEdgeField(cacheKey, computed)
        field = computed
    }

    return field
}



/**
 * Landscape/tablet counterpart of [rememberArtworkBottomEdgeField].
 *
 * Samples only the extreme right edge of the artwork and produces a field
 * that extends horizontally into the controls surface. The source artwork is
 * never stretched into the controls: only the colours physically touching the
 * right edge are preserved, then progressively mixed into one stable surface.
 */
@Composable
fun rememberArtworkRightEdgeField(
    image: String?,
    fallback: Color,
    sampleStartFraction: Float = 0.985f,
): ArtworkBottomEdgeField {
    val context = LocalContext.current
    val safeStart = sampleStartFraction.coerceIn(0.95f, 0.998f)
    if (image.isNullOrBlank()) {
        return ArtworkBottomEdgeField(edgeFlow = null, solidColor = fallback)
    }

    val cacheKey = remember(image, safeStart) { edgeFieldCacheKey(image, safeStart, edge = "right") }
    val latestFallback = rememberUpdatedState(fallback)
    var field by remember(image, safeStart) {
        mutableStateOf(
            cachedEdgeField(cacheKey)
                ?: ArtworkBottomEdgeField(edgeFlow = null, solidColor = fallback),
        )
    }

    LaunchedEffect(image, safeStart) {
        cachedEdgeField(cacheKey)?.let { cached ->
            field = cached
            return@LaunchedEffect
        }

        val request = ImageRequest.Builder(context)
            .data(image)
            .size(384)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect

        val computed = withContext(Dispatchers.Default) {
            createRightEdgeField(bitmap, safeStart, latestFallback.value)
        } ?: return@LaunchedEffect

        cacheEdgeField(cacheKey, computed)
        field = computed
    }

    return field
}

@Composable
fun rememberArtworkBottomEdgeColor(
    image: String?,
    fallback: Color,
    sampleStartFraction: Float = 0.985f,
): Color = rememberArtworkBottomEdgeField(
    image = image,
    fallback = fallback,
    sampleStartFraction = sampleStartFraction,
).solidColor

private data class Rgb(
    val r: Double,
    val g: Double,
    val b: Double,
)

private fun createBottomEdgeField(
    bitmap: Bitmap,
    sampleStartFraction: Float,
    fallback: Color,
): ArtworkBottomEdgeField? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    // Enough columns to retain black/white or multi-colour structure without
    // reproducing recognisable artwork detail.
    val columnCount = 24
    val top = (bitmap.height * sampleStartFraction).toInt().coerceIn(0, bitmap.height - 1)
    val sampleHeight = max(1, bitmap.height - top)
    val columnWidth = bitmap.width.toFloat() / columnCount.toFloat()
    val stepY = max(1, sampleHeight / 18)

    val columns = MutableList(columnCount) { Rgb(0.0, 0.0, 0.0) }
    val columnWeights = DoubleArray(columnCount)

    var totalR = 0.0
    var totalG = 0.0
    var totalB = 0.0
    var totalWeight = 0.0

    for (column in 0 until columnCount) {
        val left = (column * columnWidth).toInt().coerceIn(0, bitmap.width - 1)
        val right = (((column + 1) * columnWidth).toInt()).coerceIn(left + 1, bitmap.width)
        val stepX = max(1, (right - left) / 8)

        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weightSum = 0.0

        for (y in top until bitmap.height step stepY) {
            val yProgress = ((y - top).toFloat() / max(1, sampleHeight - 1)).coerceIn(0f, 1f)
            // The last physical rows dominate the field.
            val yWeight = (0.30f + yProgress * yProgress * 3.10f).toDouble()
            for (x in left until right step stepX) {
                val pixel = bitmap.getPixel(x, y)
                r += ((pixel shr 16) and 0xFF) * yWeight
                g += ((pixel shr 8) and 0xFF) * yWeight
                b += (pixel and 0xFF) * yWeight
                weightSum += yWeight
            }
        }

        if (weightSum > 0.0) {
            columns[column] = Rgb(r / weightSum, g / weightSum, b / weightSum)
            columnWeights[column] = weightSum
            totalR += r
            totalG += g
            totalB += b
            totalWeight += weightSum
        }
    }

    if (totalWeight <= 0.0) {
        return ArtworkBottomEdgeField(edgeFlow = null, solidColor = fallback)
    }

    val solid = Rgb(
        totalR / totalWeight,
        totalG / totalWeight,
        totalB / totalWeight,
    )
    val solidColor = solid.toComposeColor()

    // Generate the same progressive horizontal colour flow without the old
    // O(width * height * columns) Gaussian/exp loop. First interpolate the 24
    // sampled edge columns into one base row, then use prefix sums for an O(1)
    // variable-radius box blur at every output pixel. This cuts the field work
    // from >1M expensive exponentials to roughly 30K cheap arithmetic ops while
    // preserving the visual behaviour: edge colours remain distinct at the top
    // and converge smoothly toward [solidColor] lower down.
    val flowWidth = 160
    val flowHeight = 192
    val baseR = DoubleArray(flowWidth)
    val baseG = DoubleArray(flowWidth)
    val baseB = DoubleArray(flowWidth)

    for (x in 0 until flowWidth) {
        val sourcePosition = x.toDouble() / (flowWidth - 1).toDouble() * (columnCount - 1)
        val leftIndex = floor(sourcePosition).toInt().coerceIn(0, columnCount - 1)
        val rightIndex = (leftIndex + 1).coerceAtMost(columnCount - 1)
        val fraction = sourcePosition - leftIndex.toDouble()
        val left = columns[leftIndex]
        val right = columns[rightIndex]
        baseR[x] = left.r * (1.0 - fraction) + right.r * fraction
        baseG[x] = left.g * (1.0 - fraction) + right.g * fraction
        baseB[x] = left.b * (1.0 - fraction) + right.b * fraction
    }

    fun prefix(values: DoubleArray): DoubleArray {
        val result = DoubleArray(values.size + 1)
        for (index in values.indices) result[index + 1] = result[index] + values[index]
        return result
    }

    val prefixR = prefix(baseR)
    val prefixG = prefix(baseG)
    val prefixB = prefix(baseB)
    val pixels = IntArray(flowWidth * flowHeight)

    for (y in 0 until flowHeight) {
        val t = y.toDouble() / (flowHeight - 1).toDouble()
        val eased = t.pow(1.18)
        val radius = (1.0 + eased * flowWidth * 0.34).toInt().coerceIn(1, flowWidth / 2)
        val solidMix = if (t < 0.58) {
            0.0
        } else {
            ((t - 0.58) / 0.42).coerceIn(0.0, 1.0).pow(2.15)
        }

        for (x in 0 until flowWidth) {
            val left = (x - radius).coerceAtLeast(0)
            val right = (x + radius + 1).coerceAtMost(flowWidth)
            val count = (right - left).coerceAtLeast(1).toDouble()
            val blurred = Rgb(
                (prefixR[right] - prefixR[left]) / count,
                (prefixG[right] - prefixG[left]) / count,
                (prefixB[right] - prefixB[left]) / count,
            )
            val mixed = Rgb(
                blurred.r * (1.0 - solidMix) + solid.r * solidMix,
                blurred.g * (1.0 - solidMix) + solid.g * solidMix,
                blurred.b * (1.0 - solidMix) + solid.b * solidMix,
            )
            pixels[y * flowWidth + x] = mixed.toArgb()
        }
    }

    val flow = Bitmap.createBitmap(flowWidth, flowHeight, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, flowWidth, 0, 0, flowWidth, flowHeight)
    }

    return ArtworkBottomEdgeField(
        edgeFlow = flow.asImageBitmap(),
        solidColor = solidColor,
    )
}



private fun createRightEdgeField(
    bitmap: Bitmap,
    sampleStartFraction: Float,
    fallback: Color,
): ArtworkBottomEdgeField? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    // Mirror the bottom-edge algorithm on the X axis: preserve enough rows to
    // retain the vertical colour structure at the physical right edge, then
    // progressively blur/mix those rows as the field travels to the right.
    val rowCount = 24
    val left = (bitmap.width * sampleStartFraction).toInt().coerceIn(0, bitmap.width - 1)
    val sampleWidth = max(1, bitmap.width - left)
    val rowHeight = bitmap.height.toFloat() / rowCount.toFloat()
    val stepX = max(1, sampleWidth / 18)

    val rows = MutableList(rowCount) { Rgb(0.0, 0.0, 0.0) }
    var totalR = 0.0
    var totalG = 0.0
    var totalB = 0.0
    var totalWeight = 0.0

    for (row in 0 until rowCount) {
        val top = (row * rowHeight).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (((row + 1) * rowHeight).toInt()).coerceIn(top + 1, bitmap.height)
        val stepY = max(1, (bottom - top) / 8)

        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weightSum = 0.0

        for (x in left until bitmap.width step stepX) {
            val xProgress = ((x - left).toFloat() / max(1, sampleWidth - 1)).coerceIn(0f, 1f)
            val xWeight = (0.30f + xProgress * xProgress * 3.10f).toDouble()
            for (y in top until bottom step stepY) {
                val pixel = bitmap.getPixel(x, y)
                r += ((pixel shr 16) and 0xFF) * xWeight
                g += ((pixel shr 8) and 0xFF) * xWeight
                b += (pixel and 0xFF) * xWeight
                weightSum += xWeight
            }
        }

        if (weightSum > 0.0) {
            rows[row] = Rgb(r / weightSum, g / weightSum, b / weightSum)
            totalR += r
            totalG += g
            totalB += b
            totalWeight += weightSum
        }
    }

    if (totalWeight <= 0.0) {
        return ArtworkBottomEdgeField(edgeFlow = null, solidColor = fallback)
    }

    val solid = Rgb(
        totalR / totalWeight,
        totalG / totalWeight,
        totalB / totalWeight,
    )
    val solidColor = solid.toComposeColor()

    // Landscape gets a wider generated runway than portrait. The field is
    // later stretched into a 360dp band, so preserving more horizontal samples
    // prevents the right edge from collapsing into a flat block too close to
    // the physical artwork boundary.
    val flowWidth = 288
    val flowHeight = 160
    val baseR = DoubleArray(flowHeight)
    val baseG = DoubleArray(flowHeight)
    val baseB = DoubleArray(flowHeight)

    for (y in 0 until flowHeight) {
        val sourcePosition = y.toDouble() / (flowHeight - 1).toDouble() * (rowCount - 1)
        val topIndex = floor(sourcePosition).toInt().coerceIn(0, rowCount - 1)
        val bottomIndex = (topIndex + 1).coerceAtMost(rowCount - 1)
        val fraction = sourcePosition - topIndex.toDouble()
        val top = rows[topIndex]
        val bottom = rows[bottomIndex]
        baseR[y] = top.r * (1.0 - fraction) + bottom.r * fraction
        baseG[y] = top.g * (1.0 - fraction) + bottom.g * fraction
        baseB[y] = top.b * (1.0 - fraction) + bottom.b * fraction
    }

    fun prefix(values: DoubleArray): DoubleArray {
        val result = DoubleArray(values.size + 1)
        for (index in values.indices) result[index + 1] = result[index] + values[index]
        return result
    }

    val prefixR = prefix(baseR)
    val prefixG = prefix(baseG)
    val prefixB = prefix(baseB)
    val pixels = IntArray(flowWidth * flowHeight)

    for (x in 0 until flowWidth) {
        val t = x.toDouble() / (flowWidth - 1).toDouble()
        val eased = t.pow(1.18)
        val radius = (1.0 + eased * flowHeight * 0.34).toInt().coerceIn(1, flowHeight / 2)
        val solidMix = if (t < 0.70) {
            0.0
        } else {
            ((t - 0.70) / 0.30).coerceIn(0.0, 1.0).pow(1.55)
        }

        for (y in 0 until flowHeight) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(flowHeight)
            val count = (bottom - top).coerceAtLeast(1).toDouble()
            val blurred = Rgb(
                (prefixR[bottom] - prefixR[top]) / count,
                (prefixG[bottom] - prefixG[top]) / count,
                (prefixB[bottom] - prefixB[top]) / count,
            )
            val mixed = Rgb(
                blurred.r * (1.0 - solidMix) + solid.r * solidMix,
                blurred.g * (1.0 - solidMix) + solid.g * solidMix,
                blurred.b * (1.0 - solidMix) + solid.b * solidMix,
            )
            pixels[y * flowWidth + x] = mixed.toArgb()
        }
    }

    val flow = Bitmap.createBitmap(flowWidth, flowHeight, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, flowWidth, 0, 0, flowWidth, flowHeight)
    }

    return ArtworkBottomEdgeField(
        edgeFlow = flow.asImageBitmap(),
        solidColor = solidColor,
    )
}

private fun Rgb.toComposeColor(): Color = Color(
    red = r.toInt().coerceIn(0, 255),
    green = g.toInt().coerceIn(0, 255),
    blue = b.toInt().coerceIn(0, 255),
)

private fun Rgb.toArgb(): Int {
    val rr = r.toInt().coerceIn(0, 255)
    val gg = g.toInt().coerceIn(0, 255)
    val bb = b.toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
}


/** Foregrounds paired with an artwork-derived page surface. */
data class ArtworkSurfaceForegrounds(
    val primary: Color,
    val secondary: Color,
)

/**
 * Picks the foreground that has the highest WCAG contrast against [surface].
 *
 * This must be based on the final bottom-edge surface itself, not on the
 * whole-artwork palette, because a mostly-light cover can still resolve to a
 * very dark lower control surface (and vice versa).
 */
fun bestForegroundForArtworkSurface(surface: Color): Color {
    val whiteContrast = contrastRatio(Color.White, surface)
    val blackContrast = contrastRatio(Color.Black, surface)
    return if (whiteContrast >= blackContrast) Color.White else Color.Black
}

/**
 * Text colours for album/playlist pages.
 *
 * Pure black/white is always the emergency fallback, but using it everywhere
 * makes strongly coloured releases look detached from their own artwork. This
 * keeps a small amount of the surface hue in the foreground whenever that
 * tinted foreground still clears a strict 7:1 contrast target. Secondary text
 * is then blended back toward the artwork only as far as WCAG AA (4.5:1)
 * allows. The result behaves like the references: orange/light-blue pages get
 * a deep artwork-related near-black; dark artwork gets a subtly tinted white.
 */
fun artworkSurfaceForegrounds(surface: Color): ArtworkSurfaceForegrounds {
    val absolute = bestForegroundForArtworkSurface(surface)
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(surface.toArgb(), it) }
    val identityHsl = hsl.copyOf().also { tuned ->
        if (absolute == Color.Black) {
            tuned[1] = (tuned[1] * 0.30f).coerceAtMost(0.18f)
            tuned[2] = 0.035f
        } else {
            tuned[1] = (tuned[1] * 0.22f).coerceAtMost(0.14f)
            tuned[2] = 0.965f
        }
    }
    val identity = Color(ColorUtils.HSLToColor(identityHsl))
    val primary = if (contrastRatio(identity, surface) >= 7.0) identity else absolute
    val secondary = closestReadableBlend(
        surface = surface,
        foreground = primary,
        minimumContrast = 4.5,
    )
    return ArtworkSurfaceForegrounds(primary = primary, secondary = secondary)
}

private fun closestReadableBlend(
    surface: Color,
    foreground: Color,
    minimumContrast: Double,
): Color {
    // Find the least foreground-heavy opaque mix that remains readable. This
    // preserves more of the album colour than an arbitrary alpha while keeping
    // the real rendered contrast measurable and stable on every theme.
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        val candidate = blendOpaque(surface, foreground, mid)
        if (contrastRatio(candidate, surface) >= minimumContrast) high = mid else low = mid
    }
    return blendOpaque(surface, foreground, high)
}

private fun blendOpaque(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )
}

private fun contrastRatio(a: Color, b: Color): Double {
    fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.coerceIn(0f, 1f).toDouble()
            return if (value <= 0.04045) value / 12.92
            else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}
