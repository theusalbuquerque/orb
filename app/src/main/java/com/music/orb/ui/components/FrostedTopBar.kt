@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.orb.BuildConfig
import com.music.orb.R
import com.music.orb.data.model.Account
import com.music.orb.data.settings.AppSettings
import com.music.orb.ui.flavor.OrbFlavorUi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/** Header glass uses the original v1.6.2 ultra-thin Haze material. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun orbHeaderGlassStyle(containerColor: Color): HazeStyle =
    HazeMaterials.ultraThin(containerColor)

/**
 * The header and page must feel like one continuous material. Material 3's
 * surfaceContainer is intentionally more elevated than background; using it
 * unmodified made the lower edge read as a second slab. Pull it toward the
 * page canvas while keeping enough chroma/contrast for Haze to remain visible.
 */
@Composable
private fun orbHeaderGlassColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (OrbFlavorUi.expressive) {
        lerp(scheme.background, scheme.surfaceContainer, 0.42f)
    } else {
        scheme.surface
    }
}

// The former tonal skirt below the Haze header was removed. The glass itself
// still blurs the content immediately behind the header, but nothing paints
// over the artwork/cards after the header's real lower boundary.

/** Centers a selected horizontal item, naturally clamping at the row edges. */
internal suspend fun LazyListState.animateScrollToCenteredItem(index: Int) {
    // If the chip is already on screen, never run animateScrollToItem first:
    // that snaps it toward the edge and then starts a second centering motion,
    // which is the small two-step jump visible when changing Home filters.
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val itemCenter = item.offset + item.size / 2
    val delta = (itemCenter - viewportCenter).toFloat()
    if (kotlin.math.abs(delta) > 1f) {
        animateScrollBy(
            value = delta,
            animationSpec = tween(durationMillis = 320),
        )
    }
}

/**
 * Telegram-style frosted glass top bar.
 *
 * The content behind must be tagged with `Modifier.hazeSource(hazeState)`;
 * this bar then samples and blurs whatever scrolls beneath it in real time
 * (RenderEffect on API 31+, translucent scrim fallback below).
 *
 * Apple Music behaviour: the big in-list header owns the title at rest;
 * once the list scrolls, the small centered title + hairline divider fade in.
 */
private val HeaderActionEndPadding = 18.dp
private val HeaderActionTopPadding = 10.dp
private val HeaderActionTouchSize = 48.dp
private val HeaderActionIconSize = 25.dp

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FrostedTopBar(
    title: String,
    hazeState: HazeState,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * Whether the bar carries its own pane of glass.
     *
     * False where something behind it is already providing one — [TopFadeBlur]
     * on a page whose artwork runs up under the status bar. Two panes over the
     * same content is one too many, and this bar's is the one with the hard
     * bottom edge.
     */
    ownBackdrop: Boolean = true,
    /** Local files/downloads have no large in-content title, so keep the header label visible. */
    alwaysShowTitle: Boolean = false,
    onBack: (() -> Unit)? = null,
    plainBackButton: Boolean = false,
    showBrandingWhenNoBack: Boolean = true,
    refreshing: Boolean = false,
    // A lambda, not a value: the drag changes every frame, and reading it in
    // the caller would recompose the whole app on each one.
    pullFraction: () -> Float = { 0f },
    actions: @Composable () -> Unit = {},
) {
    val titleAlpha by animateFloatAsState(
        targetValue = if (scrolled || alwaysShowTitle) 1f else 0f,
        animationSpec = tween(220),
        label = "topBarTitleAlpha",
    )
    val dividerColor by animateColorAsState(
        targetValue = (
            if (OrbFlavorUi.expressive) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.outline
        ).copy(
            alpha = if (scrolled && ownBackdrop && !OrbFlavorUi.expressive) 0.6f else 0f,
        ),
        animationSpec = tween(220),
        label = "topBarDivider",
    )
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassColor = orbHeaderGlassColor()


    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        !ownBackdrop -> Modifier
                        reduceDynamicBlur -> Modifier.background(glassColor)
                        else -> Modifier.hazeEffect(
                            state = hazeState,
                            style = orbHeaderGlassStyle(glassColor),
                        )
                    },
                ),
        ) {
            Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(if (OrbFlavorUi.expressive) 60.dp else 52.dp),
        ) {
            AnimatedContent(
                targetState = title,
                contentKey = { it },
                transitionSpec = {
                    (fadeIn(tween(190)) togetherWith fadeOut(tween(150))).using(null)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    // Reserve room for the back button and the actions so a
                    // long title truncates instead of running under them.
                    .padding(horizontal = 96.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = titleAlpha },
                label = "topBarTitle",
            ) { animatedTitle ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = animatedTitle,
                        style = if (OrbFlavorUi.expressive) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let { subtitleText ->
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            // On a pushed page the back affordance is always visible, since
            // there is no large in-list header to fall back on.
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = if (OrbFlavorUi.expressive) 10.dp else 4.dp)
                        .then(if (OrbFlavorUi.expressive) Modifier.size(44.dp) else Modifier),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.song_action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else if (showBrandingWhenNoBack) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = if (OrbFlavorUi.expressive) 18.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.orb_logo),
                        contentDescription = null,
                        modifier = Modifier.height(if (OrbFlavorUi.expressive) 26.dp else 24.dp),
                    )
                    // Keep the preview channel obvious in-app. Dev and prod
                    // currently share the same package id, so installing one
                    // replaces the other even though their UI can diverge.
                    if (BuildConfig.FLAVOR == "dev") {
                        Text(
                            text = "Dev",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (OrbFlavorUi.expressive) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .then(
                                    if (OrbFlavorUi.expressive) {
                                        Modifier
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = if (OrbFlavorUi.expressive) 8.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
        // Refresh feedback belongs to the page gap; this divider stays fixed.
            Box(Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
            }
        }
        if (ownBackdrop) {

        }
    }
}

/** Greeting IDs are resolved by stringResource so app-language changes update the Home header. */
internal fun homeGreetingResForHour(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.expressive_greeting_morning
    in 12..17 -> R.string.expressive_greeting_afternoon
    in 18..23 -> R.string.expressive_greeting_evening
    else -> R.string.expressive_greeting_overnight
}

/**
 * Expanded Material 3 Expressive header used only by Home.
 *
 * At rest it keeps the profile and settings affordances on the first row, then
 * carries the greeting and the feed pills inside the very same glass pane.
 * Scrolling collapses only the extra rows; the glass itself remains in place.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun HomeFrostedHeader(
    account: Account?,
    hazeState: HazeState,
    scrolled: Boolean,
    selectedSection: Int,
    sections: List<String>,
    onSectionSelected: (Int) -> Unit,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
    glassRecovering: Boolean = false,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val wifiConnected by AppSettings.wifiConnection.collectAsStateWithLifecycle()
    // Use exactly the same glass recipe as the Settings top bar. Keeping the
    // material color opaque here is intentional: HazeMaterials owns the tint
    // and transparency; pre-dimming it changes the blur response.
    val glassColor = orbHeaderGlassColor()
    // During Back navigation Haze needs a frame to recapture the Home page.
    // This temporary backing matches the glass color and blocks the sharp-artwork flash.
    val recoveryBackingAlpha by animateFloatAsState(
        targetValue = if (glassRecovering && !reduceDynamicBlur) 0.88f else 0f,
        animationSpec = if (glassRecovering) snap() else tween(220),
        label = "homeHeaderGlassRecoveryBacking",
    )

    val dividerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = if (scrolled) 0.42f else 0f,
        ),
        animationSpec = tween(220),
        label = "homeHeaderDivider",
    )
    // Use the app's selected locale, not a hardcoded Portuguese greeting.
    // A clock tick also refreshes the greeting when Home stays open across a time boundary.
    var localHour by remember { mutableIntStateOf(java.time.LocalTime.now().hour) }
    LaunchedEffect(Unit) {
        while (true) {
            localHour = java.time.LocalTime.now().hour
            delay(30_000L)
        }
    }
    val hello = stringResource(homeGreetingResForHour(localHour))
    val firstName = account?.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val greeting = if (firstName == null) hello else "$hello, $firstName"

    val topAreaHeight by animateDpAsState(
        targetValue = if (scrolled) 62.dp else 116.dp,
        animationSpec = tween(260),
        label = "homeHeaderTopAreaHeight",
    )
    val avatarSize by animateDpAsState(
        targetValue = if (scrolled) 38.dp else 48.dp,
        animationSpec = tween(240),
        label = "homeHeaderAvatarSize",
    )
    val avatarTop by animateDpAsState(
        targetValue = if (scrolled) 17.dp else 10.dp,
        animationSpec = tween(240),
        label = "homeHeaderAvatarTop",
    )
    val greetingX by animateDpAsState(
        targetValue = if (scrolled) 68.dp else 20.dp,
        animationSpec = tween(260),
        label = "homeHeaderGreetingX",
    )
    val greetingY by animateDpAsState(
        targetValue = if (scrolled) 24.dp else 70.dp,
        animationSpec = tween(260),
        label = "homeHeaderGreetingY",
    )
    val greetingSize by animateFloatAsState(
        targetValue = if (scrolled) 18f else 29f,
        animationSpec = tween(250),
        label = "homeHeaderGreetingSize",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(glassColor)
                    } else {
                        Modifier.background(glassColor.copy(alpha = recoveryBackingAlpha))
                            .hazeEffect(
                                state = hazeState,
                                style = orbHeaderGlassStyle(glassColor),
                            )
                    },
                ),
        ) {
            Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(topAreaHeight),
        ) {
            HomeHeaderAvatar(
                account = account,
                size = avatarSize,
                iconSize = if (scrolled) 21.dp else 25.dp,
                onClick = onAccountClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = avatarTop),
            )

            OrbCastButton(
                modifier=Modifier.align(Alignment.TopEnd).padding(
                    end=HeaderActionEndPadding+HeaderActionTouchSize, top=HeaderActionTopPadding),
            )

            // Settings stays visually stable while Home collapses: only the
            // surrounding header moves. The affordance has no decorative
            // background, so its 48 dp touch target never reads as another chip.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = HeaderActionEndPadding,
                        top = HeaderActionTopPadding,
                    )
                    .size(HeaderActionTouchSize)
                    .clickable(onClick = onSettingsClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.title_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(HeaderActionIconSize),
                )
            }

            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = greetingSize.sp,
                    lineHeight = (greetingSize + if (scrolled) 4f else 5f).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = greetingX, top = greetingY, end = if (wifiConnected == true) 126.dp else 78.dp),
            )
        }

        // A single moving selection capsule, driven by the same spring family
        // used by the bottom navigation. The previous implementation animated
        // four independent chip backgrounds, so the highlight appeared to
        // teleport instead of travelling between sections.
        HomeSectionSelector(
            sections = sections,
            selectedSection = selectedSection,
            onSectionSelected = onSectionSelected,
        )

            Box(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(0.5.dp))
            }
        }

    }
}


/**
 * Material 3 Expressive Home section selector.
 *
 * With only four destinations a regular scroll row is cheaper and, more
 * importantly, lets one physical indicator move between measured chip slots.
 * Its offset/width and the automatic centering scroll use medium-bouncy
 * springs, mirroring the motion language of [FloatingBottomBar].
 */
@Composable
private fun HomeSectionSelector(
    sections: List<String>,
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
) {
    OrbBouncyPillSelector(
        labels = sections,
        selectedIndex = selectedSection,
        onSelected = onSectionSelected,
    )
}

/**
 * Shared Material 3 Expressive travelling-pill selector.
 *
 * The selected capsule is a single physical layer that springs between slots,
 * just like Orb's expressive bottom navigation. The neutral slot under the
 * moving capsule progressively fades and morphs to the capsule radius as the
 * overlap grows; this prevents the old, less-rounded chip from peeking out at
 * the corners and looking like a shadow behind the selected item.
 */
@Composable
internal fun OrbBouncyPillSelector(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) {
    if (labels.isEmpty()) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val scrollState = rememberScrollState()
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    val sidePadding = 18.dp
    val chipHorizontalPadding = 18.dp
    val chipHeight = 42.dp
    val gap = 8.dp
    val iconSize = 18.dp
    val iconGap = 6.dp
    val neutralRadiusDp = 15f
    val selectedRadiusDp = 22f
    // Neutral filters use exactly the same glass and outline as the bottom dock.
    val neutralContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val neutralBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val navigationHazeState = LocalOrbNavigationGlassHazeState.current
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val sidePaddingPx = with(density) { sidePadding.toPx() }
    val chipPaddingPx = with(density) { (chipHorizontalPadding * 2).toPx() }
    val gapPx = with(density) { gap.toPx() }
    val iconSizePx = with(density) { iconSize.toPx() }
    val iconGapPx = with(density) { iconGap.toPx() }

    val chipWidthsPx = labels.mapIndexed { index, label ->
        val iconExtra = if (icons.getOrNull(index) != null) iconSizePx + iconGapPx else 0f
        textMeasurer.measure(
            text = AnnotatedString(label),
            style = labelStyle,
            maxLines = 1,
        ).size.width.toFloat() + chipPaddingPx + iconExtra
    }
    val chipOffsetsPx = buildList {
        var x = sidePaddingPx
        chipWidthsPx.forEach { width ->
            add(x)
            x += width + gapPx
        }
    }
    val contentWidthPx = sidePaddingPx * 2f +
            chipWidthsPx.sum() + gapPx * (labels.size - 1).coerceAtLeast(0)

    val safeSelectedIndex = selectedIndex.coerceIn(labels.indices)
    // Shared selected material: same dynamic primaryContainer as Geral/Pessoal,
    // miniplayer and bottom navigation. The outer rail retains its frosted glass.
    val selectedContainerTarget = MaterialTheme.colorScheme.primaryContainer
    val selectedContentTarget = MaterialTheme.colorScheme.onPrimaryContainer
    val selectedContainer by animateColorAsState(
        targetValue = selectedContainerTarget,
        animationSpec = tween(220),
        label = "orbPillSelectedContainer",
    )
    val selectedContent by animateColorAsState(
        targetValue = selectedContentTarget,
        animationSpec = tween(220),
        label = "orbPillSelectedContent",
    )
    val indicatorX by animateFloatAsState(
        targetValue = chipOffsetsPx[safeSelectedIndex],
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "orbPillIndicatorX",
    )
    val indicatorWidth by animateFloatAsState(
        targetValue = chipWidthsPx[safeSelectedIndex],
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "orbPillIndicatorWidth",
    )

    // Shape is part of the motion now: every destination change briefly returns
    // the travelling capsule toward the tighter neutral radius, then springs it
    // into the fully rounded selected form. This removes the instantaneous
    // 15dp -> 22dp corner jump visible in the previous implementation.
    val shapeProgress = remember { Animatable(1f) }
    LaunchedEffect(safeSelectedIndex) {
        shapeProgress.snapTo(0f)
        shapeProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    val indicatorRadiusDp = neutralRadiusDp +
            (selectedRadiusDp - neutralRadiusDp) * shapeProgress.value.coerceIn(0f, 1f)
    val indicatorShape = RoundedCornerShape(indicatorRadiusDp.dp)

    LaunchedEffect(safeSelectedIndex, viewportWidthPx, contentWidthPx) {
        if (viewportWidthPx <= 0) return@LaunchedEffect
        val center = chipOffsetsPx[safeSelectedIndex] + chipWidthsPx[safeSelectedIndex] / 2f
        val target = (center - viewportWidthPx / 2f)
            .roundToInt()
            .coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(
            value = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .onSizeChanged { viewportWidthPx = it.width }
            .horizontalScroll(scrollState),
    ) {
        val contentWidth = with(density) { contentWidthPx.toDp() }
        Box(
            modifier = Modifier
                .width(contentWidth)
                .fillMaxHeight(),
        ) {
            // Neutral slots. Their visible part fades/morphs according to the
            // actual overlap with the travelling indicator, so no darker
            // squarish chip can remain visible behind the selected capsule.
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(sidePadding))
                labels.forEachIndexed { index, _ ->
                    val widthPx = chipWidthsPx[index]
                    val width = with(density) { widthPx.toDp() }
                    val slotStart = chipOffsetsPx[index]
                    val slotEnd = slotStart + widthPx
                    val indicatorEnd = indicatorX + indicatorWidth
                    val overlapPx = max(
                        0f,
                        min(slotEnd, indicatorEnd) - max(slotStart, indicatorX),
                    )
                    val overlapBase = min(widthPx, indicatorWidth).coerceAtLeast(1f)
                    val overlap = (overlapPx / overlapBase).coerceIn(0f, 1f)
                    val slotRadiusDp = neutralRadiusDp +
                            (indicatorRadiusDp - neutralRadiusDp) * overlap
                    val neutralShape = RoundedCornerShape(slotRadiusDp.dp)
                    val neutralAlpha = (1f - overlap).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(chipHeight)
                            .graphicsLayer { alpha = neutralAlpha }
                            .orbNavigationNeutralGlass(
                                hazeState = navigationHazeState,
                                reduceDynamicBlur = reduceDynamicBlur,
                                containerColor = neutralContainer,
                                borderColor = neutralBorder,
                                shape = neutralShape,
                            ),
                    )
                    if (index != labels.lastIndex) Spacer(Modifier.width(gap))
                }
                Spacer(Modifier.width(sidePadding))
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(indicatorX.roundToInt(), 0) }
                    .width(with(density) { indicatorWidth.toDp() })
                    .height(chipHeight)
                    .clip(indicatorShape)
                    .background(selectedContainer),
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(sidePadding))
                labels.forEachIndexed { index, label ->
                    val selected = index == safeSelectedIndex
                    val width = with(density) { chipWidthsPx[index].toDp() }
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) {
                            selectedContent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(180),
                        label = "orbPillTextColor$index",
                    )
                    val labelScale by animateFloatAsState(
                        targetValue = if (selected) 1.035f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "orbPillTextScale$index",
                    )
                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(chipHeight)
                            .clip(RoundedCornerShape(selectedRadiusDp.dp))
                            .clickable {
                                if (index != safeSelectedIndex) {
                                    OrbHaptics.perform(context, OrbHaptics.Kind.NAVIGATION)
                                    onSelected(index)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            modifier = Modifier.graphicsLayer {
                                scaleX = labelScale
                                scaleY = labelScale
                            },
                        ) {
                            icons.getOrNull(index)?.let { icon ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = labelColor,
                                    modifier = Modifier.size(iconSize),
                                )
                                Spacer(Modifier.width(iconGap))
                            }
                            Text(
                                text = label,
                                style = labelStyle,
                                color = labelColor,
                                maxLines = 1,
                            )
                        }
                    }
                    if (index != labels.lastIndex) Spacer(Modifier.width(gap))
                }
                Spacer(Modifier.width(sidePadding))
            }
        }
    }
}

/** Fixed Library header with in-place local library search. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LibraryFrostedHeader(
    account: Account?,
    hazeState: HazeState,
    title: String,
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onAccountClick: () -> Unit,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassColor = orbHeaderGlassColor()

    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus() else focusManager.clearFocus()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(glassColor)
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = orbHeaderGlassStyle(glassColor),
                        )
                    },
                ),
        ) {
            Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(62.dp),
        ) {
            HomeHeaderAvatar(
                account = account,
                size = 38.dp,
                iconSize = 21.dp,
                onClick = onAccountClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 12.dp),
            )

            if (searchActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 72.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            if (query.isBlank()) {
                                Text(
                                    text = stringResource(R.string.library_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        },
                    )
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 76.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = HeaderActionEndPadding,
                        top = HeaderActionTopPadding,
                    )
                    .size(HeaderActionTouchSize)
                    .clickable(onClick = if (searchActive) onCloseSearch else onSearchClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (searchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(
                        if (searchActive) R.string.library_search_close else R.string.library_search_open,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(HeaderActionIconSize),
                )
            }
        }

            Box(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(0.5.dp))
            }
        }

    }
}

/**
 * Fixed compact Expressive header for Explore. It intentionally mirrors the
 * collapsed Home header: account avatar on the left, a centered title and a
 * permanent second row. Explore replaces Home's feed pills with a search bar.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ExploreFrostedHeader(
    account: Account?,
    hazeState: HazeState,
    title: String,
    searchHint: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchActivated: () -> Unit,
    focusTrigger: Int = 0,
    onPeopleSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassColor = orbHeaderGlassColor()

    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            onSearchActivated()
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(glassColor)
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = orbHeaderGlassStyle(glassColor),
                        )
                    },
                ),
        ) {
            Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(62.dp),
        ) {
            HomeHeaderAvatar(
                account = account,
                size = 38.dp,
                iconSize = 21.dp,
                onClick = onAccountClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 12.dp),
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 76.dp),
            )

            IconButton(
                onClick = onPeopleSearchClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = HeaderActionEndPadding,
                        top = HeaderActionTopPadding,
                    )
                    .size(HeaderActionTouchSize),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonSearch,
                    contentDescription = stringResource(R.string.stats_user_search_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(HeaderActionIconSize),
                )
            }
        }

        ExpressiveSearchField(
            query = query,
            searchHint = searchHint,
            onQueryChange = onQueryChange,
            onSearchSubmit = onSearchSubmit,
            onSearchActivated = onSearchActivated,
            focusRequester = focusRequester,
            clearContentDescription = stringResource(R.string.beta_ui_clear_search),
        )

            Box(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(0.5.dp))
            }
        }

    }
}

@Composable
fun ExpressiveSearchField(
    query: String,
    searchHint: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchActivated: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    clearContentDescription: String? = null,
) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = { value ->
            onSearchActivated()
            onQueryChange(value)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSearchActivated()
                onSearchSubmit()
                focusManager.clearFocus()
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 18.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.68f))
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onSearchActivated() }
            .padding(horizontal = 14.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = searchHint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable {
                                onSearchActivated()
                                onQueryChange("")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = clearContentDescription ?: stringResource(R.string.beta_ui_clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Compact top-level header for Stats. It keeps the centered page title,
 * Home's account avatar in the exact same leading position, and a fixed-size
 * search affordance on the trailing edge. Search itself opens as a page state.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun StatsFrostedHeader(
    hazeState: HazeState,
    title: String,
    searchTitle: String,
    searchMode: Boolean,
    notificationCount: Int,
    onHistoryClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onBack: () -> Unit,
    periodLabels: List<String> = emptyList(),
    selectedPeriodIndex: Int = 0,
    onPeriodSelected: (Int) -> Unit = {},
    showPeriodSelector: Boolean = false,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassColor = orbHeaderGlassColor()

    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(glassColor)
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = orbHeaderGlassStyle(glassColor),
                        )
                    },
                ),
        ) {
            Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .height(62.dp),
        ) {
            if (searchMode) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 9.dp)
                        .size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.song_action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 7.dp)
                        .size(48.dp)
                        .clickable(onClick = onNotificationsClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Notifications,
                        contentDescription = stringResource(R.string.stats_notifications_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(25.dp),
                    )
                    if (notificationCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(if (notificationCount > 9) 20.dp else 18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (notificationCount > 99) "99+" else notificationCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.onError,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Text(
                text = if (searchMode) searchTitle else title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 76.dp),
            )

            if (!searchMode) {
                // Mirrors Home's trailing settings affordance: fixed 48dp hit
                // target, no decorative background, stable through scrolling.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 18.dp, top = 7.dp)
                        .size(48.dp)
                        .clickable(onClick = onHistoryClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = stringResource(R.string.playback_history_open),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        }

        if (showPeriodSelector && periodLabels.isNotEmpty()) {
            OrbBouncyPillSelector(
                labels = periodLabels,
                selectedIndex = selectedPeriodIndex,
                onSelected = onPeriodSelected,
            )
        }

            Box(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(0.5.dp))
            }
        }

    }
}

@Composable
private fun HomeHeaderAvatar(
    account: Account?,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = account?.thumbnailUrl
    if (photo != null) {
        AsyncImage(
            model = photo,
            contentDescription = stringResource(R.string.title_settings),
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .thumbnailBorder(CircleShape)
                .clickable(onClick = onClick),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (OrbFlavorUi.expressive) Modifier
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = stringResource(R.string.title_settings),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * The account affordance at the right end of the bar.
 *
 * It is the signed-in Google account's own photo — the same one YouTube Music
 * shows there — and tapping it opens Settings, where the account lives. Signed
 * out, or before the account menu has come back, it falls back to a person
 * glyph on a filled circle so the tap target never disappears.
 *
 * The hairline ring is what keeps a photo with light edges from dissolving into
 * the bar's glass; it is the same one thumbnails elsewhere carry.
 */
@Composable
fun TopBarAccountButton(
    account: Account?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Wrapped in an IconButton so it keeps the 48dp target, the ripple and the
    // spacing every other action in this bar has.
    IconButton(onClick = onClick, modifier = modifier) {
        val photo = account?.thumbnailUrl
        val avatarSize = if (OrbFlavorUi.expressive) 36.dp else AVATAR_SIZE
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = stringResource(R.string.title_settings),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .then(
                        if (OrbFlavorUi.expressive) Modifier
                        else Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = stringResource(R.string.title_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (OrbFlavorUi.expressive) 21.dp else 18.dp),
                )
            }
        }
    }
}



/**
 * The account photo's diameter.
 *
 * Smaller than an icon's 24dp box: a filled circle carries more weight than a
 * glyph does, and at 24 it sat heavier in the bar than the wordmark opposite it.
 */
private val AVATAR_SIZE = 28.dp
