package com.music.orb.ui.components

import android.os.Build
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

/**
 * Fixed-position dialog sheet host for the Material 3 version used by Orb.
 *
 * Material3 ModalBottomSheet always installs its own anchored drag and nested-scroll
 * handling in this version: confirmValueChange only rejects Hidden and DOES NOT
 * prevent visible sheet movement. A bottom-aligned Dialog has no anchored drag
 * handler. Scrolling and tapping descendants therefore never moves the surface.
 * Dismiss gestures are implemented exclusively in the small, top handle hitbox.
 *
 * This host intentionally does not add a pointerInput/nestedScroll interceptor to
 * the sheet body; doing so delays clicks and interferes with LazyColumn scrolling.
 */
@Composable
fun OrbFixedSheetHost(
    containerColor: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
    scrimColor: Color = Color.Black.copy(alpha = 0.10f),
    floatingTitle: String? = null,
    floatingSubtitle: String? = null,
    panelTranslationPx: Float = 0f,
    titleOpacity: Float = 1f,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = {}, // Scrim never dismisses; handle or explicit Back only.
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // A single 10% scrim covers the entire backdrop, including the area
        // behind the translucent panel. Dividing it at the panel's top edge
        // created a visible brightness seam when the sheet opened/moved.
        // The glass is drawn afterwards and keeps its contextual tint/blur.
        var panelHeightPx by remember { mutableIntStateOf(0) }
        val dialogView = LocalView.current
        val density = LocalDensity.current
        DisposableEffect(dialogView) {
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            if (dialogWindow == null) {
                onDispose { }
            } else {
                // Dialog must draw edge-to-edge. Otherwise its bottom edge is
                // above Android's gesture bar even if children request nav insets.
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                val oldDimAmount = dialogWindow.attributes.dimAmount
                val hadDimFlag = (dialogWindow.attributes.flags and
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0
                dialogWindow.setDimAmount(0f)
                onDispose {
                    dialogWindow.setDimAmount(oldDimAmount)
                    if (!hadDimFlag) dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().drawBehind {
                // Dim the full underlying screen uniformly, even below the
                // sheet; the panel material is composited over this backdrop.
                drawRect(scrimColor)
            },
        ) {
            val availableHeight = maxHeight
            // The title belongs to the vacant area OUTSIDE the glass. It does
            // not intercept the handle or any clickable list item.
            if (!floatingTitle.isNullOrBlank()) {
                val emptyAreaPx = with(density) { maxHeight.toPx() } - panelHeightPx
                if (panelHeightPx > 0 && emptyAreaPx > with(density) { 56.dp.toPx() }) {
                    val titleColor = MaterialTheme.colorScheme.onSurface
                    val outline = if (titleColor.luminance() > 0.5f) Color.Black else Color.White
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(with(density) { emptyAreaPx.toDp() })
                            .offset { IntOffset(0, -(panelTranslationPx * 0.14f).roundToInt()) }
                            .alpha(titleOpacity.coerceIn(0f, 1f))
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = floatingTitle,
                            color = titleColor,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                shadow = Shadow(outline.copy(alpha = 0.18f), Offset.Zero, 8f),
                            ),
                            maxLines = 2,
                        )
                        if (!floatingSubtitle.isNullOrBlank()) {
                            Text(
                                text = floatingSubtitle,
                                color = titleColor.copy(alpha = 0.80f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    shadow = Shadow(outline.copy(alpha = 0.16f), Offset.Zero, 6f),
                                ),
                                modifier = Modifier.padding(top = 5.dp),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = availableHeight)
                    .offset { IntOffset(0, panelTranslationPx.roundToInt()) }
                    // Do not add elevation here: this Column also owns any floating
                    // cover/CD hero, which begins above the bottom-sheet boundary.
                    // Elevation belongs to each concrete panel (not the host).
                    .onSizeChanged { panelHeightPx = it.height }
                    .then(if (containerColor == Color.Transparent) Modifier else Modifier.clip(shape))
                    .background(containerColor),
                verticalArrangement = Arrangement.Top,
                content = content,
            )
            // Window-root overlay, so a flying rating star stays visible after it
            // leaves the bottom sheet and while the panel animates away.
            overlay()
        }
    }
}

/** Material elevation belongs to the actual panel, never the floating hero/host. */
fun Modifier.orbSheetPanelElevation(shape: Shape): Modifier = this.shadow(
    elevation = 14.dp,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.27f),
    spotColor = Color.Black.copy(alpha = 0.36f),
)

/**
 * The only allowed drag-to-dismiss area is the top handle (26dp). Unlike the
 * Material 3 built-in ModalBottomSheet, the rest of the sheet is completely
 * stationary while the content scrolls, including at the ends of lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbModalBottomSheet(
    onDismissRequest: () -> Unit,
    dismissEnabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") sheetState: SheetState? = null,
    containerColor: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
    scrimColor: Color = Color.Black.copy(alpha = 0.10f),
    navigationBarInsetInContent: Boolean = false,
    floatingTitle: String? = null,
    floatingSubtitle: String? = null,
    dragHandle: (@Composable () -> Unit)? = null,
    closeRequested: Boolean = false,
    closeAnimationMillis: Long = 390L,
    onCloseStarted: (Boolean) -> Unit = {},
    onHandleMotion: (Float, Float) -> Unit = { _, _ -> },
    onOpened: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val travelPx = with(density) { (configuration.screenHeightDp + 72).dp.toPx() }
    val threshold = with(density) { 72.dp.toPx() }
    val minVelocity = with(density) { 1050.dp.toPx() }
    var entered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var handleOffsetPx by remember { mutableFloatStateOf(0f) }
    var handleWasDragged by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val panelOffsetPx by animateFloatAsState(
        targetValue = if (!entered || closing) travelPx else handleOffsetPx,
        animationSpec = if (dragging) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "orbSheetHandleTranslation",
    )
    val requestClose: (Boolean) -> Unit = { fromHandle ->
        if (dismissEnabled && !closing) {
            dragging = false
            closing = true
            onCloseStarted(fromHandle)
            scope.launch {
                // Collection covers/discs need time to retract before unmounting.
                delay(closeAnimationMillis)
                currentDismiss()
            }
        }
    }
    LaunchedEffect(closeRequested) {
        if (closeRequested) requestClose(false)
    }
    LaunchedEffect(entered, closing) {
        if (entered && !closing) {
            delay(600L)
            if (!closing) onOpened()
        }
    }
    LaunchedEffect(panelOffsetPx, handleOffsetPx, entered, closing, dragging, handleWasDragged) {
        // Track the entire drag AND spring-back, but ignore entrance travel.
        if (entered && (handleWasDragged || closing)) {
            onHandleMotion(panelOffsetPx, handleOffsetPx)
            if (!dragging && !closing && handleOffsetPx == 0f && panelOffsetPx < 0.5f) {
                handleWasDragged = false
            }
        } else {
            onHandleMotion(0f, 0f)
        }
    }
    val navbarTone = orbExpressiveGlassTone(MaterialTheme.colorScheme.surfaceContainerHigh)
    OrbFixedSheetHost(
        containerColor = containerColor,
        shape = shape,
        scrimColor = scrimColor,
        floatingTitle = floatingTitle,
        floatingSubtitle = floatingSubtitle,
        panelTranslationPx = panelOffsetPx,
        titleOpacity = (1f - panelOffsetPx / with(density) { 220.dp.toPx() }).coerceIn(0f, 1f),
        overlay = overlay,
    ) {
        OrbModalBackHandler { requestClose(false) }
        // When the glass itself paints the navigation inset, keep the system
        // navigation bar transparent so no solid, differently tinted strip
        // interrupts the frosted surface above the gesture handle.
        OrbSheetNavigationBarTone(navbarTone, transparent = true)
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Top) {
                if (dragHandle != null) dragHandle()
                content()
                if (!navigationBarInsetInContent) {
                    // For custom sheets not using OrbGlassSheetSurface, paint a
                    // continuous glass footer instead of an opaque separate strip.
                    Box(
                        Modifier.fillMaxWidth()
                            .windowInsetsBottomHeight(WindowInsets.navigationBars)
                            .orbGlassSurface(
                                pageColor = orbOverlayGlassBaseColor(),
                                cornerRadius = 0.dp,
                                backdropBlur = 56.dp,
                                overlayStrength = true,
                                overlayOpacity = 0.40f,
                                lightweightSheetMaterial = true,
                                sheetTintMode = OrbSheetTintMode.BACKDROP,
                                manageDialogBackdrop = false,
                            ),
                    )
                }
            }
            // Overlay restricted to the grip; no gesture interception on rows.
            Box(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(26.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            handleOffsetPx = (handleOffsetPx + delta).coerceIn(0f, travelPx)
                        },
                        onDragStarted = {
                            dragging = true
                            handleWasDragged = true
                        },
                        onDragStopped = { velocity ->
                            dragging = false
                            if (handleOffsetPx >= threshold || velocity > minVelocity) {
                                requestClose(true)
                            } else {
                                // Release below threshold springs the *whole sheet* back.
                                handleOffsetPx = 0f
                            }
                        },
                    ),
            )
        }
    }
}

@Composable
internal fun OrbModalBackHandler(onBack: () -> Unit) {
    val view = LocalView.current
    val currentOnBack by rememberUpdatedState(onBack)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window == null) {
                onDispose { }
            } else {
                val callback = OnBackInvokedCallback { currentOnBack() }
                val dispatcher = window.onBackInvokedDispatcher
                dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback,
                )
                onDispose { dispatcher.unregisterOnBackInvokedCallback(callback) }
            }
        }
    } else {
        BackHandler { currentOnBack() }
    }
}

@Composable
private fun OrbSheetNavigationBarTone(color: Color, transparent: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view, color, transparent) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousColor = window.navigationBarColor
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            val previousLight = controller.isAppearanceLightNavigationBars
            val previousContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else null
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (transparent) Color.Transparent.toArgb() else color.toArgb()
            controller.isAppearanceLightNavigationBars = color.luminance() > 0.5f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            onDispose {
                @Suppress("DEPRECATION")
                window.navigationBarColor = previousColor
                controller.isAppearanceLightNavigationBars = previousLight
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && previousContrast != null) {
                    window.isNavigationBarContrastEnforced = previousContrast
                }
            }
        }
    }
}
