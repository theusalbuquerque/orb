package com.music.orb.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.settings.AppSettings
import com.music.orb.ui.flavor.OrbFlavorUi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

data class BottomTab(
    val label: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FloatingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    notificationTabIndex: Int? = null,
    showNotificationDot: Boolean = false,
) {
    if (OrbFlavorUi.expressive) {
        ExpressiveFloatingBottomBar(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onTabSelected = onTabSelected,
            hazeState = hazeState,
            modifier = modifier,
            notificationTabIndex = notificationTabIndex,
            showNotificationDot = showNotificationDot,
        )
    } else {
        ClassicFloatingBottomBar(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onTabSelected = onTabSelected,
            hazeState = hazeState,
            modifier = modifier,
            notificationTabIndex = notificationTabIndex,
            showNotificationDot = showNotificationDot,
        )
    }
}

/**
 * Beta/dev navigation: a deliberately chunkier Material 3 Expressive dock.
 * The moving selection container is intentionally tall and saturated, while
 * the outer glass stays neutral so Dynamic Color remains in control.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ExpressiveFloatingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    notificationTabIndex: Int? = null,
    showNotificationDot: Boolean = false,
) {
    val outerShape = RoundedCornerShape(36.dp)
    val indicatorShape = RoundedCornerShape(26.dp)
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val selectedContainerTarget = when (selectedIndex % 3) {
        1 -> MaterialTheme.colorScheme.secondaryContainer
        2 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val selectedContentTarget = when (selectedIndex % 3) {
        1 -> MaterialTheme.colorScheme.onSecondaryContainer
        2 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val selectedContainer by animateColorAsState(
        targetValue = selectedContainerTarget,
        animationSpec = tween(180),
        label = "expressiveTabIndicatorColor",
    )
    val selectedContent by animateColorAsState(
        targetValue = selectedContentTarget,
        animationSpec = tween(180),
        label = "expressiveTabContentColor",
    )
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }
    val n = tabs.size

    val tabWidthPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width - gapPx * (n - 1)) / n
    } else 0f
    val tabStepPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width + gapPx) / n
    } else 0f
    val indicatorTargetPx = if (tabStepPx > 0f) {
        selectedIndex * tabStepPx + dragOffset
    } else 0f
    val animatedIndicatorOffset by animateFloatAsState(
        targetValue = indicatorTargetPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveTabIndicatorOffset",
    )

    var lastHapticTab by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) { dragOffset = 0f }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(outerShape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(container)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(container),
                    )
                },
            )
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = outerShape,
            )
            .padding(horizontal = 7.dp, vertical = 7.dp),
    ) {
        if (tabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .height(56.dp)
                    .graphicsLayer { translationX = animatedIndicatorOffset }
                    .clip(indicatorShape)
                    .background(selectedContainer),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragCancel = { dragOffset = 0f },
                        onDragEnd = {
                            if (tabStepPx > 0f) {
                                val ratio = totalDrag / tabStepPx
                                val shift = when {
                                    ratio > 0.35f -> kotlin.math.max(1, ratio.roundToInt())
                                    ratio < -0.35f -> kotlin.math.min(-1, ratio.roundToInt())
                                    else -> 0
                                }
                                val newIndex = (currentSelectedIndex + shift)
                                    .coerceIn(0, tabs.lastIndex)
                                if (newIndex != currentSelectedIndex) onTabSelected(newIndex)
                            }
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            totalDrag += delta
                            dragOffset = when {
                                totalDrag > 0 && currentSelectedIndex == tabs.lastIndex -> totalDrag * 0.25f
                                totalDrag < 0 && currentSelectedIndex == 0 -> totalDrag * 0.25f
                                else -> totalDrag
                            }
                            if (tabStepPx > 0f) {
                                val approxTab = (currentSelectedIndex + dragOffset / tabStepPx)
                                    .coerceIn(0f, tabs.lastIndex.toFloat())
                                    .roundToInt()
                                if (approxTab != lastHapticTab) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastHapticTab = approxTab
                                }
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                ExpressiveBottomBarItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    selectedContentColor = selectedContent,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                    showNotificationDot = showNotificationDot && index == notificationTabIndex,
                )
            }
        }
    }
}

@Composable
private fun ExpressiveBottomBarItem(
    tab: BottomTab,
    selected: Boolean,
    selectedContentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showNotificationDot: Boolean = false,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveTabScale",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) {
            selectedContentColor
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "expressiveTabTint",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(26.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(top = 7.dp, bottom = 5.dp),
    ) {
        val notificationScale by animateFloatAsState(
            targetValue = if (showNotificationDot) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "expressiveStatsNotificationDot",
        )
        Box(
            modifier = Modifier.size(31.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(27.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .graphicsLayer {
                        scaleX = notificationScale
                        scaleY = notificationScale
                    }
                    .clip(RoundedCornerShape(4.5.dp))
                    .background(MaterialTheme.colorScheme.tertiary)
                    .border(
                        1.25.dp,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(4.5.dp),
                    ),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ClassicFloatingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    notificationTabIndex: Int? = null,
    showNotificationDot: Boolean = false,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }
    val n = tabs.size

    val tabWidthPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width - gapPx * (n - 1)) / n
    } else 0f
    val tabStepPx = if (rowSize.width > 0 && n > 0) {
        (rowSize.width + gapPx) / n
    } else 0f

    val pillTargetPx = if (tabStepPx > 0f) {
        selectedIndex * tabStepPx + dragOffset
    } else 0f

    val animatedPillOffset by animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pillOffset",
    )

    var lastHapticTab by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(selectedIndex) { dragOffset = 0f }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .clip(pillShape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(container)
                } else {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(container),
                    )
                },
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), pillShape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (tabWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .height(with(density) { rowSize.height.toDp() })
                    .graphicsLayer { translationX = animatedPillOffset }
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragCancel = { dragOffset = 0f },
                        onDragEnd = {
                            if (tabStepPx > 0f) {
                                val ratio = totalDrag / tabStepPx
                                val shift = when {
                                    ratio > 0.35f -> kotlin.math.max(1, ratio.roundToInt())
                                    ratio < -0.35f -> kotlin.math.min(-1, ratio.roundToInt())
                                    else -> 0
                                }
                                val newIndex = (currentSelectedIndex + shift).coerceIn(0, tabs.lastIndex)
                                if (newIndex != currentSelectedIndex) {
                                    onTabSelected(newIndex)
                                }
                            }
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            totalDrag += delta
                            val rawPx = when {
                                totalDrag > 0 && currentSelectedIndex == tabs.lastIndex ->
                                    totalDrag * 0.25f
                                totalDrag < 0 && currentSelectedIndex == 0 ->
                                    totalDrag * 0.25f
                                else -> totalDrag
                            }
                            dragOffset = rawPx

                            val approxTab =
                                (currentSelectedIndex + dragOffset / tabStepPx)
                                    .coerceIn(0f, tabs.lastIndex.toFloat())
                                    .roundToInt()
                            if (approxTab != lastHapticTab) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastHapticTab = approxTab
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                ClassicBottomBarItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                    showNotificationDot = showNotificationDot && index == notificationTabIndex,
                )
            }
        }
    }
}

@Composable
private fun ClassicBottomBarItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showNotificationDot: Boolean = false,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tabScale",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "tabTint",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 7.dp),
    ) {
        Box(
            modifier = Modifier.size(29.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(25.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
            if (showNotificationDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
