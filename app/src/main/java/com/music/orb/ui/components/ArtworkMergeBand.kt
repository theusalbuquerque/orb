package com.music.orb.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// Both detail and profile pages use this exact physical blur band and mask.
val ARTWORK_MERGE_BAND = 360.dp
val ARTWORK_MERGE_BLUR = 44.dp
val ARTWORK_MERGE_OVERLAP = ARTWORK_MERGE_BAND * 0.46f

@Composable
fun ArtworkMergeBand(
    surfaceColor: Color,
    hazeState: HazeState,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) return
    Box(
        modifier.hazeEffect(hazeState) {
            canDrawArea = { true }
            blurRadius = ARTWORK_MERGE_BLUR
            noiseFactor = 0f
            tints = listOf(HazeTint(Color.Transparent))
            backgroundColor = surfaceColor
            mask = if (horizontal) {
                Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    0.10f to Color.Black.copy(alpha = 0.18f),
                    0.26f to Color.Black.copy(alpha = 0.72f),
                    0.42f to Color.Black,
                    0.72f to Color.Black,
                    0.90f to Color.Black.copy(alpha = 0.46f),
                    1.00f to Color.Transparent,
                )
            } else {
                Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.10f to Color.Black.copy(alpha = 0.18f),
                    0.26f to Color.Black.copy(alpha = 0.72f),
                    0.42f to Color.Black,
                    0.72f to Color.Black,
                    0.90f to Color.Black.copy(alpha = 0.46f),
                    1.00f to Color.Transparent,
                )
            }
        },
    )
}

// A broad, continuous crossfade keeps landscape artwork from ending at a hard seam.
val ARTWORK_LANDSCAPE_FLOW_WIDTH = 360.dp
val ARTWORK_LANDSCAPE_FLOW_OVERLAP = 180.dp

fun artworkLandscapeFlowMask(): Brush = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.15f to Color.White.copy(alpha = 0.06f),
    0.30f to Color.White.copy(alpha = 0.24f),
    0.50f to Color.White.copy(alpha = 0.60f),
    0.70f to Color.White.copy(alpha = 0.90f),
    0.85f to Color.White,
    1f to Color.White,
)

fun Modifier.landscapeArtworkFade(): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fadeWidth = ARTWORK_LANDSCAPE_FLOW_OVERLAP.toPx().coerceAtMost(size.width)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.White,
                0.25f to Color.White.copy(alpha = 0.84f),
                0.50f to Color.White.copy(alpha = 0.50f),
                0.75f to Color.White.copy(alpha = 0.16f),
                1f to Color.Transparent,
                startX = size.width - fadeWidth,
                endX = size.width,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
