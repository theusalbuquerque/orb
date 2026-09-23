@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * The run the fade needs below the bar to get from full blur to none without
 * the eye finding where it got there.
 */
private val FADE_RUN = 120.dp

/** The bar's own height, above the status bar inset — see [FrostedTopBar]. */
private val BAR_HEIGHT = 52.dp

/**
 * Default peak intensity for surfaces that do not request a custom value.
 *
 * Kept at the previous value so existing callers preserve their current look.
 * Detail pages can now pass a much lower value without affecting Home or
 * other surfaces that also use [TopFadeBlur].
 */
private const val DEFAULT_PEAK_INTENSITY = 0.75f

/**
 * [BottomFadeBlur] the other way up: full blur along the top edge, ramping to
 * nothing on the way down.
 *
 * For pages whose own artwork runs up under the status bar. A bar that carries
 * a uniform pane of glass is a rectangle sitting on the picture, and its bottom
 * edge is a line across it — the same line this page spends its whole effort
 * removing further down. Fading the glass out instead leaves the back arrow
 * something to be legible against and the artwork nothing to be interrupted by.
 *
 * The bar itself paints no glass of its own on those pages; this is the whole
 * of it. See `ownBackdrop` on [FrostedTopBar].
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TopFadeBlur(
    hazeState: HazeState,
    /**
     * The colour of the page behind this — a detail page's artwork wash.
     */
    pageColor: Color,
    modifier: Modifier = Modifier,

    /**
     * Maximum blur intensity at the very top of the screen.
     *
     * 0f = no haze.
     * 1f = full material intensity.
     *
     * The default preserves the previous appearance for existing callers.
     */
    peakIntensity: Float = DEFAULT_PEAK_INTENSITY,
) {
    val inset = WindowInsets.statusBarsIgnoringVisibility
        .asPaddingValues()
        .calculateTopPadding()

    val safePeak = peakIntensity.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(inset + BAR_HEIGHT + FADE_RUN)
            .hazeEffect(
                state = hazeState,
                style = HazeMaterials.ultraThin(pageColor),
            ) {
                progressive = HazeProgressive.verticalGradient(
                    easing = EaseOutCubic,
                    startIntensity = safePeak,
                    endIntensity = 0f,
                )

                noiseFactor = 0f
            },
    )
}