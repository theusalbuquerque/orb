package com.music.orb.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.orb.data.model.CARD_ART_PX
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * The colours an album, playlist or artist page paints itself in.
 *
 * Apple Music's release pages are not one design tinted five ways — the whole
 * page is derived from the sleeve, down to which grey the metadata line is. So
 * rather than hand callers a raw swatch and let each of them guess, this is the
 * finished set: a page tint, an accent that is legible *on that tint*, and the
 * two text colours and hairline that go with them.
 *
 * Every value is theme-aware. The same sleeve yields a near-black tint in dark
 * mode and a pale wash of the same hue in light mode, which is the only way the
 * pages stay readable when the app's theme disagrees with the artwork's.
 */
@Immutable
data class ArtworkStatusBarProfile(
    val darkIconBands: List<Boolean> = emptyList(),
) {
    /**
     * Whether Android should draw dark status-bar glyphs over the artwork at
     * [verticalFraction] (0 = top edge, 1 = bottom edge).
     *
     * Artwork pages scroll the picture underneath a fixed status bar, so one
     * global light/dark decision for the whole cover is not enough.
     */
    fun darkIconsAt(verticalFraction: Float): Boolean {
        if (darkIconBands.isEmpty()) return false
        val fraction = verticalFraction.coerceIn(0f, 0.9999f)
        val index = (fraction * darkIconBands.size)
            .toInt()
            .coerceIn(0, darkIconBands.lastIndex)
        return darkIconBands[index]
    }

    companion object {
        fun uniform(darkIcons: Boolean): ArtworkStatusBarProfile =
            ArtworkStatusBarProfile(List(STATUS_BAR_PROFILE_BANDS) { darkIcons })
    }
}

@Immutable
data class ArtworkPalette(
    /** The page's background wash. */
    val background: Color,
    /**
     * The colour the artwork's own bottom edge blurs down to.
     *
     * A blur wide enough to lose the picture leaves the mean of what it
     * sampled, so a page that starts from this colour where the artwork stops
     * reads as that blur carrying on rather than as a second surface beginning.
     * Lighter than [background], which the page still settles into further
     * down — the artwork's colour is strongest right under the artwork.
     */
    val wash: Color,
    /** Fill for the glass buttons and chips that sit on [background]. */
    val elevated: Color,
    /** The artwork's own colour, contrast-corrected — titles, icons, Play. */
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
    /**
     * Theme-independent colour for Now Playing.
     *
     * Detail pages may apply a light/dark overlay, but the player must look the
     * same regardless of the app/system theme.
     */
    val playerBackground: Color = background,
    /** Foreground paired with [playerBackground]. */
    val playerOnBackground: Color = onBackground,
    /**
     * Artwork accent for Now Playing.
     *
     * Uses the same rule as [accent], but validates contrast against the
     * theme-independent [playerBackground]. If the artwork accent is not
     * readable there, it falls back to [playerOnBackground].
     */
    val playerAccent: Color = accent,
    /**
     * Light/dark status-bar decisions sampled from the artwork itself.
     *
     * This is intentionally independent from [playerOnBackground]: that colour
     * describes the generated player surface, while the status bar may sit on
     * the untouched image.
     */
    val statusBarProfile: ArtworkStatusBarProfile = ArtworkStatusBarProfile(),
)

/**
 * Pulls [ArtworkPalette] out of the artwork at [imageUrl].
 *
 * Artwork that has already been read once is tinted on the very first frame,
 * off [seedCache] — a sheet opened from a page it shares a cover with, or a
 * page opened twice, has nothing to wait for and nothing to fade. Only a sleeve
 * genuinely being seen for the first time starts from the theme's own colours
 * and warms into the artwork's, so it never flashes a placeholder tint.
 * "Reduce animation" turns that crossfade into a cut.
 */
@Composable
fun rememberArtworkPalette(
    imageUrl: String?,
    dark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    /**
     * The artwork size to read, which should be whichever one the surface
     * already has on screen, matching the backdrop drawn from it.
     *
     * A quantiser cares about a thumbnail's resolution no more than a blur
     * does, so the only thing this choice decides is whether the read comes
     * out of the cache or off the network.
     */
    artPx: Int = CARD_ART_PX,
): ArtworkPalette {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    // The two swatches everything else is derived from, or null until read.
    var seed by remember(imageUrl) { mutableStateOf(imageUrl?.let(seedCache::get)) }
    // Whether the colours were there from the first frame. If they were, there
    // is nothing to crossfade *from* and animating would only put a delay in
    // front of a surface that could already be right.
    val knownUpFront = remember(imageUrl) { seed != null }

    LaunchedEffect(imageUrl, artPx) {
        if (imageUrl == null || seed != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            // The size the artwork is *displayed* at, deliberately: the fetch
            // then shares a disk-cache entry with the row, card or backdrop
            // drawing the same artwork, instead of pulling its own copy over
            // the wire — which is the difference between a surface that is
            // tinted as it opens and one that turns colour a second later.
            .data(imageUrl.artworkAt(artPx))
            .size(PALETTE_PX) // palette quality holds up here, and it's far faster
            .allowHardware(false) // Palette needs pixel access
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        // Quantising 128² pixels is not free, and this coroutine is on the main
        // dispatcher — left there it stutters whatever is animating the surface in.
        val found = withContext(Dispatchers.Default) { seedOf(bitmap) } ?: return@LaunchedEffect
        seedCache[imageUrl] = found
        seed = found
    }

    val target = seed?.toPalette(dark) ?: ArtworkPalette(
        background = scheme.background,
        wash = scheme.background,
        elevated = scheme.surfaceVariant,
        accent = scheme.primary,
        onBackground = scheme.onBackground,
        onBackgroundVariant = scheme.onSurfaceVariant,
        divider = scheme.outline,
        statusBarProfile = ArtworkStatusBarProfile.uniform(darkIcons = !dark),
    )

    val spec: AnimationSpec<Color> = if (reduceAnimation || knownUpFront) {
        snap()
    } else {
        tween(TINT_FADE_MS)
    }
    return ArtworkPalette(
        background = animateColorAsState(target.background, spec, label = "tintBackground").value,
        wash = animateColorAsState(target.wash, spec, label = "tintWash").value,
        elevated = animateColorAsState(target.elevated, spec, label = "tintElevated").value,
        accent = animateColorAsState(target.accent, spec, label = "tintAccent").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "tintOn").value,
        onBackgroundVariant = animateColorAsState(
            target.onBackgroundVariant, spec, label = "tintOnVariant",
        ).value,
        divider = animateColorAsState(target.divider, spec, label = "tintDivider").value,
        playerBackground = animateColorAsState(
            target.playerBackground, spec, label = "tintPlayerBackground",
        ).value,
        playerOnBackground = animateColorAsState(
            target.playerOnBackground, spec, label = "tintPlayerOnBackground",
        ).value,
        playerAccent = animateColorAsState(
            target.playerAccent, spec, label = "tintPlayerAccent",
        ).value,
        statusBarProfile = target.statusBarProfile,
    )
}

/**
 * Colours already read, keyed by artwork URL.
 *
 * Reading them again costs a decode and a quantise for an answer that cannot
 * have changed — the artwork at a URL is the artwork at that URL. Access is
 * from composition and from the resumption of [rememberArtworkPalette]'s
 * effect, both on the main thread, so it needs no locking of its own.
 */
private val seedCache = object : LinkedHashMap<String, Seed>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Seed>) = size > SEED_CACHE_ENTRIES
}

/** Deep enough to cover a session's browsing without holding a screenful of colours. */
private const val SEED_CACHE_ENTRIES = 128

private const val PALETTE_PX = 128

/** Short: this is a surface settling into its colour, not an effect in itself. */
private const val TINT_FADE_MS = 260

/**
 * The raw artwork colours used to build a detail-page tint.
 *
 * The page follows the same balance as Now Playing:
 * 45% = visual highlight of the whole artwork
 * 30% = colour of the lower edge
 * 25% = spatial colour distribution across the artwork
 */
private data class Seed(
    val balanced: Color,
    val highlight: Color,
    val edge: Color,
    val spatial: Color,
    val dominant: Color,
    val monochrome: Boolean,
    val statusBarProfile: ArtworkStatusBarProfile,
)

private fun seedOf(bitmap: Bitmap): Seed? {
    val dominant = bitmap.dominantColor() ?: return null
    val monochrome = bitmap.isNearlyMonochrome()
    val highlight = bitmap.visualHighlightColor() ?: dominant
    val edge = bitmap.bottomEdgeRepresentative(highlight)
    val spatial = bitmap.spatialRepresentative(highlight)
    val statusBarProfile = bitmap.statusBarProfile()

    val balanced = if (monochrome) {
        // For black/white artwork the true dominant tone is the identity.
        // Do not average it into a generic mid-grey.
        dominant.preserveMonochromeExtreme()
    } else {
        weightedBlend(
            colors = listOf(highlight, edge, spatial),
            weights = listOf(0.45f, 0.30f, 0.25f),
        )
    }

    return Seed(
        balanced = balanced,
        highlight = highlight,
        edge = edge,
        spatial = spatial,
        dominant = dominant,
        monochrome = monochrome,
        statusBarProfile = statusBarProfile,
    )
}

private const val SWATCH_COUNT = 24

/**
 * Visually dominant colour of the entire artwork.
 *
 * Population matters, but saturation and mid-range lightness get extra weight,
 * so a large vivid pink/magenta subject can outrank a quieter blue background.
 * Tiny details are ignored so logos, labels and small ornaments cannot become
 * the page identity.
 */
private fun Bitmap.visualHighlightColor(): Color? {
    val swatches = Palette.from(this)
        .maximumColorCount(32)
        .clearFilters()
        .generate()
        .swatches

    if (swatches.isEmpty()) return null

    val total = swatches.sumOf { it.population }.coerceAtLeast(1)

    val candidates = swatches.mapNotNull { swatch ->
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(swatch.rgb, it) }
        val share = swatch.population.toFloat() / total.toFloat()
        val saturation = hsl[1]
        val lightness = hsl[2]

        if (share < 0.03f || saturation < 0.18f) {
            null
        } else {
            val populationScore = sqrt(share)
            val saturationScore = 0.45f + saturation * 1.55f
            val lightnessBalance =
                (1f - kotlin.math.abs(lightness - 0.50f) * 1.15f).coerceIn(0.55f, 1f)

            swatch to (populationScore * saturationScore * lightnessBalance)
        }
    }

    val chosen = candidates.maxByOrNull { it.second }?.first
        ?: swatches.maxByOrNull { it.population }
        ?: return null

    return Color(chosen.rgb)
}

/**
 * Representative colour of the artwork's final 12%, split into three zones.
 *
 * Left, centre and right are analysed independently before being recombined.
 * Neutral colours from small logos, parental-advisory marks or barcodes are
 * ignored whenever a meaningful chromatic colour exists in the same zone.
 */
private fun Bitmap.bottomEdgeRepresentative(fallback: Color): Color {
    val band = (height * EDGE_BAND).toInt().coerceIn(1, height)
    val top = height - band

    val zones = (0 until 3).map { zone ->
        val left = (width * zone / 3f).toInt()
        val right = if (zone == 2) width else (width * (zone + 1) / 3f).toInt()
        val zoneWidth = (right - left).coerceAtLeast(1)

        val crop = Bitmap.createBitmap(
            this,
            left.coerceAtMost(width - 1),
            top,
            zoneWidth.coerceAtMost(width - left),
            band,
        )

        try {
            crop.representativeRegionColor(fallback)
        } finally {
            if (crop !== this && !crop.isRecycled) crop.recycle()
        }
    }

    return averageColors(zones)
}

/**
 * Spatial colour of the whole artwork, preserving broad left/centre/right
 * distribution without allowing one small object to dominate the page.
 */
private fun Bitmap.spatialRepresentative(fallback: Color): Color {
    val zones = (0 until 3).map { zone ->
        val left = (width * zone / 3f).toInt()
        val right = if (zone == 2) width else (width * (zone + 1) / 3f).toInt()
        val zoneWidth = (right - left).coerceAtLeast(1)

        val crop = Bitmap.createBitmap(
            this,
            left.coerceAtMost(width - 1),
            0,
            zoneWidth.coerceAtMost(width - left),
            height,
        )

        try {
            crop.representativeRegionColor(fallback)
        } finally {
            if (crop !== this && !crop.isRecycled) crop.recycle()
        }
    }

    return averageColors(zones)
}

private fun Bitmap.representativeRegionColor(fallback: Color): Color {
    val swatches = Palette.from(this)
        .maximumColorCount(SWATCH_COUNT)
        .clearFilters()
        .generate()
        .swatches
        .sortedByDescending { it.population }

    if (swatches.isEmpty()) return fallback

    val total = swatches.sumOf { it.population }.coerceAtLeast(1)

    val chromatic = swatches.filter { swatch ->
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(swatch.rgb, it) }
        val share = swatch.population.toFloat() / total.toFloat()
        hsl[1] >= 0.18f && share >= 0.035f
    }

    val chosen = (chromatic.ifEmpty { swatches })
        .maxByOrNull { it.population }
        ?: return fallback

    return Color(chosen.rgb)
}

private fun weightedBlend(
    colors: List<Color>,
    weights: List<Float>,
): Color {
    if (colors.isEmpty()) return Color.Black

    val usableWeights = weights.take(colors.size)
    val totalWeight = usableWeights.sum().takeIf { it > 0f } ?: 1f

    var red = 0f
    var green = 0f
    var blue = 0f

    colors.forEachIndexed { index, color ->
        val weight = usableWeights.getOrElse(index) { 0f } / totalWeight
        red += color.red * weight
        green += color.green * weight
        blue += color.blue * weight
    }

    return Color(
        red = red.coerceIn(0f, 1f),
        green = green.coerceIn(0f, 1f),
        blue = blue.coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/**
 * Samples the artwork in horizontal bands for system-bar contrast.
 *
 * Each band is also split into vertical zones. We choose whichever of black or
 * white has the stronger worst-case contrast across the zones, so a bright logo
 * or a dark portrait on one side does not make the entire status bar illegible.
 */
private fun Bitmap.statusBarProfile(): ArtworkStatusBarProfile {
    if (width <= 0 || height <= 0) return ArtworkStatusBarProfile()

    val bands = List(STATUS_BAR_PROFILE_BANDS) { band ->
        val top = (height * band / STATUS_BAR_PROFILE_BANDS.toFloat())
            .toInt()
            .coerceIn(0, height - 1)
        val bottom = (height * (band + 1) / STATUS_BAR_PROFILE_BANDS.toFloat())
            .toInt()
            .coerceIn(top + 1, height)

        val zones = List(STATUS_BAR_PROFILE_ZONES) { zone ->
            val left = (width * zone / STATUS_BAR_PROFILE_ZONES.toFloat())
                .toInt()
                .coerceIn(0, width - 1)
            val right = (width * (zone + 1) / STATUS_BAR_PROFILE_ZONES.toFloat())
                .toInt()
                .coerceIn(left + 1, width)
            averageRegionColor(left, top, right, bottom)
        }

        val blackWorst = zones.minOf { Color.Black.contrastRatio(it) }
        val whiteWorst = zones.minOf { Color.White.contrastRatio(it) }

        if (kotlin.math.abs(blackWorst - whiteWorst) < 0.20f) {
            averageColors(zones).luminance() >= 0.50f
        } else {
            blackWorst > whiteWorst
        }
    }

    return ArtworkStatusBarProfile(bands)
}

private fun Bitmap.averageRegionColor(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Color {
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var count = 0

    for (y in top until bottom) {
        for (x in left until right) {
            val pixel = getPixel(x, y)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
    }

    if (count == 0) return Color.Black

    return Color(
        red = (red / count / 255.0).toFloat(),
        green = (green / count / 255.0).toFloat(),
        blue = (blue / count / 255.0).toFloat(),
        alpha = 1f,
    )
}

private fun averageColors(colors: List<Color>): Color {
    if (colors.isEmpty()) return Color.Black
    return weightedBlend(
        colors = colors,
        weights = List(colors.size) { 1f / colors.size.toFloat() },
    )
}

/** How much of the artwork's height the edge colour is read from. */
private const val EDGE_BAND = 0.12f

/** Vertical resolution used while artwork scrolls underneath the status bar. */
private const val STATUS_BAR_PROFILE_BANDS = 10

/** Independent horizontal regions checked for status-bar contrast. */
private const val STATUS_BAR_PROFILE_ZONES = 4

private fun Seed.toPalette(dark: Boolean): ArtworkPalette {
    // Beta's artwork-matched palette is now the shared visual behaviour for
    // both release channels. BuildConfig.IS_BETA is reserved for release
    // identity/update-channel decisions, not feature availability.
    return toBetaPlayerMatchedPalette(dark)
}

private fun Seed.toStablePalette(dark: Boolean): ArtworkPalette {
    val base = if (monochrome) dominant.preserveMonochromeExtreme() else balanced
    val baseLuminance = base.luminance()
    val on = if (baseLuminance > 0.48f) Color.Black else Color.White

    val background = if (monochrome) {
        base
    } else if (dark) {
        base.withHsl(
            saturation = { it.coerceIn(0.24f, 0.68f) },
            lightness = { current -> current.coerceIn(0.10f, 0.24f) },
        )
    } else {
        base.withHsl(
            saturation = { it.coerceIn(0.16f, 0.54f) },
            lightness = { current -> current.coerceIn(0.76f, 0.94f) },
        )
    }

    val wash = if (monochrome) {
        base.withHsl(
            lightness = { current ->
                if (baseLuminance > 0.48f) {
                    (current - 0.04f).coerceAtLeast(0f)
                } else {
                    (current + 0.04f).coerceAtMost(1f)
                }
            },
        )
    } else if (dark) {
        base.withHsl(
            saturation = { it.coerceIn(0.22f, 0.66f) },
            lightness = { current -> current.coerceIn(0.15f, 0.28f) },
        )
    } else {
        base.withHsl(
            saturation = { it.coerceIn(0.14f, 0.50f) },
            lightness = { current -> current.coerceIn(0.76f, 0.92f) },
        )
    }

    val elevated = if (monochrome) {
        base.withHsl(
            lightness = { current ->
                if (baseLuminance > 0.48f) {
                    (current - 0.10f).coerceAtLeast(0f)
                } else {
                    (current + 0.10f).coerceAtMost(1f)
                }
            },
        )
    } else if (dark) {
        base.withHsl(
            saturation = { it.coerceIn(0.24f, 0.68f) },
            lightness = { 0.22f },
        )
    } else {
        base.withHsl(
            saturation = { it.coerceIn(0.16f, 0.54f) },
            lightness = { 0.83f },
        )
    }

    val accent = if (monochrome) {
        on
    } else if (on == Color.White) {
        highlight.withHsl(
            saturation = { it.coerceAtLeast(0.55f) },
            lightness = { it.coerceIn(0.62f, 0.78f) },
        )
    } else {
        highlight.withHsl(
            saturation = { it.coerceAtLeast(0.55f) },
            lightness = { it.coerceIn(0.30f, 0.44f) },
        )
    }

    return ArtworkPalette(
        background = background,
        wash = wash,
        elevated = elevated,
        accent = accent,
        onBackground = on,
        onBackgroundVariant = on.copy(alpha = if (on == Color.White) 0.80f else 0.72f),
        divider = on.copy(alpha = if (on == Color.White) 0.12f else 0.10f),
        statusBarProfile = statusBarProfile,
    )
}

private fun Seed.toBetaPlayerMatchedPalette(dark: Boolean): ArtworkPalette {
    val rawPlayerBase = if (monochrome) {
        dominant.preserveMonochromeExtremeForPlayer()
    } else {
        weightedBlend(
            colors = listOf(
                highlight.tunedForPlayerBase(),
                edge.tunedForPlayerBase(),
                spatial.tunedForPlayerBase(),
            ),
            weights = listOf(0.45f, 0.30f, 0.25f),
        )
    }

    // Foreground and surface are chosen together.
    //
    // A pure "whichever of black/white has the larger contrast ratio" rule is
    // technically defensible but visually harsh on strongly chromatic artwork:
    // cyan, orange, magenta and medium blue frequently end up with black text.
    // For an immersive music surface, those colours read better as a darkened
    // colour field with white typography. Very light/pastel/neutral surfaces
    // still keep black.
    val playerOn = preferredPlayerForeground(rawPlayerBase)
    val playerBase = if (playerOn == Color.White) {
        rawPlayerBase.ensureWhiteContrast(PLAYER_WHITE_MIN_CONTRAST)
    } else {
        rawPlayerBase
    }

    // Beta detail pages are intentionally theme-independent.
    // Album / playlist / artist pages always use the same 30% black overlay,
    // regardless of whether the app/system theme is light or dark.
    val background = lerp(
        playerBase,
        Color.Black,
        BETA_DARK_PAGE_OVERLAY,
    )

    // Keep the artwork-to-page transition softer than the final page surface,
    // but use the same dark-direction treatment in both themes.
    val wash = lerp(
        playerBase,
        Color.Black,
        BETA_DARK_WASH_OVERLAY,
    )

    val on = bestContrastingText(background)

    val elevated = if (on == Color.White) {
        lerp(background, Color.White, 0.10f)
    } else {
        lerp(background, Color.Black, 0.08f)
    }

    val accentCandidate = if (monochrome) {
        on
    } else if (on == Color.White) {
        highlight.withHsl(
            saturation = { it.coerceAtLeast(0.55f) },
            lightness = { it.coerceIn(0.62f, 0.78f) },
        )
    } else {
        highlight.withHsl(
            saturation = { it.coerceAtLeast(0.55f) },
            lightness = { it.coerceIn(0.30f, 0.44f) },
        )
    }

    val accent = if (accentCandidate.contrastRatio(background) >= BETA_ACCENT_MIN_CONTRAST) {
        accentCandidate
    } else {
        on
    }

    val playerAccent = if (
        accentCandidate.contrastRatio(playerBase) >= BETA_ACCENT_MIN_CONTRAST
    ) {
        accentCandidate
    } else {
        playerOn
    }

    return ArtworkPalette(
        background = background,
        wash = wash,
        elevated = elevated,
        accent = accent,
        onBackground = on,
        onBackgroundVariant = on.copy(alpha = if (on == Color.White) 0.80f else 0.72f),
        divider = on.copy(alpha = if (on == Color.White) 0.12f else 0.10f),

        // No light/dark overlay in Now Playing. The same artwork therefore
        // produces exactly the same player in both app themes.
        playerBackground = playerBase,
        playerOnBackground = playerOn,
        playerAccent = playerAccent,
        statusBarProfile = statusBarProfile,
    )
}

private fun Color.tunedForPlayerBase(): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

    if (hsl[1] < 0.12f) {
        hsl[2] = hsl[2].coerceIn(0.01f, 0.99f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    hsl[1] = (hsl[1] * 1.18f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.10f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.preserveMonochromeExtremeForPlayer(): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

    if (hsl[1] >= 0.12f) return tunedForPlayerBase()

    hsl[2] = when {
        hsl[2] <= 0.10f -> 0.02f
        hsl[2] >= 0.78f -> hsl[2].coerceAtLeast(0.92f)
        else -> hsl[2]
    }

    return Color(ColorUtils.HSLToColor(hsl))
}

private const val BETA_DARK_PAGE_OVERLAY = 0.30f
private const val BETA_DARK_WASH_OVERLAY = 0.18f
private const val BETA_ACCENT_MIN_CONTRAST = 3.0f

private fun Bitmap.dominantColor(): Color? {
    val swatches = Palette.from(this)
        .maximumColorCount(SWATCH_COUNT)
        .clearFilters()
        .generate()
        .swatches

    return swatches.maxByOrNull { it.population }?.let { Color(it.rgb) }
}

private fun Bitmap.isNearlyMonochrome(): Boolean {
    val swatches = Palette.from(this)
        .maximumColorCount(SWATCH_COUNT)
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

private fun Color.preserveMonochromeExtreme(): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

    if (hsl[1] >= 0.12f) return this

    hsl[2] = when {
        hsl[2] <= 0.22f -> hsl[2].coerceAtMost(0.06f)
        hsl[2] >= 0.78f -> hsl[2].coerceAtLeast(0.94f)
        else -> hsl[2]
    }

    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.withHsl(
    saturation: (Float) -> Float = { it },
    lightness: (Float) -> Float = { it },
): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    hsl[1] = saturation(hsl[1]).coerceIn(0f, 1f)
    hsl[2] = lightness(hsl[2]).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.contrastRatio(other: Color): Float {
    val a = luminance()
    val b = other.luminance()
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun bestContrastingText(background: Color): Color {
    val whiteContrast = Color.White.contrastRatio(background)
    val blackContrast = Color.Black.contrastRatio(background)
    return if (whiteContrast >= blackContrast) Color.White else Color.Black
}

/**
 * Visual foreground policy for the artwork-driven player/detail surface.
 *
 * Bright surfaces stay black. Dark surfaces stay white. Strong chromatic
 * mid-tones prefer white even when black wins the raw WCAG comparison by a
 * small amount; [ensureWhiteContrast] then darkens the colour itself just
 * enough to keep body text accessible.
 */
private fun preferredPlayerForeground(background: Color): Color {
    val hsl = FloatArray(3).also {
        ColorUtils.colorToHSL(background.toArgb(), it)
    }
    val saturation = hsl[1]
    val lightness = hsl[2]

    return when {
        lightness >= 0.74f -> Color.Black
        lightness <= 0.42f -> Color.White
        saturation >= 0.22f && lightness <= 0.70f -> Color.White
        else -> bestContrastingText(background)
    }
}

/**
 * Preserves hue/saturation and only moves the surface toward black until white
 * reaches the requested contrast. The smallest successful step is used so the
 * artwork identity changes as little as possible.
 */
private fun Color.ensureWhiteContrast(minContrast: Float): Color {
    if (Color.White.contrastRatio(this) >= minContrast) return this

    var low = 0f
    var high = 1f
    var best = Color.Black

    repeat(14) {
        val amount = (low + high) / 2f
        val candidate = lerp(this, Color.Black, amount)
        if (Color.White.contrastRatio(candidate) >= minContrast) {
            best = candidate
            high = amount
        } else {
            low = amount
        }
    }

    return best
}

private const val PLAYER_WHITE_MIN_CONTRAST = 4.5f

/** Perceived brightness, used only to tell a dark theme from a light one. */
private fun Color.luminance(): Float = ColorUtils.calculateLuminance(toArgb()).toFloat()
