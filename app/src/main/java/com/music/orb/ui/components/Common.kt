@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.LocalAudioQuality
import com.music.orb.download.DownloadState
import com.music.orb.download.Downloads
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AlbumRatingStore
import com.music.orb.data.social.CommunityRatingRepository
import com.music.orb.data.spotify.LinkedLikesSync
import com.music.orb.ui.flavor.OrbFlavorUi
import com.music.orb.data.sources.LosslessTier
import com.music.orb.data.sources.SourceKind
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.SourceResolver
import com.music.orb.data.sources.CollectionBadgeIndex
import kotlin.math.abs
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.orb.ui.components.OrbGlassButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.music.orb.R
import com.music.orb.ui.player.NowPlayingLaunchOriginRegistry

/**
 * Shared page-tinted translucent glass used by album/artist/playlist controls,
 * share surfaces, sheets and popups. Keeping overlays on this helper prevents
 * each dialog from drifting to its own opaque Material surface.
 */
/**
 * Orb Expressive Glass tone. The material stays mostly neutral so controls keep
 * one identity across artworks; the current page/artwork contributes only a
 * restrained tint. This is intentionally closer to frosted system glass than
 * to a translucent copy of the dominant artwork colour.
 */
@Composable
fun orbExpressiveGlassTone(
    pageColor: Color,
    artworkInfluence: Float = 0.16f,
): Color {
    val neutral = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f)
    return lerp(
        neutral,
        pageColor.copy(alpha = 1f),
        artworkInfluence.coerceIn(0f, 0.42f),
    )
}

@Composable
fun orbExpressiveGlassContentColor(
    pageColor: Color,
    artworkInfluence: Float = 0.16f,
): Color =
    artworkSurfaceForegrounds(
        orbExpressiveGlassTone(pageColor, artworkInfluence),
    ).primary

/**
 * Colour source for the *outer* frosted panel of an Orb bottom sheet.
 * ARTWORK is reserved for the song Action Button; BACKDROP lets the actual
 * blurred activity underneath supply the hue instead of a neutral colour slab.
 * Ordinary controls/badges keep the existing Expressive Glass material.
 */
/**
 * The exact lightweight veil used for transport controls in Now Playing.
 * The page already provides a cover-derived background; paint only a faint
 * light/dark veil using the adaptive foreground, not an opaque Material tone.
 * The caller owns clipping to the desired Expressive control shape.
 */
fun Modifier.orbNowPlayingStyleGlass(
    contentColor: Color,
    opacity: Float = 0.08f,
    highlight: Float = 0.025f,
): Modifier = this
    .background(contentColor.copy(alpha = opacity.coerceIn(0f, 0.22f)))
    .background(
        Brush.verticalGradient(
            colors = listOf(
                contentColor.copy(alpha = highlight.coerceIn(0f, 0.08f)),
                Color.Transparent,
            ),
        ),
    )

enum class OrbSheetTintMode { ARTWORK, BACKDROP }

fun Modifier.orbGlassSurface(
    pageColor: Color,
    cornerRadius: Dp? = null,
    drawRim: Boolean = true,
    backdropBlur: Dp = 56.dp,
    overlayStrength: Boolean = false,
    overlayOpacity: Float? = null,
    artworkInfluenceOverride: Float? = null,
    contentColorHint: Color? = null,
    observeTapFeedback: Boolean = true,
    manageDialogBackdrop: Boolean = true,
    // Use only for outer bottom-sheet surfaces. Leaves controls and badges intact.
    lightweightSheetMaterial: Boolean = false,
    sheetTintMode: OrbSheetTintMode? = null,
): Modifier = composed {
    // ModalBottomSheet / AlertDialog / Dialog live in their own Android window.
    // A translucent paint alone only lowers opacity; it cannot blur the activity
    // behind that window. Apply Android's real background blur whenever this
    // glass surface is hosted by a dialog window, while regular in-page controls
    // keep using the same glass paint without touching the activity window.
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    val managedDialogWindow = dialogWindow.takeIf { manageDialogBackdrop }
    // V28 trial: an approximately 10% stronger frost only on bottom-sheet
    // panels. This changes the blur diffusion, not the 10% outside scrim or
    // the tint/color of ordinary glass buttons and album badges.
    val effectiveBlur = if (sheetTintMode != null) maxOf(backdropBlur, 92.dp)
        else if (lightweightSheetMaterial) maxOf(backdropBlur, 84.dp)
        else backdropBlur
    val blurPx = with(density) { effectiveBlur.roundToPx() }

    // AlertDialog/Dialog surfaces do not call RegisterSheetBackdrop themselves.
    // When this glass modifier is hosted by a dialog window, register it here so
    // the activity content behind the window receives the same real blur used by
    // the song Action Button sheet. Regular in-page glass has no dialogWindow and
    // therefore never affects the root backdrop.
    DisposableEffect(managedDialogWindow, context) {
        if (managedDialogWindow == null) {
            onDispose { }
        } else {
            SheetBackdropRegistry.opened()
            OrbHaptics.overlayEnter(context)
            val previousAnimations = managedDialogWindow.attributes.windowAnimations
            managedDialogWindow.attributes = managedDialogWindow.attributes.apply {
                windowAnimations = R.style.OrbOverlayWindowAnimation
            }
            onDispose {
                OrbHaptics.overlayExit(context)
                managedDialogWindow.attributes = managedDialogWindow.attributes.apply {
                    windowAnimations = previousAnimations
                }
                SheetBackdropRegistry.closed()
            }
        }
    }

    DisposableEffect(managedDialogWindow, blurPx) {
        if (managedDialogWindow == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onDispose { }
        } else {
            val oldBlurBehind = managedDialogWindow.attributes.blurBehindRadius
            val hadBlurFlag = managedDialogWindow.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0

            managedDialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            managedDialogWindow.attributes = managedDialogWindow.attributes.apply {
                blurBehindRadius = maxOf(blurBehindRadius, blurPx)
            }
            runCatching { managedDialogWindow.setBackgroundBlurRadius(blurPx) }

            onDispose {
                managedDialogWindow.attributes = managedDialogWindow.attributes.apply {
                    blurBehindRadius = oldBlurBehind
                }
                runCatching { managedDialogWindow.setBackgroundBlurRadius(0) }
                if (!hadBlurFlag && oldBlurBehind == 0) {
                    managedDialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
            }
        }
    }

    // Dialog sheets contain interactive controls and scrollable lists. An outer
    // pointer observer can race their first tap; leave haptics to the actual
    // clickable row/button, not to its glass background.
    val receivesOverlayFeedback = observeTapFeedback && overlayStrength && managedDialogWindow == null
    val tapFeedbackModifier = if (receivesOverlayFeedback) {
        Modifier.pointerInput(context) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!change.pressed) {
                        if (!moved) OrbHaptics.overlayTap(context)
                        break
                    }
                }
            }
        }
    } else Modifier

    // Expressive Glass: neutral material first, artwork tint second. The stronger
    // backdrop blur carries depth; the paint itself stays translucent enough to
    // reveal context, with a soft internal highlight instead of a coloured slab.
    val artworkInfluence = artworkInfluenceOverride
        ?.coerceIn(0f, 0.42f)
        ?: if (overlayStrength || managedDialogWindow != null) 0.18f else 0.14f
    val glassTone = orbExpressiveGlassTone(pageColor, artworkInfluence)

    this.then(tapFeedbackModifier).drawWithCache {
        val radius = CornerRadius(cornerRadius?.toPx() ?: (size.minDimension / 2f))
        // Outer sheets must never be a grey neutral plate. ARTWORK uses the
        // extracted song colour; BACKDROP is almost colourless so the real
        // blurred surface beneath it determines the resulting colour.
        if (sheetTintMode != null) {
            val requestedOpacity = overlayOpacity?.coerceIn(0f, 1f)
            val tint = when (sheetTintMode) {
                OrbSheetTintMode.ARTWORK -> pageColor.copy(alpha = requestedOpacity ?: 0.34f)
                // Most of the perceived colour comes from the blurred content
                // underneath, not from a grey Material surface painted over it.
                // +10% *relative* frost: keep most colour coming from the live
                // blurred screen, not from a new white/grey overlay.
                OrbSheetTintMode.BACKDROP -> pageColor.copy(alpha = (requestedOpacity ?: 0.40f) * 0.275f)
            }
            val lightTheme = pageColor.luminance() > 0.5f
            // The shared sheet perimeter is perceptible on blurred artwork,
            // without changing the translucent material or individual controls.
            val rim = if (lightTheme) Color.Black.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.25f)
            val rimWidth = 0.9.dp.toPx()
            onDrawBehind {
                // The real blurred backdrop owns the light and colour. An
                // extra bright gradient here made the glass appear to glow
                // independently of the content underneath.
                drawRoundRect(tint, cornerRadius = radius)
                if (drawRim) drawRoundRect(rim, cornerRadius = radius, style = Stroke(rimWidth))
            }
        } else {
        val opaqueTone = glassTone.copy(alpha = 1f)
        val overlay = overlayStrength || managedDialogWindow != null
        val requestedAlpha = overlayOpacity?.coerceIn(0f, 1f)
            ?: if (lightweightSheetMaterial) 0.40f else if (overlay) 0.56f else 0.43f
        // Only legacy sheet panels without an explicit ARTWORK/BACKDROP mode.
        // Never frost nested controls, in-page cards or the ordinary badges.
        val baseAlpha = if (lightweightSheetMaterial) (requestedAlpha * 1.08f).coerceAtMost(1f)
            else requestedAlpha
        val base = opaqueTone.copy(alpha = baseAlpha)

        // A tiny neutral separation layer keeps the glass readable on both very
        // bright and very dark artwork without reintroducing a strong colour wash.
        val separator = (if (opaqueTone.luminance() >= 0.58f) Color.Black else Color.White)
            .copy(alpha = if (lightweightSheetMaterial) 0.016f else if (overlay) 0.035f else 0.045f)
        val foreground = contentColorHint ?: artworkSurfaceForegrounds(opaqueTone).primary

        fun contrast(surface: Color): Float {
            val a = foreground.luminance()
            val b = surface.luminance()
            return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        }

        val materialSurface = separator.compositeOver(base.compositeOver(opaqueTone))
        val shade = if (foreground.luminance() > 0.5f) Color.Black else Color.White
        fun readable(alpha: Float): Boolean =
            contrast(shade.copy(alpha = alpha).compositeOver(materialSurface)) >= 4.5f

        var contrastLayer = Color.Transparent
        if (!readable(0f)) {
            var low = 0f
            var high = 0.62f
            repeat(12) {
                val middle = (low + high) / 2f
                if (readable(middle)) high = middle else low = middle
            }
            contrastLayer = shade.copy(alpha = high)
        }

        // The highlight is deliberately achromatic: artwork owns atmosphere,
        // while the glass itself reads as one consistent Orb material.
        val highlightInk = if (opaqueTone.luminance() < 0.52f) Color.White else Color.Black
        val innerHighlight = Brush.linearGradient(
            colors = listOf(
                highlightInk.copy(alpha = if (lightweightSheetMaterial) 0.052f else if (overlay) 0.115f else 0.095f),
                highlightInk.copy(alpha = if (lightweightSheetMaterial) 0.012f else 0.030f),
                Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width * 0.82f, size.height),
        )
        val lowerShadeInk = if (opaqueTone.luminance() < 0.52f) Color.Black else Color.White
        val lowerShade = Brush.verticalGradient(
            colors = listOf(Color.Transparent, lowerShadeInk.copy(alpha = if (lightweightSheetMaterial) 0.012f else 0.035f)),
            startY = size.height * 0.42f,
            endY = size.height,
        )
        val rimColor = foreground.copy(alpha = if (overlay) 0.105f else 0.085f)
        val stroke = 0.5.dp.toPx()

        onDrawBehind {
            drawRoundRect(base, cornerRadius = radius)
            drawRoundRect(separator, cornerRadius = radius)
            drawRoundRect(contrastLayer, cornerRadius = radius)
            drawRoundRect(innerHighlight, cornerRadius = radius)
            drawRoundRect(lowerShade, cornerRadius = radius)
            if (drawRim) drawRoundRect(rimColor, cornerRadius = radius, style = Stroke(stroke))
        }
        }
    }
}

@Composable
fun OrbGlassContentTheme(
    pageColor: Color,
    content: @Composable () -> Unit,
) {
    val foregrounds = artworkSurfaceForegrounds(orbExpressiveGlassTone(pageColor))
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            onBackground = foregrounds.primary,
            onSurface = foregrounds.primary,
            onSurfaceVariant = foregrounds.secondary,
            onPrimaryContainer = foregrounds.primary,
            onSecondaryContainer = foregrounds.primary,
            onTertiaryContainer = foregrounds.primary,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides foregrounds.primary) {
            content()
        }
    }
}

/**
 * Shared inner surface for ordinary ModalBottomSheets. The sheet window itself
 * stays transparent; the actual frosted material is painted around the content
 * so it can never disappear because a ModalBottomSheet ignores/modifies its
 * outer modifier. The drag handle is inside the glass as well.
 */
@Composable
fun OrbGlassSheetSurface(
    pageColor: Color,
    cornerRadius: Dp = 36.dp,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val foregrounds = artworkSurfaceForegrounds(orbExpressiveGlassTone(pageColor))
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "orbGlassSheetEntrance",
    )
    OrbGlassContentTheme(pageColor) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = 0.82f + 0.18f * entrance
                    translationY = (1f - entrance) * 18.dp.toPx()
                    val scale = 0.985f + 0.015f * entrance
                    scaleX = scale
                    scaleY = scale
                }
                .orbSheetPanelElevation(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                .orbGlassSurface(
                    pageColor = pageColor,
                    cornerRadius = cornerRadius,
                    backdropBlur = 56.dp,
                    overlayStrength = true,
                    overlayOpacity = 0.40f,
                    lightweightSheetMaterial = true,
                    sheetTintMode = OrbSheetTintMode.BACKDROP,
                    observeTapFeedback = false,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(foregrounds.primary.copy(alpha = 0.42f)),
                )
            }
            content()
            // The same rendered glass extends behind Android's gesture handle.
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
fun orbOverlayGlassBaseColor(): Color = MaterialTheme.colorScheme.surfaceContainer

@Composable
fun orbOverlayGlassContentColor(pageColor: Color = orbOverlayGlassBaseColor()): Color =
    orbExpressiveGlassContentColor(pageColor)

fun Modifier.thumbnailBorder(shape: Shape): Modifier = composed {
    this.border(
        width = 1.dp,
        color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
        shape = shape
    )
}

/**
 * The left and right inset every page's content sits at.
 *
 * It is the same inset the mini player and the tab bar float at, so the edge of
 * a track row, a card or a heading lines up with the edge of the bars stacked
 * below them rather than stepping in from them. One constant, shared by the
 * bars and the pages, is what keeps that true.
 */
val PAGE_GUTTER = 10.dp

/** Where a divider under a track row starts: clear of the 52dp of artwork. */
val ROW_DIVIDER_INSET = PAGE_GUTTER + 68.dp

/**
 * Width of a card in the compact carousels — home shelves, library shelves and
 * the artist page's releases alike.
 *
 * Sized so a phone-width row shows two cards whole with the edge of a third
 * showing: enough to say the row scrolls without a card being half a card.
 */
val SHELF_CARD_WIDTH = 150.dp

/**
 * The exact user-facing lossless tier behind a badge.
 *
 * This is intentionally richer than a Boolean: flattening a proven 24/96
 * stream into `true` is how Home/Search used to lose the distinction the
 * player had already measured.
 */
data class LosslessBadgeState(
    val isLossless: Boolean = false,
    val isHiResLossless: Boolean = false,
    val isHiQuality: Boolean = false,
)

private const val HI_QUALITY_BADGE_LABEL = "Hi-Q Audio"

private fun LosslessTier.toBadgeState(): LosslessBadgeState =
    LosslessBadgeState(
        isLossless = isLossless,
        isHiResLossless = isHiRes,
    )

private fun SourceResolver.PlayableBadgeQuality.toBadgeState(): LosslessBadgeState =
    if (!resolved) {
        // A candidate/partially-known collection must never paint a quality
        // badge. The resolver only flips resolved after the stream/bitrate has
        // actually been verified, so every UI surface shares the same rule.
        LosslessBadgeState()
    } else {
        LosslessBadgeState(
            isLossless = losslessTier.isLossless,
            isHiResLossless = losslessTier.isHiRes,
            isHiQuality = hiQuality && !losslessTier.isLossless,
        )
    }


@Composable
fun HiQualityWordmark(
    color: Color,
    modifier: Modifier = Modifier,
    iconWidth: Dp = 19.dp,
    iconHeight: Dp = 12.dp,
    style: TextStyle = TextStyle(
        fontSize = 8.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        lineHeight = 8.5.sp,
    ),
) {
    val description = stringResource(R.string.hi_quality_audio_available)
    val badgeColor = color

    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_hi_q_audio_mark),
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(width = iconWidth, height = iconHeight),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = HI_QUALITY_BADGE_LABEL,
            color = badgeColor,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Context-aware Lossless wordmark used everywhere in the app. The ribbon is a
 * VectorDrawable traced from the reference supplied for Orb; icon and text use
 * the same dynamic content colour as the Explicit E on the current surface.
 */
@Composable
fun LosslessWordmark(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    iconWidth: Dp = 19.dp,
    iconHeight: Dp = 12.dp,
    style: TextStyle = TextStyle(
        fontSize = 8.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        lineHeight = 8.5.sp,
    ),
) {
    // Use the caller's contextual content colour exactly. Album/playlist
    // surfaces already derive this colour from the artwork/background, and
    // the Explicit E receives the very same value. Do not collapse it to
    // black/white here: doing so makes Lossless diverge from the dynamic E.
    val badgeColor = color

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lossless_wave),
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(width = iconWidth, height = iconHeight),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = badgeColor,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shared translucent material for Explicit/rating badges across album, Home, Library and Search.
 * It intentionally mirrors the album glass material instead of falling back to a solid container. */
fun Modifier.orbBadgeGlassSurface(
    pageColor: Color,
    cornerRadius: Dp = 5.dp,
    drawRim: Boolean = false,
    contentColorHint: Color? = null,
    transparentLikeNowPlaying: Boolean = false,
): Modifier = composed {
    // Badges are miniature Expressive Glass, not tiny opaque labels. Keep the
    // same neutral-first recipe used by buttons/sheets and reduce only the
    // physical depth so 14–18dp seals stay crisp instead of looking muddy.
    val glassTone = orbExpressiveGlassTone(pageColor, artworkInfluence = 0.12f)
    // A release-page seal must use the same foreground as the neighbouring
    // playback/action buttons. Without this hint, a light artwork page can
    // end up with dark button glyphs and white badge lettering.
    val foreground = contentColorHint ?: artworkSurfaceForegrounds(glassTone).primary
    if (transparentLikeNowPlaying) {
        this.orbNowPlayingStyleGlass(foreground)
    } else this.drawWithCache {
        val radius = CornerRadius(cornerRadius.toPx())
        val opaqueTone = glassTone.copy(alpha = 1f)
        val base = opaqueTone.copy(alpha = 0.40f)
        val separatorInk = if (opaqueTone.luminance() >= 0.58f) Color.Black else Color.White
        val separator = separatorInk.copy(alpha = 0.040f)
        val highlightInk = if (opaqueTone.luminance() < 0.52f) Color.White else Color.Black
        val highlight = Brush.linearGradient(
            colors = listOf(
                highlightInk.copy(alpha = 0.105f),
                highlightInk.copy(alpha = 0.026f),
                Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width * 0.82f, size.height),
        )
        val lowerShadeInk = if (opaqueTone.luminance() < 0.52f) Color.Black else Color.White
        val lowerShade = Brush.verticalGradient(
            colors = listOf(Color.Transparent, lowerShadeInk.copy(alpha = 0.030f)),
            startY = size.height * 0.44f,
            endY = size.height,
        )
        val rimColor = foreground.copy(alpha = 0.095f)
        val stroke = 0.5.dp.toPx()
        onDrawBehind {
            drawRoundRect(base, cornerRadius = radius)
            drawRoundRect(separator, cornerRadius = radius)
            drawRoundRect(highlight, cornerRadius = radius)
            drawRoundRect(lowerShade, cornerRadius = radius)
            if (drawRim) drawRoundRect(rimColor, cornerRadius = radius, style = Stroke(stroke))
        }
    }
}


/**
 * The compact Explicit seal owns its material inside the vector frame.
 * The outer layout box is transparent, so no glass plate can extend past the
 * seal's own rounded border.
 */
@Composable
fun ExplicitBadgeSeal(
    pageColor: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    contentColorOverride: Color? = null,
    transparentLikeNowPlaying: Boolean = false,
) {
    val description = stringResource(R.string.explicit_content)
    val contentColor = contentColorOverride ?: orbExpressiveGlassContentColor(pageColor)
    val radius = size * (5f / 18f)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .orbBadgeGlassSurface(
                pageColor = pageColor,
                cornerRadius = radius,
                drawRim = false,
                contentColorHint = contentColor,
                transparentLikeNowPlaying = transparentLikeNowPlaying,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "E",
            color = contentColor,
            style = TextStyle(
                fontSize = 9.25.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.sp,
                lineHeight = 9.25.sp,
            ),
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * Release-level badges shown below album/single/EP metadata. Explicit uses the
 * same compact E everywhere; its glass and foreground are derived from the
 * contextual page/player surface so the badge follows Material 3 Expressive
 * color rather than owning a fixed palette.
 */
@Composable
fun ReleaseBadges(
    isExplicit: Boolean,
    isLossless: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    isHiResLossless: Boolean = false,
    isHiQuality: Boolean = false,
    plainExplicit: Boolean = false,
    glassPageColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    transparentLikeNowPlaying: Boolean = false,
) {
    if (!isExplicit && !isLossless && !isHiQuality) return
    @Suppress("UNUSED_VARIABLE")
    val keepSourceCompatibility = plainExplicit
    val losslessDescription = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_available else R.string.lossless_available,
    )
    val losslessLabel = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_badge else R.string.lossless_badge,
    )
    val arrangement = if (centered) {
        Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
    } else {
        Arrangement.spacedBy(5.dp)
    }
    // Reuse the exact colour provided by the release page; it is also the
    // foreground used by its Play, shuffle, share and overflow controls.
    val badgeContentColor = color

    Row(
        modifier = modifier,
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isExplicit) {
            ExplicitBadgeSeal(
                pageColor = glassPageColor,
                size = 14.5.dp,
                contentColorOverride = badgeContentColor,
                transparentLikeNowPlaying = transparentLikeNowPlaying,
            )
        }
        if (isLossless || isHiQuality) {
            Row(
                modifier = Modifier
                    .height(14.5.dp)
                    .clip(RoundedCornerShape(4.5.dp))
                    .orbBadgeGlassSurface(
                        glassPageColor,
                        cornerRadius = 4.5.dp,
                        contentColorHint = badgeContentColor,
                        transparentLikeNowPlaying = transparentLikeNowPlaying,
                    )
                    .padding(horizontal = 4.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLossless) {
                    LosslessWordmark(
                        text = losslessLabel,
                        color = badgeContentColor,
                        iconWidth = 15.5.dp,
                        iconHeight = 10.dp,
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            lineHeight = 8.5.sp,
                        ),
                        modifier = Modifier
                            .height(14.5.dp)
                            .semantics { contentDescription = losslessDescription },
                    )
                } else {
                    HiQualityWordmark(
                        color = badgeContentColor,
                        iconWidth = 15.5.dp,
                        iconHeight = 10.dp,
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            lineHeight = 8.5.sp,
                        ),
                        modifier = Modifier.height(14.5.dp),
                    )
                }
            }
        }
    }
}

/** Album/single/EP rating badge shared by album cards and release pages. */
@Composable
fun AlbumRatingBadge(
    albumId: String?,
    glassPageColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier: Modifier = Modifier,
    contentColorOverride: Color? = null,
    transparentLikeNowPlaying: Boolean = false,
) {
    if (albumId.isNullOrBlank()) return
    // Public-facing seal shows the community mean, not the viewer's own vote.
    // It includes registered editions and IDs linked in Other versions.
    val revision by AlbumRatingStore.revision.collectAsStateWithLifecycle()
    val rating by produceState<Float?>(initialValue = null, key1 = albumId, key2 = revision) {
        value = runCatching { CommunityRatingRepository.albumAverage(albumId) }.getOrNull()
    }
    val contentColor = contentColorOverride ?: orbExpressiveGlassContentColor(glassPageColor)
    Row(
        modifier = modifier
            .height(14.5.dp)
            .clip(RoundedCornerShape(5.dp))
            .orbBadgeGlassSurface(
                glassPageColor,
                cornerRadius = 5.dp,
                contentColorHint = contentColor,
                transparentLikeNowPlaying = transparentLikeNowPlaying,
            )
            .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = rating?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = contentColor,
        )
    }
}


@Composable
private fun ReleaseExplicitWordmark(
    color: Color,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
) {
    val label = stringResource(R.string.explicit_badge_full).uppercase()
    val description = stringResource(R.string.explicit_content)
    Box(
        modifier = modifier.height(14.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = TextStyle(
                fontSize = 8.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.sp,
                lineHeight = 8.5.sp,
            ),
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** Compact title-row badges used by Search and local-device tracks. The
 * universal E marker and the exact Lossless tier stay next to the media title
 * rather than becoming detached metadata at the row edge. */
@Composable
fun CompactMediaBadges(
    isExplicit: Boolean,
    isLossless: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    isHiResLossless: Boolean = false,
    isHiQuality: Boolean = false,
    glassPageColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    transparentLikeNowPlaying: Boolean = false,
) {
    if (!isExplicit && !isLossless && !isHiQuality) return
    val losslessDescription = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_available else R.string.lossless_available,
    )
    val losslessLabel = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_badge else R.string.lossless_badge,
    )
    val badgeContentColor = color
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isExplicit) {
            ExplicitBadgeSeal(
                pageColor = glassPageColor,
                size = 14.5.dp,
                contentColorOverride = badgeContentColor,
                transparentLikeNowPlaying = transparentLikeNowPlaying,
            )
        }
        if (isLossless || isHiQuality) {
            Row(
                modifier = Modifier
                    .height(14.5.dp)
                    .clip(RoundedCornerShape(4.5.dp))
                    .orbBadgeGlassSurface(
                        glassPageColor,
                        cornerRadius = 4.5.dp,
                        contentColorHint = badgeContentColor,
                        transparentLikeNowPlaying = transparentLikeNowPlaying,
                    )
                    .padding(horizontal = 4.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLossless) {
                    LosslessWordmark(
                        text = losslessLabel,
                        color = badgeContentColor,
                        iconWidth = 15.5.dp,
                        iconHeight = 10.dp,
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            lineHeight = 8.5.sp,
                        ),
                        modifier = Modifier
                            .height(14.5.dp)
                            .semantics { contentDescription = losslessDescription },
                    )
                } else {
                    HiQualityWordmark(
                        color = badgeContentColor,
                        iconWidth = 15.5.dp,
                        iconHeight = 10.dp,
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            lineHeight = 8.5.sp,
                        ),
                        modifier = Modifier.height(14.5.dp),
                    )
                }
            }
        }
    }
}

/**
 * Exact lossless tier for one track.
 *
 * Local files are special: their badge must describe the bytes the listener
 * owns, so it is read from FLAC/WAV/container metadata and does not depend on
 * the online "Lossless audio" preference. Network tracks keep the existing
 * verified-source probe, but retain the stream's native Hi-Res tier.
 */
@Composable
fun rememberTrackLosslessBadgeState(
    song: Song,
    allowProbe: Boolean = true,
): LosslessBadgeState {
    val local = !song.localUri.isNullOrBlank() || !song.localPath.isNullOrBlank()
    val context = LocalContext.current
    // The Home cards are usually online catalogue Songs; they do not carry
    // localUri even if that same track is downloaded. Observe the saved map so
    // the badge updates immediately when a new offline rendition is committed.
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val downloadedFormat = if (song.videoId in savedDownloads) {
        Downloads.savedQuality(song.videoId)
    } else null
    if (downloadedFormat != null) {
        val offlineTier = downloadedFormat.losslessTier
        return offlineTier.toBadgeState().copy(
            isHiQuality = !offlineTier.isLossless && (downloadedFormat.kbps ?: 0) >= 256,
        )
    }

    if (local) {
        val cachedLocal = LocalAudioQuality.cached(song)
        var localState by remember(song.videoId, song.localUri, song.localPath, cachedLocal) {
            mutableStateOf(cachedLocal?.losslessTier?.toBadgeState() ?: LosslessBadgeState())
        }
        LaunchedEffect(song.videoId, song.localUri, song.localPath, cachedLocal) {
            val format = cachedLocal ?: withContext(Dispatchers.IO) {
                LocalAudioQuality.inspect(context, song)
            }
            localState = format?.losslessTier?.toBadgeState() ?: LosslessBadgeState()
            if (!localState.isLossless && (format?.kbps ?: 0) >= 256) {
                localState = localState.copy(isHiQuality = true)
            }
        }
        return localState.copy(
            isHiQuality = !localState.isLossless && ((cachedLocal?.kbps ?: 0) >= 256 || localState.isHiQuality),
        )
    }

    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val badgeCacheRevision by SourceResolver.badgeCacheRevision.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val wifiMaximum by AppSettings.audioQualityWifiMaximum.collectAsStateWithLifecycle()
    val losslessEnabled by AppSettings.losslessAudio.collectAsStateWithLifecycle()
    val onWifi by AppSettings.wifiConnection.collectAsStateWithLifecycle()

    var state by remember(
        song.videoId, song.title, song.artist, badgeCacheRevision,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        mutableStateOf(SourceResolver.cachedTrackPlayableBadge(song).toBadgeState())
    }

    LaunchedEffect(
        song.videoId, song.title, song.artist, allowProbe,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        state = SourceResolver.cachedTrackPlayableBadge(song).toBadgeState()
    }

    LaunchedEffect(badgeCacheRevision) {
        state = SourceResolver.cachedTrackPlayableBadge(song).toBadgeState()
    }
    return state
}

/** Backwards-compatible Boolean view for callers that do not paint a tier. */
@Composable
fun rememberTrackLosslessAvailable(song: Song, allowProbe: Boolean = true): Boolean =
    rememberTrackLosslessBadgeState(song, allowProbe).isLossless

/**
 * Exact release-level quality state. A collection is only promoted to
 * Hi-Res Lossless after SourceResolver has proven its collection-level Hi-Res
 * criteria; an unresolved/unknown resolution remains ordinary Lossless.
 */
@Composable
fun rememberReleaseLosslessBadgeState(
    title: String,
    subtitle: String,
    isRelease: Boolean,
    knownSongs: List<Song> = emptyList(),
    browseId: String? = null,
    allowProbe: Boolean = true,
): LosslessBadgeState {
    if (!isRelease) return LosslessBadgeState()

    val badgeCacheRevision by SourceResolver.badgeCacheRevision.collectAsStateWithLifecycle()
    // A completed download can change a collection's three-track threshold
    // even if no playback source was resolved during this session.
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val wifiMaximum by AppSettings.audioQualityWifiMaximum.collectAsStateWithLifecycle()
    val losslessEnabled by AppSettings.losslessAudio.collectAsStateWithLifecycle()
    val onWifi by AppSettings.wifiConnection.collectAsStateWithLifecycle()
    val songIdentityKey = remember(knownSongs) {
        knownSongs.joinToString("\u001f") { "${it.videoId}|${it.title}|${it.artist}" }
    }

    var state by remember(
        title, subtitle, browseId, songIdentityKey, badgeCacheRevision, savedDownloads,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        mutableStateOf(
            SourceResolver.cachedCollectionPlayableBadge(title, subtitle, browseId, knownSongs).toBadgeState(),
        )
    }

    LaunchedEffect(
        title, subtitle, browseId, songIdentityKey, allowProbe,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        state = SourceResolver.cachedCollectionPlayableBadge(title, subtitle, browseId, knownSongs).toBadgeState()
    }

    LaunchedEffect(badgeCacheRevision, savedDownloads) {
        state = SourceResolver.cachedCollectionPlayableBadge(title, subtitle, browseId, knownSongs).toBadgeState()
    }
    return state
}

/** Backwards-compatible Boolean view for callers that do not paint a tier. */
@Composable
fun rememberReleaseLosslessAvailable(
    title: String,
    subtitle: String,
    isRelease: Boolean,
    knownSongs: List<Song> = emptyList(),
    browseId: String? = null,
    allowProbe: Boolean = true,
): Boolean = rememberReleaseLosslessBadgeState(
    title = title,
    subtitle = subtitle,
    isRelease = isRelease,
    knownSongs = knownSongs,
    browseId = browseId,
    allowProbe = allowProbe,
).isLossless

data class CollectionBadgeState(
    val isExplicit: Boolean,
    val isLossless: Boolean,
    val isHiResLossless: Boolean = false,
    val isHiQuality: Boolean = false,
)

/**
 * Badge state for albums/singles and playlists.
 *
 * Albums keep the release-level probe. Playlists — including the account's own
 * playlists — derive Explicit/quality from their contents and share a cache
 * with the detail page, so cards and destinations use the same exact tier.
 */
@Composable
fun rememberCollectionBadges(
    type: BrowseType,
    title: String,
    subtitle: String,
    browseId: String?,
    explicitHint: Boolean,
    knownSongs: List<Song> = emptyList(),
    allowProbe: Boolean = true,
): CollectionBadgeState {
    // A card only has a browseId, unlike the detail screen. Index its real
    // track membership in the background so offline/playback confirmations
    // anywhere in a long playlist are visible without opening it first.
    val context = LocalContext.current
    val badgeCacheRevision by SourceResolver.badgeCacheRevision.collectAsStateWithLifecycle()
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    // Do not key the HTTP work to the ever-changing cache revision. Otherwise
    // finishing one card's scan cancels the next card's in-flight scan.
    val hasLosslessEvidence = SourceResolver.hasVerifiedLosslessTracks()
    val knownTrackIds = remember(knownSongs) { knownSongs.map(Song::videoId) }
    LaunchedEffect(browseId, knownTrackIds) {
        if (knownTrackIds.isNotEmpty()) {
            SourceResolver.extendCollectionTrackIds(browseId, knownTrackIds)
        }
    }
    LaunchedEffect(browseId, type, knownSongs.isEmpty(), hasLosslessEvidence) {
        if (knownSongs.isEmpty() && hasLosslessEvidence) {
            CollectionBadgeIndex.ensureIndexed(context, browseId, type)
        }
    }
    val verifiedQuality = rememberReleaseLosslessBadgeState(
        title = title,
        subtitle = subtitle,
        isRelease = type == BrowseType.ALBUM || type == BrowseType.PLAYLIST,
        knownSongs = knownSongs,
        browseId = browseId,
        allowProbe = allowProbe,
    )

    val albumExplicit = if (type == BrowseType.ALBUM) {
        explicitHint || knownSongs.any { it.isExplicit } ||
            SourceResolver.cachedReleaseExplicit(title, subtitle, browseId) == true
    } else false

    val isPlaylist = type == BrowseType.PLAYLIST && !browseId.isNullOrBlank()
    val cachedPlaylist = if (isPlaylist) {
        SourceResolver.cachedPlaylistBadges(requireNotNull(browseId))
    } else null
    var playlistExplicit by remember(
        browseId, type, explicitHint, cachedPlaylist?.isExplicit, knownSongs, badgeCacheRevision,
    ) {
        mutableStateOf(explicitHint || knownSongs.any { it.isExplicit } || cachedPlaylist?.isExplicit == true)
    }


    return when (type) {
        BrowseType.ALBUM -> CollectionBadgeState(
            isExplicit = albumExplicit,
            isLossless = verifiedQuality.isLossless,
            isHiResLossless = verifiedQuality.isHiResLossless,
            isHiQuality = verifiedQuality.isHiQuality,
        )
        BrowseType.PLAYLIST -> CollectionBadgeState(
            isExplicit = playlistExplicit || explicitHint || knownSongs.any { it.isExplicit },
            isLossless = verifiedQuality.isLossless,
            isHiResLossless = verifiedQuality.isHiResLossless,
            isHiQuality = verifiedQuality.isHiQuality,
        )
        else -> CollectionBadgeState(
            isExplicit = explicitHint,
            isLossless = false,
            isHiResLossless = false,
            isHiQuality = false,
        )
    }
}

/**
 * A title with a compact explicit-content marker.
 *
 * The E is always kept outside the title text whenever [isExplicit] is true,
 * so ellipsis can never hide a confirmed Explicit badge anywhere in the app.
 * Regular surfaces keep the title static and apply their normal [overflow].
 * MiniPlayer and Now Playing opt into [keepBadgeVisible], which additionally
 * lets a single-line title marquee while the E remains fixed in place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplicitTitle(
    text: String,
    isExplicit: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    badgeSpacing: Dp = 4.dp,
    // One universal visual size for the Explicit seal across the whole app.
    // The VectorDrawable itself owns the frame/glyph proportions.
    badgeSize: Dp = 14.5.dp,
    // Kept for source compatibility with existing callers. The vector owns
    // the E geometry now, so text size no longer affects the rendered badge.
    badgeFontSize: TextUnit = 9.sp,
    badgeVerticalOffset: Dp = 0.dp,
    badgePageColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    badgeContentColor: Color? = null,
    fadeOverflow: Boolean = false,
    keepBadgeVisible: Boolean = false,
) {
    var titleWidthPx by remember(text, style) { mutableStateOf(0) }
    var viewportWidthPx by remember(text, style) { mutableStateOf(0) }
    val needsFade = { viewportWidthPx > 0 && titleWidthPx > viewportWidthPx }
    if (!isExplicit) {
        val marqueeEnabled = keepBadgeVisible && maxLines == 1
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier
                .onSizeChanged { viewportWidthPx = it.width }
                .endFade(enabled = { fadeOverflow && marqueeEnabled && needsFade() }, width = 14.dp)
                .then(
                    if (marqueeEnabled) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1200,
                            repeatDelayMillis = 1200,
                            velocity = 30.dp,
                        )
                    } else {
                        Modifier
                    },
                ),
            maxLines = maxLines,
            overflow = if (marqueeEnabled) TextOverflow.Clip else overflow,
            textAlign = textAlign,
            onTextLayout = { titleWidthPx = it.size.width },
        )
        return
    }


    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            // Only MiniPlayer/Now Playing marquee. Every other surface keeps
            // its normal ellipsis while the badge receives reserved width.
            overflow = if (keepBadgeVisible && maxLines == 1) TextOverflow.Clip else overflow,
            textAlign = textAlign,
            onTextLayout = { titleWidthPx = it.size.width },
            modifier = Modifier
                .weight(1f, fill = false)
                .onSizeChanged { viewportWidthPx = it.width }
                .endFade(enabled = { keepBadgeVisible && fadeOverflow && maxLines == 1 && needsFade() }, width = 14.dp)
                .then(
                    if (keepBadgeVisible && maxLines == 1) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1200,
                            repeatDelayMillis = 1200,
                            velocity = 30.dp,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.width(badgeSpacing))
        ExplicitBadgeSeal(
            pageColor = badgePageColor,
            size = badgeSize,
            modifier = Modifier.offset(y = badgeVerticalOffset),
            contentColorOverride = badgeContentColor,
        )
    }
}

private fun Modifier.endFade(
    enabled: () -> Boolean,
    width: Dp,
): Modifier = this
        .graphicsLayer {
            compositingStrategy = if (enabled()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            if (!enabled()) return@drawWithContent
            val fadeWidth = width.toPx().coerceAtMost(size.width)
            val fadeStart = (size.width - fadeWidth).coerceAtLeast(0f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf<Pair<Float, Color>>(
                        0f to Color.Black,
                        (if (size.width == 0f) 1f else (fadeStart / size.width)) to Color.Black,
                        1f to Color.Transparent,
                    ),
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * One track row, used by search, library and detail pages.
 *
 * Swiping springs the row back rather than dismissing it. Album/playlist
 * callers can supply direction-specific actions (right = Add to queue, left =
 * Play next); legacy callers may keep [onSwipeToQueue], which still follows
 * [AppSettings.swipeToPlayNext]. Long-press opens the actions menu.
 */
/**
 * Compact, passive download state shown next to a track duration. Queued and
 * active downloads share the in-progress glyph; a saved track gets the
 * completed-download glyph. Failed/absent downloads intentionally render
 * nothing here — the action sheet remains the place for errors and retries.
 */
@Composable
fun DownloadStatusGlyph(
    videoId: String,
    tint: Color,
    modifier: Modifier = Modifier,
    assumeSaved: Boolean = false,
) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val icon = when (active[videoId]) {
        is DownloadState.Queued, is DownloadState.Resolving, is DownloadState.Running -> Icons.Rounded.Downloading
        else -> if (assumeSaved || videoId in saved) Icons.Rounded.DownloadDone else null
    } ?: return

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(15.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    /**
     * What the row paints over the swipe reveal as it slides back.
     *
     * It has to be the colour of the page the row is *on*, not the theme's
     * background — an album page tinted from its sleeve would otherwise drag a
     * black band across itself on every swipe.
     */
    rowBackground: Color = MaterialTheme.colorScheme.background,
    /**
     * Drawn in place of the artwork, for lists where every row would otherwise
     * repeat the same cover — an album's own track listing.
     */
    trackNumber: Int? = null,
    /**
     * The artist line, and the track number when there is one.
     *
     * A page tinted from its artwork wants this brighter than the flat feeds
     * do: the usual dim grey is pitched against black, and against a mid-toned
     * wash it stops being legible as a second line and starts disappearing.
     */
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** Search-only compact E/Lossless badges; other SongRow callers stay unchanged. */
    showSearchBadges: Boolean = false,
    /** Search pipeline already resolved this row; do not launch a duplicate probe from Compose. */
    searchBadgesPreResolved: Boolean = false,
    /** Search/feed rows can opt into the rounded Expressive surface without changing album track lists. */
    expressiveContainer: Boolean = false,
    forceDownloadedGlyph: Boolean = false,
    fullExplicitBadge: Boolean = false,
    /** Material source used by Explicit/quality badges rendered inside this row. */
    badgePageColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    /** Keep album/playlist inline seals as clear as Now Playing's controls. */
    transparentDetailBadges: Boolean = false,
) {
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffsetTarget by remember(song.videoId) { mutableFloatStateOf(0f) }
    var horizontalDragging by remember(song.videoId) { mutableStateOf(false) }
    val swipeOffsetPx by animateFloatAsState(
        targetValue = swipeOffsetTarget,
        animationSpec = if (horizontalDragging) snap() else spring(dampingRatio = 0.82f, stiffness = 480f),
        label = "songRowSwipeOffset",
    )
    val localTrack = !song.localUri.isNullOrBlank() || !song.localPath.isNullOrBlank()
    val rowQuality = if (showSearchBadges || localTrack) {
        rememberTrackLosslessBadgeState(
            song = song,
            allowProbe = showSearchBadges && !searchBadgesPreResolved,
        )
    } else {
        LosslessBadgeState()
    }
    val showInlineBadges = showSearchBadges || rowQuality.isLossless || rowQuality.isHiQuality

    val hasDirectionalSwipe = onSwipeRight != null || onSwipeLeft != null
    val hasAnySwipe = hasDirectionalSwipe || onSwipeToQueue != null
    val swipeContext = LocalContext.current
    var lastSwipeDirection by remember(song.videoId) { mutableStateOf(0) }

    if (!hasAnySwipe) {
        SongRowContent(
            song, onClick, onLongPress, modifier, trackNumber, subtitleColor,
            showInlineBadges = showInlineBadges,
            quality = rowQuality,
            expressiveContainer = expressiveContainer,
            forceDownloadedGlyph = forceDownloadedGlyph,
            fullExplicitBadge = fullExplicitBadge,
            badgePageColor = badgePageColor,
            transparentDetailBadges = transparentDetailBadges,
        )
        return
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxWidth = it.width.toFloat() }
            .pointerInput(song.videoId, hasAnySwipe, boxWidth) {
                detectOrbHorizontalDragGestures(
                    horizontalDominance = 1.40f,
                    onDragStart = {
                        horizontalDragging = true
                        lastSwipeDirection = 0
                    },
                    onHorizontalDrag = { deltaX ->
                        if (boxWidth > 0f) {
                            swipeOffsetTarget = (swipeOffsetTarget + deltaX)
                                .coerceIn(-boxWidth * 0.62f, boxWidth * 0.62f)
                            val activation = (boxWidth * 0.08f).coerceAtLeast(1f)
                            val direction = when {
                                swipeOffsetTarget >= activation -> 1
                                swipeOffsetTarget <= -activation -> -1
                                else -> 0
                            }
                            if (direction != 0 && direction != lastSwipeDirection) {
                                OrbHaptics.perform(swipeContext, OrbHaptics.Kind.NAVIGATION)
                            }
                            lastSwipeDirection = direction
                        }
                    },
                    onDragEnd = { totalDeltaX ->
                        horizontalDragging = false
                        val reachedAction = boxWidth > 0f && abs(swipeOffsetTarget) >= boxWidth * 0.45f
                        if (reachedAction) {
                            if (swipeOffsetTarget > 0f) {
                                (onSwipeRight ?: onSwipeToQueue)?.invoke()
                            } else if (swipeOffsetTarget < 0f) {
                                (onSwipeLeft ?: onSwipeToQueue)?.invoke()
                            }
                        }
                        swipeOffsetTarget = 0f
                        lastSwipeDirection = 0
                    },
                    onDragCancel = {
                        horizontalDragging = false
                        swipeOffsetTarget = 0f
                        lastSwipeDirection = 0
                    },
                )
            },
    ) {
        QueueSwipeBackground(
            offsetPx = swipeOffsetPx,
            directionalActions = hasDirectionalSwipe,
        )
        SongRowContent(
            song = song,
            onClick = onClick,
            onLongPress = onLongPress,
            modifier = Modifier
                .graphicsLayer { translationX = swipeOffsetPx }
                .background(rowBackground),
            trackNumber = trackNumber,
            subtitleColor = subtitleColor,
            showInlineBadges = showInlineBadges,
            quality = rowQuality,
            expressiveContainer = expressiveContainer,
            forceDownloadedGlyph = forceDownloadedGlyph,
            fullExplicitBadge = fullExplicitBadge,
            badgePageColor = badgePageColor,
            transparentDetailBadges = transparentDetailBadges,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwipeBackground(
    offsetPx: Float,
    directionalActions: Boolean,
) {
    val legacyPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                if (offsetPx > 0f) {
                    clipRect(left = 0f, top = 0f, right = offsetPx.coerceAtMost(size.width), bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                } else if (offsetPx < 0f) {
                    clipRect(left = (size.width + offsetPx).coerceAtLeast(0f), top = 0f, right = size.width, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = PAGE_GUTTER + 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (directionalActions) {
            // Dragging right reveals the left edge: Add to queue. Dragging left
            // reveals the right edge: Play next.
            QueueSwipeLabel(playNext = false)
            QueueSwipeLabel(playNext = true)
        } else {
            QueueSwipeLabel(legacyPlayNext)
            QueueSwipeLabel(legacyPlayNext)
        }
    }
}

@Composable
private fun QueueSwipeLabel(playNext: Boolean) {
    val shape = RoundedCornerShape(15.dp)
    val contentColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .widthIn(min = 172.dp)
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(contentColor.copy(alpha = 0.13f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (playNext) Icons.Rounded.PlaylistPlay else Icons.Rounded.PlaylistAdd,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(
                if (playNext) R.string.song_action_play_next else R.string.song_action_add_to_queue,
            ),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showInlineBadges: Boolean = false,
    quality: LosslessBadgeState = LosslessBadgeState(),
    expressiveContainer: Boolean = false,
    forceDownloadedGlyph: Boolean = false,
    fullExplicitBadge: Boolean = false,
    badgePageColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    transparentDetailBadges: Boolean = false,
) {
    var launchBounds by remember(song.videoId) { mutableStateOf<Rect?>(null) }
    val launchAwareClick = {
        launchBounds?.let(NowPlayingLaunchOriginRegistry::record)
        onClick()
    }
    val rowModifier = if (OrbFlavorUi.expressive && expressiveContainer) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = launchAwareClick, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    } else {
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = launchAwareClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            // Same 52dp the artwork would take, so a numbered list and an
            // illustrated one share a left edge and a divider inset.
            Box(
                Modifier
                    .size(52.dp)
                    .onGloballyPositioned { coordinates ->
                        val topLeft = coordinates.positionInRoot()
                        launchBounds = Rect(
                            left = topLeft.x,
                            top = topLeft.y,
                            right = topLeft.x + coordinates.size.width,
                            bottom = topLeft.y + coordinates.size.height,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$trackNumber",
                    style = MaterialTheme.typography.bodyLarge,
                    color = subtitleColor,
                )
            }
        } else {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .onGloballyPositioned { coordinates ->
                        val topLeft = coordinates.positionInRoot()
                        launchBounds = Rect(
                            left = topLeft.x,
                            top = topLeft.y,
                            right = topLeft.x + coordinates.size.width,
                            bottom = topLeft.y + coordinates.size.height,
                        )
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(if (trackNumber != null) 10.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            if (showInlineBadges) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AnimatedVisibility(
                        visible = song.isExplicit || quality.isLossless || quality.isHiQuality,
                        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.86f),
                        exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = 0.90f),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(6.dp))
                            if (fullExplicitBadge) {
                                ReleaseBadges(
                                    isExplicit = song.isExplicit,
                                    isLossless = quality.isLossless,
                                    isHiResLossless = quality.isHiResLossless,
                                    isHiQuality = quality.isHiQuality,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    glassPageColor = badgePageColor,
                                    transparentLikeNowPlaying = transparentDetailBadges,
                                )
                            } else {
                                CompactMediaBadges(
                                    isExplicit = song.isExplicit,
                                    isLossless = quality.isLossless,
                                    isHiResLossless = quality.isHiResLossless,
                                    isHiQuality = quality.isHiQuality,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    glassPageColor = badgePageColor,
                                    transparentLikeNowPlaying = transparentDetailBadges,
                                )
                            }
                        }
                    }
                }
            } else {
                ExplicitTitle(
                    text = song.title,
                    isExplicit = song.isExplicit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    badgePageColor = badgePageColor,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Download state is independent from whether duration metadata exists.
        // Keep the glyph immediately to the LEFT of the duration on every
        // SongRow surface.
        Spacer(Modifier.width(8.dp))
        DownloadStatusGlyph(
            videoId = song.videoId,
            tint = subtitleColor,
            assumeSaved = forceDownloadedGlyph,
        )
        song.durationText?.let { duration ->
            Spacer(Modifier.width(5.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelMedium,
                color = subtitleColor,
            )
        }
        // Same sheet the long-press opens, for anyone who doesn't think to hold.
        if (onLongPress != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLongPress),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.collection_actions_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Pull to refresh without any content translation or in-list indicator.
 * The floating OrbRefreshDroplet is drawn above the header by MainActivity.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
    indicatorTopPadding: Dp = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 64.dp,
    pageColor: Color = MaterialTheme.colorScheme.background,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    // Detach the gooey blob BEFORE it can become an unnaturally long cone.
    // At 82% of the stock pull distance, refresh immediately, even if the
    // finger is still down. Keep suppression until finger-up to
    // prevent a second callback from the underlying Material3 handler.
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    var autoRefreshSent by remember(state) { mutableStateOf(false) }
    // The Material3 state may reset its fraction to zero WHILE the user's
    // finger is still down. Track the actual pointer instead, so we cannot
    // dispatch a second refresh during the same long press.
    var fingerDown by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state, refreshing, autoRefreshSent) {
        snapshotFlow { state.distanceFraction }
            .collectLatest { fraction ->
                if (fraction >= 0.82f && !refreshing && !autoRefreshSent) {
                    autoRefreshSent = true
                    latestOnRefresh()
                }
            }
    }
    LaunchedEffect(state, refreshing, autoRefreshSent) {
        if (autoRefreshSent && !refreshing) {
            // Wait for BOTH the real touch release and an idle pull state.
            snapshotFlow { !fingerDown && state.distanceFraction <= 0.04f }
                .first { it }
            delay(320L)
            autoRefreshSent = false
        }
    }
    // No page translations, stretch overscroll or in-list progress indicator.
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { if (!autoRefreshSent) latestOnRefresh() },
            state = state,
            modifier = modifier
                .fillMaxSize()
                .background(pageColor)
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        fingerDown = true
                        var isPressed = true
                        while (isPressed) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            isPressed = event.changes.any { it.pressed }
                        }
                        fingerDown = false
                    }
                },
            indicator = {},
        ) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** Slim dismissible-looking prompt shown atop Home while signed out. */
@Composable
fun SignInBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onSignIn)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.banner_signin_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.banner_signin_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onSignIn) { Text(stringResource(R.string.banner_signin_button)) }
    }
}

@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER + 12.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
