package com.music.orb.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import com.music.orb.ui.components.OrbGlassFilledTonalButton as FilledTonalButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.OrbSheetTintMode
import com.music.orb.ui.components.RegisterSheetBackdrop
import com.music.orb.ui.components.OrbModalBottomSheet
import com.music.orb.ui.components.orbSheetPanelElevation
import com.music.orb.data.social.SocialRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.music.orb.R
import com.music.orb.data.WikipediaArtistInfoRepository
import com.music.orb.data.model.DetailPage
import com.music.orb.data.settings.AlbumRatingStore
import com.music.orb.data.social.CommunityStatsRepository
import com.music.orb.data.spotify.LinkedLikesSync
import com.music.orb.ui.components.OrbHaptics
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.theme.ArtworkPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumInfoPanel(
    page: DetailPage,
    artist: String,
    palette: ArtworkPalette,
    blocked: Boolean,
    onToggleBlock: (() -> Unit)?,
    showDetails: Boolean = true,
    rateRequest: Int = 0,
    ratingLandingTargetOnScreen: Rect? = null,
) {
    val context = LocalContext.current
    val account = LinkedLikesSync.currentAccount() ?: "local-device"
    val profile by SocialRepository.myProfileState.collectAsStateWithLifecycle()
    val signedIn = profile != null && SocialRepository.currentUserId() != null
    // Opportunistically backfill release metadata for previously saved ratings,
    // without changing the score or making a private rating visible.
    val albumRatingRevision by AlbumRatingStore.revision.collectAsStateWithLifecycle()
    LaunchedEffect(albumRatingRevision, page.browseId, page.title, artist, page.thumbnailUrl, account, signedIn) {
        if (signedIn && AlbumRatingStore.hasRating(context, account, page.browseId)) {
            runCatching {
                CommunityStatsRepository.registerRatedAlbum(
                    page.browseId, page.title, artist, page.thumbnailUrl,
                )
            }
        }
    }
    val scope = rememberCoroutineScope()
    var ratingExpanded by remember(account, page.browseId) { mutableStateOf(false) }
    var draftRating by remember(account, page.browseId) { mutableStateOf(0f) }
    var saving by remember(account, page.browseId) { mutableStateOf(false) }
    var saveFailed by remember(account, page.browseId) { mutableStateOf(false) }
    var ratingSaveAnimating by remember(account, page.browseId) { mutableStateOf(false) }
    var ratingButtonBoundsOnScreen by remember(account, page.browseId) { mutableStateOf<Rect?>(null) }
    val ratingMorph = remember(account, page.browseId) { Animatable(0f) }
    var ratingSheetClosing by remember(account, page.browseId) { mutableStateOf(false) }
    val hostView = LocalView.current
    var info by remember(page.browseId, artist) { mutableStateOf<WikipediaArtistInfoRepository.ArtistInfo?>(null) }
    var loading by remember(page.browseId, artist) { mutableStateOf(true) }
    LaunchedEffect(page.browseId, artist, showDetails) {
        if (!showDetails) return@LaunchedEffect
        loading = true
        try { info = WikipediaArtistInfoRepository.albumSummary(page.title, artist) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { info = null }
        finally { loading = false }
    }
    LaunchedEffect(rateRequest) {
        if (rateRequest > 0 && signedIn) {
            draftRating = AlbumRatingStore.load(context, account, page.browseId)
            saveFailed = false
            ratingSheetClosing = false
            ratingExpanded = true
        }
    }
    AnimatedVisibility(
        visible = showDetails,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 16.dp)) {
        StableTypewriterText(
            text = when {
                loading -> stringResource(R.string.album_info_loading)
                info == null -> stringResource(R.string.album_info_unavailable)
                else -> info?.extract.orEmpty()
            },
            animate = !loading && info != null,
            color = palette.onBackground,
        )
        if (info != null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.artist_info_source), style = MaterialTheme.typography.labelSmall, color = palette.onBackgroundVariant)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                    draftRating = AlbumRatingStore.load(context, account, page.browseId)
                    saveFailed = false
                    ratingSheetClosing = false
                    ratingExpanded = true
                },
                enabled = signedIn,
                shape = RoundedCornerShape(14.dp),
                pageColor = palette.background,
                transparentLikeNowPlaying = true,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = stringResource(R.string.album_rate),
                    modifier = Modifier.size(24.dp).onGloballyPositioned { coordinates ->
                        // Measure the STAR glyph's layout, not the 48dp tap target.
                        val bounds = coordinates.boundsInWindow()
                        val origin = IntArray(2)
                        hostView.rootView.getLocationOnScreen(origin)
                        ratingButtonBoundsOnScreen = Rect(
                            bounds.left + origin[0], bounds.top + origin[1],
                            bounds.right + origin[0], bounds.bottom + origin[1],
                        )
                    },
                )
            }
            onToggleBlock?.let { action ->
                val blockShape = if (blocked) CircleShape else RoundedCornerShape(14.dp)
                FilledTonalButton(
                    onClick = action,
                    shape = blockShape,
                    pageColor = palette.background,
                    transparentLikeNowPlaying = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (blocked) R.string.album_allow_playback else R.string.album_do_not_play))
                }
            }
        }
        if (!signedIn) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.album_rating_sign_in), style = MaterialTheme.typography.labelSmall, color = palette.onBackgroundVariant)
        }
    }
    }
    if (ratingExpanded && signedIn) {
        RegisterSheetBackdrop()
        val density = LocalDensity.current
        // Both endpoints are stored in SCREEN pixels. Window and panel coordinates
        // are different, especially while a Dialog sheet is closing.
        var starsCenterOnScreen by remember(page.browseId) { mutableStateOf<Offset?>(null) }
        var overlayOriginOnScreen by remember(page.browseId) { mutableStateOf<Offset?>(null) }
        // Freeze destination POSITION and SIZE in screen pixels for the whole flight.
        var frozenFlightOnScreen by remember(page.browseId) { mutableStateOf<Pair<Offset, Rect>?>(null) }

        LaunchedEffect(ratingSaveAnimating) {
            if (!ratingSaveAnimating) return@LaunchedEffect
            ratingMorph.snapTo(0f)
            // Wait until layout positions have settled, then freeze the actual
            // destination. Recomposition after saving must never retarget the star.
            withFrameNanos { }
            val start = starsCenterOnScreen
            val overlayOrigin = overlayOriginOnScreen
            if (start != null && overlayOrigin != null) {
                val candidate = ratingButtonBoundsOnScreen ?: ratingLandingTargetOnScreen
                val validTarget = candidate?.takeIf { target ->
                    target.left.isFinite() && target.top.isFinite() &&
                        target.right.isFinite() && target.bottom.isFinite() &&
                        target.width > 0f && target.height > 0f
                }
                // If no destination is available, stay at the current location
                // and size. Do not invent a point near the top of the display.
                val sourceSizePx = with(density) { 38.dp.toPx() }
                val fallback = Rect(
                    start.x - sourceSizePx / 2f, start.y - sourceSizePx / 2f,
                    start.x + sourceSizePx / 2f, start.y + sourceSizePx / 2f,
                )
                frozenFlightOnScreen = start to (validTarget ?: fallback)
            }
            // The stars move first. As soon as their converged star has cleared
            // the panel, the sheet follows it down without interrupting the flight.
            ratingMorph.animateTo(0.55f, tween(430, easing = FastOutSlowInEasing))
            ratingSheetClosing = true
            ratingMorph.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
            OrbHaptics.ratingSavedSparkle(context)
            ratingExpanded = false
            ratingSaveAnimating = false
            ratingSheetClosing = false
            frozenFlightOnScreen = null
            ratingMorph.snapTo(0f)
        }

        val progress = ratingMorph.value
        val flight = ((progress - 0.30f) / 0.70f).coerceIn(0f, 1f)

        OrbModalBottomSheet(
            onDismissRequest = { if (!saving && !ratingSaveAnimating) ratingExpanded = false },
            dismissEnabled = (!saving && !ratingSaveAnimating) || ratingSheetClosing,
            containerColor = Color.Transparent,
            navigationBarInsetInContent = true,
            dragHandle = null,
            closeRequested = ratingSheetClosing,
            closeAnimationMillis = 1_000L,
            overlay = {
                // Include the overlay's own window offset (not only the Dialog
                // decor origin): all star positions use the same screen space.
                val overlayView = LocalView.current
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                        val location = IntArray(2)
                        overlayView.rootView.getLocationOnScreen(location)
                        val bounds = coordinates.boundsInWindow()
                        overlayOriginOnScreen = Offset(
                            location[0] + bounds.left,
                            location[1] + bounds.top,
                        )
                    },
                )
                // Keep the score at screen center, above the bottom-aligned stars.
                Text(
                    text = String.format(Locale.getDefault(), "%.1f", draftRating),
                    color = palette.onBackground,
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-110).dp)
                        .graphicsLayer { alpha = (1f - progress / 0.26f).coerceIn(0f, 1f) },
                )
                if (ratingSaveAnimating && progress >= 0.30f) {
                    val anchors = frozenFlightOnScreen
                    val overlayOrigin = overlayOriginOnScreen
                    if (anchors != null && overlayOrigin != null) {
                        // Fixed endpoints + straight interpolation: the star
                        // cannot jump to the top or change direction mid-flight.
                        val start = anchors.first - overlayOrigin
                        val destinationBounds = anchors.second
                        val destination = destinationBounds.center - overlayOrigin
                        val x = start.x + (destination.x - start.x) * flight
                        val y = start.y + (destination.y - start.y) * flight
                        val starSize = 38.dp
                        val starPx = with(density) { starSize.toPx() }
                        // Interpolate the visible glyph's size, not the button's
                        // touch area. The same flight can end at the 12dp album
                        // badge or the 24dp star in the expanded info section.
                        val sizeFraction = FastOutSlowInEasing.transform(flight)
                        val targetScaleX = destinationBounds.width / starPx
                        val targetScaleY = destinationBounds.height / starPx
                        val scaleXForFlight = 1.14f + (targetScaleX - 1.14f) * sizeFraction
                        val scaleYForFlight = 1.14f + (targetScaleY - 1.14f) * sizeFraction
                        val landing = ((flight - 0.78f) / 0.22f).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset {
                                    IntOffset(
                                        (x - starPx / 2f).roundToInt(),
                                        (y - starPx / 2f).roundToInt(),
                                    )
                                }
                                .size(starSize)
                                .graphicsLayer {
                                    alpha = ((progress - 0.30f) / 0.10f).coerceIn(0f, 1f)
                                    // Full revolutions keep the glyph oriented
                                    // identically to the destination upon arrival.
                                    rotationZ = flight * 720f
                                    scaleX = scaleXForFlight
                                    scaleY = scaleYForFlight
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (landing > 0f) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val center = center
                                    val radius = size.minDimension * (0.28f + 0.30f * landing)
                                    repeat(6) { index ->
                                        val angle = 2.0 * PI * index / 6.0
                                        drawCircle(
                                            color = palette.onBackground.copy(alpha = (1f - landing) * 0.75f),
                                            radius = size.minDimension * 0.045f * (1f - landing * 0.35f),
                                            center = Offset(
                                                center.x + kotlin.math.cos(angle).toFloat() * radius,
                                                center.y + kotlin.math.sin(angle).toFloat() * radius,
                                            ),
                                        )
                                    }
                                }
                            }
                            Icon(Icons.Rounded.Star, contentDescription = null,
                                tint = palette.onBackground, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            },
        ) {
            // Must resolve LocalView inside the Dialog, not in the Activity.
            val sheetView = LocalView.current
            val shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
            Column(
                modifier = Modifier.fillMaxWidth()
                    .orbSheetPanelElevation(shape)
                    .clip(shape)
                    .orbGlassSurface(
                        pageColor = palette.background,
                        cornerRadius = 36.dp,
                        backdropBlur = 92.dp,
                        overlayStrength = true,
                        overlayOpacity = 0.4f,
                        lightweightSheetMaterial = true,
                        sheetTintMode = OrbSheetTintMode.BACKDROP,
                        manageDialogBackdrop = false,
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(palette.onBackground.copy(alpha = 0.34f)))
                Box(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val location = IntArray(2)
                            sheetView.rootView.getLocationOnScreen(location)
                            val center = coordinates.boundsInWindow().center
                            starsCenterOnScreen = Offset(
                                location[0] + center.x,
                                location[1] + center.y,
                            )
                        },
                ) {
                    AlbumRatingStars(
                        draftRating, palette,
                        morphProgress = progress,
                    ) { if (!saving && !ratingSaveAnimating) draftRating = it }
                }
                if (saveFailed) {
                    Text(stringResource(R.string.album_rating_local_error),
                        color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.graphicsLayer {
                    alpha = (1f - progress / 0.26f).coerceIn(0f, 1f)
                }) {
                    HoldToSaveRatingButton(
                        enabled = !saving && !ratingSaveAnimating,
                        palette = palette,
                        onConfirmed = {
                            if (saving || ratingSaveAnimating) return@HoldToSaveRatingButton
                            saving = true
                            saveFailed = false
                            scope.launch {
                                try {
                                    AlbumRatingStore.save(
                                        context, account, page.browseId, draftRating,
                                        albumTitle = page.title,
                                        artistName = artist,
                                        artworkUrl = page.thumbnailUrl,
                                    )
                                    ratingSaveAnimating = true
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    saveFailed = true
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}


@Composable
private fun HoldToSaveRatingButton(
    enabled: Boolean,
    palette: ArtworkPalette,
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    val currentConfirm by rememberUpdatedState(onConfirmed)
    val progress by animateFloatAsState(
        targetValue = if (holding && enabled) 1f else 0f,
        animationSpec = if (holding && enabled) {
            tween(durationMillis = 900, easing = LinearEasing)
        } else {
            tween(durationMillis = 180)
        },
        label = "albumRatingHoldProgress",
    )

    LaunchedEffect(holding, enabled) {
        if (!holding || !enabled) return@LaunchedEffect
        delay(900L)
        if (holding && enabled) {
            holding = false
            OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
            currentConfirm()
        }
    }

    val shape = RoundedCornerShape(24.dp)
    val contentColor = palette.onBackground

    Box(
        modifier = Modifier
            .widthIn(min = 168.dp)
            .height(48.dp)
            .clip(shape)
            .albumGlassSurface(palette.background, cornerRadius = 24.dp, transparentLikeNowPlaying = true)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        holding = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            holding = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = progress.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }
                .background(contentColor.copy(alpha = if (enabled) 0.18f else 0.08f)),
        )
        Text(
            text = stringResource(R.string.album_rating_hold_to_save),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
            maxLines = 1,
        )
    }
}

/** One full-width touch surface lets taps and drags select tenths, including zero. */
@Composable
private fun AlbumRatingStars(value: Float, palette: ArtworkPalette, morphProgress: Float = 0f, onChange: (Float) -> Unit) {
    val context = LocalContext.current
    val currentChange by rememberUpdatedState(onChange)
    val currentValue by rememberUpdatedState(value)
    // The stars and numeric score are one visual unit; use the exact same
    // Expressive Glass foreground for both filled and outlined star geometry.
    val ratingContentColor = palette.onBackground
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.onBackground.copy(alpha = 0.08f))
            .border(0.5.dp, palette.onBackground.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..5f, 49)
                setProgress { requested ->
                    currentChange((requested.coerceIn(0f, 5f) * 10).roundToInt() / 10f)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(x: Float) {
                        val next = ((x / size.width.coerceAtLeast(1) * 50).roundToInt().coerceIn(0, 50)) / 10f
                        if (next != currentValue) {
                            if ((next * 2).toInt() != (currentValue * 2).toInt()) OrbHaptics.ratingStarTick(context)
                            currentChange(next)
                        }
                    }
                    down.consume()
                    update(down.position.x)
                    drag(down.id) { change -> update(change.position.x); change.consume() }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { index ->
            val selected = value > index
            val convergence = (morphProgress / 0.34f).coerceIn(0f, 1f)
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.12f else 0.92f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 420f),
                label = "ratingStarScale",
            )
            val rotation by animateFloatAsState(
                targetValue = if (selected) 0f else -16f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
                label = "ratingStarRotation",
            )
            val fraction by animateFloatAsState(
                targetValue = if (morphProgress > 0f && index == 2) 1f else (value - index).coerceIn(0f, 1f),
                animationSpec = tween(130),
                label = "ratingStarFill",
            )
            Box(Modifier.weight(1f).graphicsLayer {
                // Five actual stars travel into the center star. Nothing is
                // substituted until they have visibly converged.
                translationX = (2 - index) * size.width * convergence
                scaleX = scale * (1f - 0.20f * convergence)
                scaleY = scale * (1f - 0.20f * convergence)
                rotationZ = rotation * (1f - convergence)
                alpha = if (index == 2) (1f - ((morphProgress - 0.30f) / 0.10f).coerceIn(0f, 1f))
                    else (1f - convergence).coerceIn(0f, 1f)
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.StarBorder, contentDescription = null, tint = ratingContentColor, modifier = Modifier.size(36.dp))
                Icon(Icons.Rounded.Star, contentDescription = null, tint = ratingContentColor,
                    modifier = Modifier.size(36.dp).drawWithContent {
                        val canvas = drawContext.canvas
                        canvas.save()
                        canvas.clipRect(0f, 0f, size.width * fraction, size.height)
                        drawContent()
                        canvas.restore()
                    })
            }
        }
    }
}
