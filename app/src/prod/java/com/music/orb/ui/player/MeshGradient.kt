package com.music.orb.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.orb.data.settings.AppSettings
import com.music.orb.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val FallbackColors = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

/** The four mesh colours, wrapped so the backdrop can skip recomposition. */
@Immutable
data class MeshPalette(
    val colors: List<Color>,
    val edgeColor: Color? = null,
    val edgeColors: List<Color> = emptyList(),
    val highlightColor: Color? = null,
    val isMonochrome: Boolean = false,
    /**
     * The exact background chosen by ArtworkPalette for album / playlist /
     * artist pages. This is the Now Playing colour source of truth.
     */
    val pageBackground: Color? = null,
    val contentColor: Color = Color.White,
)

/**
 * The Apple Music "Now Playing" backdrop: four luminous colour blobs sampled
 * from the album art, drawn as soft radial gradients and blurred into a mesh.
 * Colour changes on track skip crossfade over ~1.4s instead of snapping.
 *
 * The blobs drift when there is a reason to — the player opening, or
 * [trackKey] changing — and then come to rest. They used to orbit forever,
 * which meant re-blurring a full-screen layer at display refresh rate for as
 * long as the player was up: the most expensive thing in the app, for motion
 * that reads as ambient at best and is invisible while the phone is in a
 * pocket. The settled frame looks the same; only the battery drain is gone.
 */
@Composable
fun MeshGradientBackground(
    palette: MeshPalette,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    driftMillis: Int = 8_000,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    val tuned = (palette.colors.ifEmpty { FallbackColors } + FallbackColors)
        .take(4)
        .map { it.tuned() }

    val spatialEdgeTargets = when {
        palette.edgeColors.size >= 3 -> palette.edgeColors.take(3)
        palette.edgeColor != null -> listOf(
            palette.edgeColor,
            palette.edgeColor,
            palette.edgeColor,
        )
        else -> listOf(tuned.first(), tuned.first(), tuned.first())
    }.map { it.tunedForBase() }

    // 45 / 30 / 25 balance:
    // 45% = visual highlight of the whole cover
    // 30% = lower artwork edge continuity
    // 25% = secondary/spatial colours from the rest of the cover
    //
    // The weights are used both to choose the base wash and to set the visual
    // strength of the corresponding mesh layers.
    val highlightTarget = (palette.highlightColor ?: tuned.first()).tunedForBase()
    val edgeTarget = spatialEdgeTargets[1]
    val spatialTarget = averageColors(
        listOf(
            spatialEdgeTargets[0],
            spatialEdgeTargets[2],
            tuned.getOrElse(1) { tuned.first() }.tunedForBase(),
        ),
    )

    val weightedBaseTarget = if (palette.isMonochrome) {
        // For genuinely monochrome artwork, respect black and white instead of
        // pulling them toward a safe mid-grey. The UI adapts its foreground.
        (palette.highlightColor ?: highlightTarget).preserveMonochromeExtreme()
    } else {
        weightedBlend(
            colors = listOf(highlightTarget, edgeTarget, spatialTarget),
            weights = listOf(0.45f, 0.30f, 0.25f),
        )
    }

    val colorSpec: AnimationSpec<Color> = if (reduceAnimation) snap() else tween(1400)

    // Album / playlist / artist page colour is the source of truth.
    // The old mesh calculation is retained as a fallback so this file stays
    // robust if a page palette has not been resolved yet.
    val pageBaseTarget = palette.pageBackground ?: weightedBaseTarget

    // Keep the mesh depth, but do not let its accent blobs redefine the colour
    // identity. All accents are pulled strongly toward the exact page colour.
    val globalHighlightTarget = lerp(
        pageBaseTarget,
        highlightTarget.tuned(),
        0.12f,
    )

    val globalHighlight by animateColorAsState(
        globalHighlightTarget,
        colorSpec,
        label = "meshGlobalHighlight",
    )

    val animatedEdgeColors = spatialEdgeTargets.mapIndexed { index, color ->
        animateColorAsState(
            lerp(pageBaseTarget, color, 0.10f),
            colorSpec,
            label = "meshEdgeColor$index",
        ).value
    }

    val secondaryColors = listOf(
        tuned.getOrElse(1) { tuned.first() },
        tuned.getOrElse(2) { tuned.first() },
    )
    val animatedSecondaryColors = secondaryColors.mapIndexed { index, color ->
        animateColorAsState(
            lerp(pageBaseTarget, color, 0.08f),
            colorSpec,
            label = "meshSecondaryColor$index",
        ).value
    }

    val baseColor by animateColorAsState(
        pageBaseTarget,
        colorSpec,
        label = "meshBase",
    )

    // Read in the draw lambda, not here: an Animatable read during draw
    // invalidates only the drawing, leaving composition out of the loop.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey, reduceAnimation) {
        if (reduceAnimation) {
            phase.snapTo(0f)
        } else {
            phase.animateTo(
                targetValue = phase.value + DRIFT_RADIANS,
                animationSpec = tween(driftMillis, easing = FastOutSlowInEasing),
            )
        }
    }

    // Scale up slightly so the blur's clamped edges never show, then blur the
    // whole layer (RenderEffect, API 31+; a no-op below — the radial falloff
    // already reads soft there).
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .background(baseColor)
            .blur(64.dp),
    ) {
        val drift = phase.value

        // 45% — dominant visual highlight of the entire cover. This layer is
        // deliberately broad and central, so a visually important colour such
        // as Madonna's pink/magenta can define the identity of the controls
        // background even when the very bottom strip is blue.
        val highlightCenter = Offset(
            x = (0.50f + 0.035f * cos(drift)) * size.width,
            y = (0.34f + 0.025f * sin(drift * 0.85f)) * size.height,
        )
        val highlightRadius = size.maxDimension * 0.78f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    globalHighlight.copy(alpha = 0.34f),
                    globalHighlight.copy(alpha = 0f),
                ),
                center = highlightCenter,
                radius = highlightRadius,
            ),
            radius = highlightRadius,
            center = highlightCenter,
        )

        // 30% — preserve the actual lower-edge continuity. Left remains left,
        // center remains center, right remains right. The movement stays tiny,
        // so logos/seals cannot wander through the player.
        val edgeAnchors = listOf(
            Offset(0.16f, 0.62f),
            Offset(0.50f, 0.65f),
            Offset(0.84f, 0.62f),
        )
        val edgeSpeeds = listOf(0.80f, -0.65f, 0.72f)

        animatedEdgeColors.forEachIndexed { index, color ->
            val anchor = edgeAnchors[index]
            val center = Offset(
                x = (
                        anchor.x +
                                0.014f * cos(drift * edgeSpeeds[index] + index * 1.3f)
                        ) * size.width,
                y = (
                        anchor.y +
                                0.011f * sin(drift * edgeSpeeds[index] * 0.9f + index * 1.7f)
                        ) * size.height,
            )
            val radius = size.maxDimension * 0.46f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = 0.24f),
                        color.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // 25% — secondary colours from the rest of the cover. These add the
        // remaining spatial variety without overpowering the highlight colour.
        val secondaryAnchors = listOf(
            Offset(0.28f, 0.48f),
            Offset(0.74f, 0.50f),
        )
        animatedSecondaryColors.forEachIndexed { index, color ->
            val anchor = secondaryAnchors[index]
            val center = Offset(
                x = (
                        anchor.x +
                                0.018f * cos(drift * (0.55f + index * 0.18f) + index)
                        ) * size.width,
                y = (
                        anchor.y +
                                0.014f * sin(drift * (0.60f + index * 0.16f) + index * 1.4f)
                        ) * size.height,
            )
            val radius = size.maxDimension * 0.42f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = 0.16f),
                        color.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Keep depth without changing the page colour identity. Light page
        // palettes (black foreground) get only a very light shadow at the foot;
        // dark palettes can take a little more depth because their foreground is
        // already white.
        val usesDarkForeground = palette.contentColor == Color.Black
        val bottomAlpha = if (usesDarkForeground) 0.08f else 0.20f
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.42f to Color.Black.copy(alpha = if (usesDarkForeground) 0.00f else 0.02f),
                    0.68f to Color.Black.copy(alpha = bottomAlpha * 0.25f),
                    0.84f to Color.Black.copy(alpha = bottomAlpha * 0.55f),
                    1.00f to Color.Black.copy(alpha = bottomAlpha),
                ),
            ),
        )
    }
}

/**
 * Loads the artwork with Coil (software bitmap, thumbnail-sized) and pulls a
 * 4-colour palette out of it. Recomputes when [imageUrl] changes.
 *
 * A track's motion artwork is frequently lit nothing like its still sleeve —
 * a different shot, a different grade. [canvasFrame], a frame captured off
 * the playing clip once one is available, is quantised the same way and
 * takes over from there, crossfading in exactly like a track skip.
 */
@Composable
fun rememberArtworkColors(imageUrl: String?, canvasFrame: Bitmap? = null): MeshPalette {
    val context = LocalContext.current

    // Reuse the same artwork analysis as DetailScreen, but consume its
    // theme-independent player fields. Detail pages may add a light/dark overlay;
    // Now Playing deliberately does not, so it stays identical across themes.
    val detailPalette = rememberArtworkPalette(imageUrl)
    var palette by remember(imageUrl) {
        mutableStateOf(
            MeshPalette(
                colors = FallbackColors,
                edgeColor = FallbackColors.first(),
                edgeColors = listOf(
                    FallbackColors.first(),
                    FallbackColors.first(),
                    FallbackColors.first(),
                ),
                highlightColor = FallbackColors.first(),
                isMonochrome = false,
                pageBackground = detailPalette.playerBackground,
                contentColor = detailPalette.onBackground,
            ),
        )
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(256) // enough detail for both global and lower-edge palette sampling
            .allowHardware(false) // Palette needs pixel access
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect

        val sampled = withContext(Dispatchers.Default) { paletteAndEdgeOf(bitmap) }
        palette = MeshPalette(
            colors = sampled.colors,
            edgeColor = sampled.edgeColors[1],
            edgeColors = sampled.edgeColors,
            highlightColor = sampled.highlightColor,
            isMonochrome = sampled.isMonochrome,
            pageBackground = detailPalette.playerBackground,
            contentColor = detailPalette.onBackground,
        )
    }

    LaunchedEffect(canvasFrame) {
        val frame = canvasFrame ?: return@LaunchedEffect
        val sampled = withContext(Dispatchers.Default) { paletteAndEdgeOf(frame) }
        palette = MeshPalette(
            colors = sampled.colors,
            edgeColor = sampled.edgeColors[1],
            edgeColors = sampled.edgeColors,
            highlightColor = sampled.highlightColor,
            isMonochrome = sampled.isMonochrome,
            pageBackground = detailPalette.playerBackground,
            contentColor = detailPalette.onBackground,
        )
    }

    return palette.copy(
        pageBackground = detailPalette.playerBackground,
        contentColor = detailPalette.onBackground,
    )
}

/**
 * How far the blobs travel in one settle. A shade under half a turn: enough
 * that the backdrop visibly reacts to a track change, short of a full orbit
 * that would land the blobs back where they started.
 */
private const val DRIFT_RADIANS = (PI * 0.45f).toFloat()

/**
 * Four mesh colours drawn from the artwork.
 *
 * The named swatches — vibrant, muted and friends — are a convenience over the
 * full set, and on dark or desaturated sleeves every vibrant slot comes back
 * null: Karan Aujla's marble interior fills two of the five. Topping the rest
 * up from [FallbackColors] is what left those covers sitting under the stock
 * purple. So the whole swatch list is read instead, and any shortfall is
 * derived from the art's own colours rather than borrowed.
 */
private data class SpatialPaletteSample(
    val colors: List<Color>,
    val edgeColors: List<Color>,
    val highlightColor: Color,
    val isMonochrome: Boolean,
    val contentColor: Color,
)

private fun paletteAndEdgeOf(bitmap: Bitmap): SpatialPaletteSample {
    val global = paletteOf(bitmap)

    // Analyse only the final 12% of the artwork, but keep its horizontal
    // position. This lets the background visually continue whatever actually
    // touches the bottom-left, bottom-center and bottom-right of the sleeve.
    val bandHeight = (bitmap.height * 0.12f).toInt().coerceAtLeast(1)
    val bandTop = (bitmap.height - bandHeight).coerceAtLeast(0)

    val zoneColors = (0 until 3).map { zone ->
        val left = (bitmap.width * zone / 3f).toInt()
        val right = if (zone == 2) {
            bitmap.width
        } else {
            (bitmap.width * (zone + 1) / 3f).toInt()
        }
        val zoneWidth = (right - left).coerceAtLeast(1)

        val zoneBitmap = Bitmap.createBitmap(
            bitmap,
            left.coerceAtMost(bitmap.width - 1),
            bandTop,
            zoneWidth.coerceAtMost(bitmap.width - left),
            bandHeight,
        )

        try {
            representativeEdgeColor(
                bitmap = zoneBitmap,
                fallback = global.firstOrNull() ?: FallbackColors.first(),
            )
        } finally {
            if (zoneBitmap !== bitmap && !zoneBitmap.isRecycled) {
                zoneBitmap.recycle()
            }
        }
    }

    val highlight = visualHighlightColor(
        bitmap = bitmap,
        fallback = global.firstOrNull() ?: FallbackColors.first(),
    )
    val monochrome = bitmap.isNearlyMonochrome()

    val representative = if (monochrome) {
        highlight
    } else {
        weightedBlend(
            colors = listOf(highlight, zoneColors[1], averageColors(zoneColors)),
            weights = listOf(0.45f, 0.30f, 0.25f),
            preserveExtremes = true,
        )
    }

    return SpatialPaletteSample(
        colors = global,
        edgeColors = zoneColors,
        highlightColor = highlight,
        isMonochrome = monochrome,
        contentColor = representative.bestContentColor(),
    )
}

/**
 * Pick the colour that visually owns one spatial zone.
 *
 * Small neutral graphics such as "Parental Advisory", barcodes and white/black
 * logos should not overrule a saturated background. If a zone contains a
 * meaningful chromatic swatch, neutral greys are ignored for the representative
 * colour. Monochrome artwork still works because neutrals remain eligible when
 * no chromatic candidate exists.
 */

/**
 * Find the colour that behaves like the visual "hero" of the cover rather than
 * simply the largest bucket of pixels.
 *
 * Population still matters, but saturation gets a strong bonus. Very small
 * swatches are ignored so a logo, lipstick detail or tiny label cannot win.
 * This makes a large vivid pink/magenta subject outrank a broad but visually
 * quieter blue/purple background when that better matches how the cover reads.
 */
private fun visualHighlightColor(
    bitmap: Bitmap,
    fallback: Color,
): Color {
    val swatches = Palette.from(bitmap)
        .maximumColorCount(32)
        .clearFilters()
        .generate()
        .swatches

    if (swatches.isEmpty()) return fallback

    val totalPopulation = swatches.sumOf { it.population }.coerceAtLeast(1)

    val candidates = swatches.mapNotNull { swatch ->
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch.rgb, hsl)

        val share = swatch.population.toFloat() / totalPopulation.toFloat()
        val saturation = hsl[1]
        val lightness = hsl[2]

        // Ignore tiny details and nearly-neutral colours while chromatic
        // candidates exist. A 3% floor is enough to keep genuine subjects.
        if (share < 0.03f || saturation < 0.18f) {
            null
        } else {
            // Population is softened so a huge quiet background does not always
            // beat a slightly smaller but much more visually dominant colour.
            val populationScore = kotlin.math.sqrt(share)
            val saturationScore = 0.45f + saturation * 1.55f

            // Mid-lightness colours read more strongly than near-black/white.
            val lightnessBalance =
                (1f - kotlin.math.abs(lightness - 0.50f) * 1.15f).coerceIn(0.55f, 1f)

            val score = populationScore * saturationScore * lightnessBalance
            swatch to score
        }
    }

    val chosen = candidates.maxByOrNull { it.second }?.first
        ?: swatches.maxByOrNull { it.population }
        ?: return fallback

    return Color(chosen.rgb)
}

private fun representativeEdgeColor(
    bitmap: Bitmap,
    fallback: Color,
): Color {
    val swatches = Palette.from(bitmap)
        .maximumColorCount(24)
        .clearFilters()
        .generate()
        .swatches
        .sortedByDescending { it.population }

    if (swatches.isEmpty()) return fallback

    val totalPopulation = swatches.sumOf { it.population }.coerceAtLeast(1)

    val chromatic = swatches.filter { swatch ->
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch.rgb, hsl)
        val share = swatch.population.toFloat() / totalPopulation.toFloat()

        // Require enough saturation to represent an actual colour and enough
        // area to avoid tiny coloured logos/details becoming the zone anchor.
        hsl[1] >= 0.18f && share >= 0.035f
    }

    val chosen = (chromatic.ifEmpty { swatches })
        .maxByOrNull { it.population }
        ?: return fallback

    return Color(chosen.rgb)
}

private fun paletteOf(bitmap: Bitmap): List<Color> {
    fun swatchesOf(builder: Palette.Builder): List<Color> =
        builder.maximumColorCount(24).generate().swatches
            .sortedByDescending { it.population }
            .map { Color(it.rgb) }

    val found = swatchesOf(Palette.from(bitmap)).ifEmpty {
        // The default filter discards near-black and near-white, which on a
        // monochrome sleeve can be everything there is.
        swatchesOf(Palette.from(bitmap).clearFilters())
    }

    val distinct = found.distinctEnough()
    return when {
        distinct.isEmpty() -> FallbackColors
        distinct.size >= 4 -> distinct.take(4)
        else -> distinct.expandedToFour()
    }
}

/** Drop near-duplicates, so the four blobs don't collapse into one wash. */
private fun List<Color>.distinctEnough(): List<Color> {
    val kept = mutableListOf<Color>()
    forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
    return kept
}

private fun Color.isCloseTo(other: Color): Boolean {
    val a = hsl()
    val b = other.hsl()
    val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
    return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
}

/** Fill the empty slots off the art itself, fanning hue and lightness out. */
private fun List<Color>.expandedToFour(): List<Color> {
    val out = toMutableList()
    var step = 1
    while (out.size < 4) {
        out += this[(out.size - size) % size].shifted(24f * step, 0.12f * step)
        step++
    }
    return out
}


private fun weightedBlend(
    colors: List<Color>,
    weights: List<Float>,
    preserveExtremes: Boolean = false,
): Color {
    if (colors.isEmpty()) return FallbackColors.first()

    val usableWeights = weights.take(colors.size)
    val weightSum = usableWeights.sum().takeIf { it > 0f } ?: 1f

    var red = 0f
    var green = 0f
    var blue = 0f

    colors.forEachIndexed { index, color ->
        val weight = usableWeights.getOrElse(index) { 0f } / weightSum
        red += color.red * weight
        green += color.green * weight
        blue += color.blue * weight
    }

    val blended = Color(
        red = red.coerceIn(0f, 1f),
        green = green.coerceIn(0f, 1f),
        blue = blue.coerceIn(0f, 1f),
        alpha = 1f,
    )

    return if (preserveExtremes) blended else blended.tunedForBase()
}

private fun averageColors(colors: List<Color>): Color {
    if (colors.isEmpty()) return FallbackColors.first()
    val weight = 1f / colors.size.toFloat()
    return weightedBlend(
        colors = colors,
        weights = List(colors.size) { weight },
    )
}

private fun Color.shifted(hue: Float, lightness: Float): Color {
    val hsl = hsl()
    hsl[0] = (hsl[0] + hue) % 360f
    hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.hsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

/** Tune chromatic art while leaving true monochrome extremes intact. */
private fun Color.tuned(): Color {
    val hsl = hsl()

    if (hsl[1] < 0.12f) {
        // Black stays black, white stays white, and greys remain faithful.
        hsl[2] = hsl[2].coerceIn(0.01f, 0.99f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.12f, 0.72f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.tunedForBase(): Color {
    val hsl = hsl()

    if (hsl[1] < 0.12f) {
        hsl[2] = hsl[2].coerceIn(0.01f, 0.99f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    hsl[1] = (hsl[1] * 1.18f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.10f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.preserveMonochromeExtreme(): Color {
    val hsl = hsl()
    if (hsl[1] >= 0.12f) return tunedForBase()

    // Only artwork that is already genuinely near-black is pushed closer to
    // true black. Mid greys remain untouched, so covers such as Abracadabra
    // keep their natural grey identity instead of being collapsed into black.
    hsl[2] = when {
        hsl[2] <= 0.10f -> 0.02f
        hsl[2] >= 0.78f -> hsl[2].coerceAtLeast(0.92f)
        else -> hsl[2]
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.bestContentColor(): Color {
    val luminance = ColorUtils.calculateLuminance(toArgb())

    // Shared readability tuning: keep white controls/text on medium and
    // moderately bright artwork. Switch to black only when the generated
    // background is genuinely very bright.
    val blackTextThreshold = 0.72

    return if (luminance > blackTextThreshold) Color.Black else Color.White
}

private fun Bitmap.isNearlyMonochrome(): Boolean {
    val swatches = Palette.from(this)
        .maximumColorCount(24)
        .clearFilters()
        .generate()
        .swatches

    if (swatches.isEmpty()) return false

    val total = swatches.sumOf { it.population }.coerceAtLeast(1)
    val weightedSaturation = swatches.fold(0f) { sum, swatch ->
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(swatch.rgb, it) }
        sum + hsl[1] * (swatch.population.toFloat() / total.toFloat())
    }

    return weightedSaturation < 0.12f
}
