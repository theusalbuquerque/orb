package com.music.orb.ui.components

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.settings.AppSettings
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * The strip the tab bar alone needs, above the gesture inset. Generous on
 * purpose: the ramp below spends most of its run at a blur too small to see,
 * and that long invisible lead-in is what hides where the layer begins.
 */
private val FADE_HEIGHT = 180.dp

/** Taller once the mini player is stacked on top of the tab bar. */
private val FADE_HEIGHT_WITH_MINI_PLAYER = 248.dp

/**
 * How much blur the fade reaches at its outer edge — short of all of it.
 *
 * The last quarter buys almost nothing visually and costs the most: a blur has
 * nothing to sample past the edge of its own layer, so the harder it is pushed
 * there the more of the layer is flat material colour rather than blurred
 * content, and the more that edge reads as a band of colour laid over the page.
 * Stopping at three quarters keeps the ramp and loses the band.
 */
private const val PEAK = 0.75f

/**
 * The frosted floor the floating bars sit on.
 *
 * A full-width pane of glass pinned to the bottom of the screen whose blur
 * ramps in from nothing at the top to full at the very bottom, so content
 * scrolling under the tab bar dissolves rather than sliding under a hard-edged
 * panel. The gradient is the feathering: the top edge has no blur and no tint
 * at all, which is what keeps the strip from reading as a rectangle stuck over
 * the feed. The side edges run to the screen edges, so they have no seam of
 * their own to soften.
 *
 * A step lighter than the bars that sit on it ([HazeMaterials.ultraThin]
 * against their `thin`), so the mini player and the tab pill still separate
 * from it rather than dissolving into one frosted mass.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BottomFadeBlur(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    withMiniPlayer: Boolean = false,
    /**
     * The colour of the page at the very foot of the screen: the theme's
     * background on a tab, and on a detail page the tint its wash settles into
     * down here rather than the wash itself. See the effect below for why it
     * cannot just be the theme's.
     */
    pageColor: Color = MaterialTheme.colorScheme.background,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    // The floating bars fill themselves solid instead when blur is reduced,
    // so this frosted floor underneath them has nothing left to do.
    if (reduceDynamicBlur) return

    // The gesture bar sits below the tab pill and wants blurring too, so it is
    // added on rather than being part of the fade's own run.
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val height by animateDpAsState(
        targetValue = inset + if (withMiniPlayer) FADE_HEIGHT_WITH_MINI_PLAYER else FADE_HEIGHT,
        // Matches the beat the mini player takes to appear, so the glass grows
        // with it instead of snapping ahead of it.
        animationSpec = tween(220),
        label = "bottomFadeHeight",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .hazeEffect(
                state = hazeState,
                // Keyed to the colour of the page underneath, not the theme's.
                //
                // Both halves of this material are flat colour: the style's
                // background is painted as an opaque rect under the sampled
                // content, and its tint is a film at 0.55 alpha in dark mode.
                // The progressive gradient reaches neither — it only ramps the
                // blur radius and the tint's alpha — so wherever the blur has
                // least to say, that flat colour is most of what is left. At
                // the very foot of the strip it is nearly all of it, because a
                // blur has nothing to sample past the bottom of its own layer.
                //
                // Given the theme's background that is invisible on a page
                // which *is* that colour, and a dark band on a page which
                // isn't — and a detail page is washed in its artwork's colour,
                // so it got a black bar under the tab bar. Handed the page's
                // own colour, the untouched edge is a copy of what it covers,
                // and there is no band on any page.
                style = HazeMaterials.ultraThin(pageColor),
            ) {
                // A cubic ease-in rather than haze's default quadratic one: the
                // ramp then holds under a few percent for the first half of the
                // strip, which is what stops the eye from finding the line
                // where the layer starts. Its whole run is spent arriving.
                progressive = HazeProgressive.verticalGradient(
                    easing = EaseInCubic,
                    startIntensity = 0f,
                    endIntensity = PEAK,
                )
                // Haze's film grain is uniform across the layer, so it shows up
                // at the top as texture over content that is otherwise
                // untouched — exactly the edge the gradient is hiding.
                noiseFactor = 0f
            },
    )
}
