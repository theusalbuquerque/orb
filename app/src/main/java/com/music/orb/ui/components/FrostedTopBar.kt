@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * Lighter glass used by Orb's ordinary fixed headers.
 *
 * The previous HazeMaterials.ultraThin preset used a 55% tint on dark
 * surfaces (35% on light). For this trial we keep the same 24 dp blur while
 * letting substantially more of the page show through: 30% tint on dark
 * surfaces and 22% on light surfaces.
 */
private fun orbHeaderGlassStyle(containerColor: Color): HazeStyle = HazeStyle(
    blurRadius = 24.dp,
    backgroundColor = containerColor,
    tint = HazeTint(
        containerColor.copy(
            alpha = if (containerColor.luminance() >= 0.5f) 0.22f else 0.30f,
        ),
    ),
)

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
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(220),
        label = "topBarTitleAlpha",
    )
    val dividerColor by animateColorAsState(
        targetValue = (
            if (OrbFlavorUi.expressive) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.outline
        ).copy(
            alpha = if (scrolled && ownBackdrop) {
                if (OrbFlavorUi.expressive) 0.48f else 0.6f
            } else {
                0f
            },
        ),
        animationSpec = tween(220),
        label = "topBarDivider",
    )
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    !ownBackdrop -> Modifier
                    reduceDynamicBlur -> Modifier.background(
                        if (OrbFlavorUi.expressive) {
                            MaterialTheme.colorScheme.surfaceContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    else -> Modifier.hazeEffect(
                        state = hazeState,
                        style = orbHeaderGlassStyle(
                            if (OrbFlavorUi.expressive) {
                                MaterialTheme.colorScheme.surfaceContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
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
                    (slideInVertically(tween(260)) { height -> height / 2 } + fadeIn(tween(190))) togetherWith
                            (slideOutVertically(tween(210)) { height -> -height / 3 } + fadeOut(tween(150)))
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
                        contentDescription = "Back",
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
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
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
        // The divider and the loader line share the bar's bottom edge; the box
        // only grows to the line's height while a refresh is actually showing.
        Box(Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
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
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    // Use exactly the same glass recipe as the Settings top bar. Keeping the
    // material color opaque here is intentional: HazeMaterials owns the tint
    // and transparency; pre-dimming it changes the blur response.
    val glassColor = MaterialTheme.colorScheme.surfaceContainer
    val dividerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = if (scrolled) 0.42f else 0f,
        ),
        animationSpec = tween(220),
        label = "homeHeaderDivider",
    )
    val greeting = remember(account?.name) {
        val firstName = account?.name?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val hour = java.time.LocalTime.now().hour
        val hello = when (hour) {
            in 5..11 -> "Bom dia"
            in 12..17 -> "Boa tarde"
            in 18..23 -> "Boa noite"
            else -> "Oi"
        }
        if (firstName != null) "$hello, $firstName" else hello
    }

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
    val settingsTop by animateDpAsState(
        targetValue = if (scrolled) 12.dp else 10.dp,
        animationSpec = tween(240),
        label = "homeHeaderSettingsTop",
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
        modifier = modifier
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

            // Settings stays visually stable while Home collapses: only the
            // surrounding header moves. The affordance has no decorative
            // background, so its 48 dp touch target never reads as another chip.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 18.dp, top = settingsTop)
                    .size(48.dp)
                    .clickable(onClick = onSettingsClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.title_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(25.dp),
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
                    .padding(start = greetingX, top = greetingY, end = 78.dp),
            )
        }

        // The feed selector is a permanent second pane of the header. Keep the
        // selected chip fully visible even when its label is wider than the
        // available remainder of the row (notably Community playlists).
        val filterListState = rememberLazyListState()
        LaunchedEffect(selectedSection, sections.size) {
            if (sections.isNotEmpty()) {
                filterListState.animateScrollToCenteredItem(
                    selectedSection.coerceIn(sections.indices),
                )
            }
        }
        LazyRow(
            state = filterListState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
        ) {
            itemsIndexed(sections) { index, label ->
                val selected = selectedSection == index
            val background = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    // Opaque + highest surface elevation keeps the chip visible
                    // against the light header, where the former translucent fill
                    // could disappear almost completely.
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            val outline = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            }
            val chipShapeRadius by animateDpAsState(
                targetValue = if (selected) 22.dp else 15.dp,
                animationSpec = tween(durationMillis = 300),
                label = "homeFilterShape",
            )
            val chipScale by animateFloatAsState(
                targetValue = if (selected) 1.015f else 1f,
                animationSpec = tween(durationMillis = 260),
                label = "homeFilterScale",
            )
            val animatedBackground by animateColorAsState(
                targetValue = background,
                animationSpec = tween(durationMillis = 280),
                label = "homeFilterBackground",
            )
            val animatedOutline by animateColorAsState(
                targetValue = outline,
                animationSpec = tween(durationMillis = 280),
                label = "homeFilterOutline",
            )
            val chipShape = RoundedCornerShape(chipShapeRadius)
            Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier
                    .graphicsLayer {
                        scaleX = chipScale
                        scaleY = chipScale
                    }
                    .clip(chipShape)
                    .background(animatedBackground)
                    .border(1.dp, animatedOutline, chipShape)
                        .clickable { onSectionSelected(index) }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
        }


        Box(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(0.5.dp))
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    val glassColor = MaterialTheme.colorScheme.surfaceContainer
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus() else focusManager.clearFocus()
    }

    Column(
        modifier = modifier
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
                    .padding(end = 18.dp, top = 7.dp)
                    .size(48.dp)
                    .clickable(onClick = if (searchActive) onCloseSearch else onSearchClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (searchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = stringResource(
                        if (searchActive) R.string.library_search_close else R.string.library_search_open,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(25.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(0.5.dp))
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    onHistoryClick: () -> Unit = {},
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassColor = MaterialTheme.colorScheme.surfaceContainer
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            onSearchActivated()
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
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
                onClick = onHistoryClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 54.dp, top = 9.dp)
                    .size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = stringResource(R.string.playback_history_open),
                    tint = MaterialTheme.colorScheme.onSurface,
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
            clearContentDescription = "Limpar pesquisa",
        )

        Box(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(0.5.dp))
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    clearContentDescription: String = "Clear search",
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
                            contentDescription = clearContentDescription,
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
    onSearchClick: () -> Unit,
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
    val glassColor = MaterialTheme.colorScheme.surfaceContainer
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)

    Column(
        modifier = modifier
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
                        .clickable(onClick = onSearchClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = searchTitle,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        }

        if (showPeriodSelector && periodLabels.isNotEmpty()) {
            val periodListState = rememberLazyListState()
            LaunchedEffect(selectedPeriodIndex, periodLabels.size) {
                periodListState.animateScrollToCenteredItem(
                    selectedPeriodIndex.coerceIn(periodLabels.indices),
                )
            }
            LazyRow(
                state = periodListState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            ) {
                itemsIndexed(periodLabels) { index, label ->
                    val active = index == selectedPeriodIndex
                    val background = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val outline = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                    }
                    val radius by animateDpAsState(
                        targetValue = if (active) 22.dp else 15.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "statsHeaderPeriodRadius",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (active) 1.04f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "statsHeaderPeriodScale",
                    )
                    val fill by animateColorAsState(
                        targetValue = background,
                        animationSpec = tween(260),
                        label = "statsHeaderPeriodFill",
                    )
                    val stroke by animateColorAsState(
                        targetValue = outline,
                        animationSpec = tween(260),
                        label = "statsHeaderPeriodStroke",
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(RoundedCornerShape(radius))
                            .background(fill)
                            .border(1.dp, stroke, RoundedCornerShape(radius))
                            .clickable { onPeriodSelected(index) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(0.5.dp))
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
                .background(MaterialTheme.colorScheme.primaryContainer)
                .thumbnailBorder(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = stringResource(R.string.title_settings),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                contentDescription = "Settings",
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
                    .background(
                        if (OrbFlavorUi.expressive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .thumbnailBorder(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = "Settings",
                    tint = if (OrbFlavorUi.expressive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(if (OrbFlavorUi.expressive) 21.dp else 18.dp),
                )
            }
        }
    }
}

/**
 * The refresh indicator: a line along the bottom of the bar, directly under the
 * status bar. It tracks the drag on the way down — filling left to right as the
 * pull approaches the threshold — then sweeps indefinitely once the refresh is
 * away, so the two phases read as one continuous gesture.
 */
@Composable
private fun RefreshLine(refreshing: Boolean, pullFraction: () -> Float, modifier: Modifier = Modifier) {
    val fraction = pullFraction()
    val pulling = fraction > 0.01f
    AnimatedVisibility(
        visible = refreshing || pulling,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val lineModifier = Modifier
            .fillMaxWidth()
            .height(LINE_HEIGHT)
        if (refreshing) {
            LinearProgressIndicator(
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

private val LINE_HEIGHT = 2.5.dp

/**
 * The account photo's diameter.
 *
 * Smaller than an icon's 24dp box: a filled circle carries more weight than a
 * glyph does, and at 24 it sat heavier in the bar than the wordmark opposite it.
 */
private val AVATAR_SIZE = 28.dp
