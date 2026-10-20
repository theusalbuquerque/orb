package com.music.orb.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.music.orb.ui.components.OrbModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.music.orb.R
import com.music.orb.cast.OrbCastBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class CastOutputRow(
    val route: MediaRouter.RouteInfo,
    val id: String,
    val name: String,
    val selected: Boolean,
    val icon: ImageVector,
)

private fun outputSelector(castSelector: MediaRouteSelector?): MediaRouteSelector =
    MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
        .apply { if (castSelector != null) addSelector(castSelector) }
        .build()

private fun localRouteIcon(route: MediaRouter.RouteInfo?): ImageVector {
    if (route == null || route.isDefault || !route.isSelected) return Icons.Rounded.PhoneAndroid
    val lower = route.name.lowercase()
    return when {
        lower.contains("buds") || lower.contains("earbud") || lower.contains("airpods") ||
            lower.contains("galaxy buds") || lower.contains("pixel buds") -> Icons.Rounded.Headset
        route.isBluetooth || lower.contains("headphone") || lower.contains("headset") ||
            lower.contains("fone") || lower.contains("auricular") -> Icons.Rounded.Headphones
        else -> Icons.Rounded.PhoneAndroid
    }
}

private fun castRouteIcon(name: String): ImageVector {
    val lower = name.lowercase()
    return if (lower.contains("tv") || lower.contains("chromecast") || lower.contains("google tv")) {
        Icons.Rounded.Tv
    } else {
        Icons.Rounded.Cast
    }
}

/**
 * MediaRouter's dynamic-group methods are provider dependent. Reflection keeps
 * Orb compatible with MediaRouter builds where those methods are absent while
 * still enabling multi-endpoint Cast groups when the active provider supports them.
 */
private fun MediaRouter.tryAddDynamicMember(route: MediaRouter.RouteInfo): Boolean = runCatching {
    val method = javaClass.methods.firstOrNull {
        it.name == "addMemberToDynamicGroup" && it.parameterTypes.size == 1
    } ?: return@runCatching false
    method.invoke(this, route)
    true
}.getOrDefault(false)

private fun MediaRouter.tryRemoveDynamicMember(route: MediaRouter.RouteInfo): Boolean = runCatching {
    val method = javaClass.methods.firstOrNull {
        it.name == "removeMemberFromDynamicGroup" && it.parameterTypes.size == 1
    } ?: return@runCatching false
    method.invoke(this, route)
    true
}.getOrDefault(false)

private fun MediaRouter.RouteInfo.dynamicMemberIds(): Set<String> = runCatching {
    val method = javaClass.methods.firstOrNull {
        (it.name == "getMemberRoutes" || it.name == "getRoutesInGroup") && it.parameterTypes.isEmpty()
    } ?: return@runCatching emptySet()
    val value = method.invoke(this)
    val routes = when (value) {
        is Collection<*> -> value.filterIsInstance<MediaRouter.RouteInfo>()
        is Array<*> -> value.filterIsInstance<MediaRouter.RouteInfo>()
        else -> emptyList()
    }
    routes.mapTo(linkedSetOf()) { it.id }
}.getOrDefault(emptySet())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbOutputSheet(
    pageColor: Color? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val router = remember(context) { MediaRouter.getInstance(context) }
    val castContext = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }
    val castSelector = castContext?.mergedSelector
    val selector = remember(castSelector) { outputSelector(castSelector) }
    val connected by OrbCastBridge.connected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var castPopupOpen by remember { mutableStateOf(false) }

    DisposableEffect(router, selector) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        onDispose { router.removeCallback(callback) }
    }

    @Suppress("UNUSED_EXPRESSION")
    revision

    val selectedRoute = router.routes.firstOrNull { it.isSelected }
    val selectedIsCast = selectedRoute != null && castSelector != null &&
        !selectedRoute.isDefault && !selectedRoute.isBluetooth && selectedRoute.matchesSelector(castSelector)
    val localRoute = selectedRoute.takeIf { !selectedIsCast }
    val localAccessoryInUse = localRoute?.takeIf {
        it.isSelected && !it.isDefault && (
            it.isBluetooth || it.name.lowercase().let { name ->
                name.contains("buds") || name.contains("ear") || name.contains("head") ||
                    name.contains("fone") || name.contains("auricular")
            }
        )
    }
    val castName = OrbCastBridge.currentCastDeviceName
    val glassColor = pageColor ?: orbOverlayGlassBaseColor()

    RegisterSheetBackdrop()
    ModalBottomSheet(
        floatingTitle = stringResource(R.string.now_playing_output_title),
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = null,
        navigationBarInsetInContent = true,
        ) {
        OrbGlassSheetSurface(
            pageColor = glassColor,
            cornerRadius = 36.dp,
            modifier = Modifier.blur(if (castPopupOpen) 18.dp else 0.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                // The floating heading is already displayed above the sheet.
                // Do not repeat the same title inside its content.
                OutputDestinationRow(
                    icon = localRouteIcon(localAccessoryInUse),
                    title = stringResource(R.string.now_playing_output_this_device),
                    subtitle = localAccessoryInUse?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.now_playing_output_phone_subtitle),
                    selected = !connected && !selectedIsCast,
                    onClick = {
                        if (connected || selectedIsCast) {
                            scope.launch {
                                OrbCastBridge.transferToLocalOutput()
                                delay(120L)
                            }
                        }
                    },
                )

                Spacer(Modifier.height(10.dp))

                OutputDestinationRow(
                    icon = if (connected || selectedIsCast) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                    title = stringResource(R.string.now_playing_output_cast_title),
                    subtitle = if ((connected || selectedIsCast) && !castName.isNullOrBlank()) {
                        castName
                    } else {
                        stringResource(R.string.now_playing_output_cast_subtitle)
                    },
                    selected = connected || selectedIsCast,
                    onClick = { castPopupOpen = true },
                )
            }
        }
    }

    if (castPopupOpen) {
        CastOutputPopup(
            router = router,
            castSelector = castSelector,
            glassColor = glassColor,
            onDismiss = { castPopupOpen = false },
        )
    }
}

@Composable
private fun OutputDestinationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        onClick = OrbHaptics.transportClick(onClick),
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (selected) 0.12f else 0.055f,
        ),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CastOutputPopup(
    router: MediaRouter,
    castSelector: MediaRouteSelector?,
    glassColor: Color,
    onDismiss: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    var pendingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(router, castSelector) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++; pendingId = null }
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) { revision++ }
        }
        if (castSelector != null) {
            router.addCallback(castSelector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        }
        onDispose { router.removeCallback(callback) }
    }

    LaunchedEffect(pendingId) {
        if (pendingId != null) {
            delay(12_000L)
            pendingId = null
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    revision

    val selectedRoute = router.routes.firstOrNull { it.isSelected }
    val groupMembers = selectedRoute?.dynamicMemberIds().orEmpty()
    val rows = router.routes
        .filter { route ->
            castSelector != null && route.isEnabled && !route.isDefault && !route.isBluetooth &&
                route.matchesSelector(castSelector)
        }
        .distinctBy { it.id }
        .map { route ->
            CastOutputRow(
                route = route,
                id = route.id,
                name = route.name,
                selected = route.isSelected || route.id in groupMembers,
                icon = castRouteIcon(route.name),
            )
        }
        .sortedWith(compareByDescending<CastOutputRow> { it.selected }.thenBy { it.name.lowercase() })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        val density = LocalDensity.current
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val blurPx = with(density) { 72.dp.roundToPx() }
        DisposableEffect(dialogWindow, blurPx) {
            if (dialogWindow == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                onDispose { }
            } else {
                val oldBlurBehind = dialogWindow.attributes.blurBehindRadius
                val hadBlurFlag = dialogWindow.attributes.flags and
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
                dialogWindow.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                )
                dialogWindow.setDimAmount(0f)
                dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    blurBehindRadius = maxOf(blurBehindRadius, blurPx)
                }
                runCatching { dialogWindow.setBackgroundBlurRadius(blurPx) }
                onDispose {
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        blurBehindRadius = oldBlurBehind
                    }
                    runCatching { dialogWindow.setBackgroundBlurRadius(0) }
                    if (!hadBlurFlag && oldBlurBehind == 0) {
                        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.13f))
                .padding(horizontal = 22.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            OrbGlassContentTheme(glassColor) {
                Surface(
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(32.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .orbGlassSurface(
                            pageColor = glassColor,
                            cornerRadius = 32.dp,
                            backdropBlur = 72.dp,
                            overlayStrength = true,
                            overlayOpacity = 0.52f,
                            lightweightSheetMaterial = true,
                        ),
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Text(
                            text = stringResource(R.string.now_playing_output_cast_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = stringResource(R.string.now_playing_output_cast_multi_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))

                        if (rows.isEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 22.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.size(12.dp))
                                Text(
                                    stringResource(R.string.cast_sheet_searching),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            val motionListState = rememberLazyListState()
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                                state = motionListState,
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                                    val connecting = pendingId == row.id
                                    val rowShape = RoundedCornerShape(22.dp)
                                    Surface(
                                        onClick = OrbHaptics.transportClick {
                                            if (pendingId != null) return@transportClick
                                            pendingId = row.id
                                            if (row.selected) {
                                                val selectedCount = rows.count { it.selected }
                                                val removed = selectedCount > 1 && router.tryRemoveDynamicMember(row.route)
                                                if (!removed && selectedCount <= 1) {
                                                    router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
                                                }
                                            } else {
                                                val alreadyCasting = rows.any { it.selected }
                                                if (!alreadyCasting || !router.tryAddDynamicMember(row.route)) {
                                                    runCatching { row.route.select() }
                                                        .onFailure { pendingId = null }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                            .orbSheetProgressiveLazyItem(motionListState, index),
                                        shape = rowShape,
                                        color = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (row.selected) 0.12f else 0.055f,
                                        ),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        tonalElevation = 0.dp,
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = row.icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(26.dp),
                                            )
                                            Spacer(Modifier.size(12.dp))
                                            Text(
                                                row.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            when {
                                                connecting -> CircularProgressIndicator(
                                                    Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                row.selected -> Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
