package com.music.orb.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import com.music.orb.ui.components.OrbModalBottomSheet as ModalBottomSheet
import com.music.orb.ui.components.OrbGlassFilledTonalButton as FilledTonalButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.music.orb.R
import com.music.orb.cast.OrbCastBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private data class CastRouteRow(
    val route: MediaRouter.RouteInfo,
    val id: String,
    val name: String,
    val selected: Boolean,
    val volume: Int,
    val volumeMax: Int,
    val adjustableVolume: Boolean,
)

/** Compose owns the surface; MediaRouter still owns discovery and session selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbCastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val castContext = remember(context) { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }
    val router = remember(context) { MediaRouter.getInstance(context) }
    val selector = castContext?.mergedSelector
    val connected by OrbCastBridge.connected.collectAsStateWithLifecycle()
    var routeRevision by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var scanAttempt by remember { mutableIntStateOf(0) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    var connectionFailed by remember { mutableStateOf(false) }

    val discoveryScope = rememberCoroutineScope()
    DisposableEffect(context) {
        // mDNS discovery must receive multicast from the entire LAN, regardless of Wi-Fi band.
        val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val multicast = runCatching {
            wifi?.createMulticastLock("orb-cast-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                discoveryScope.launch { scanAttempt++ }
            }
            override fun onLost(network: android.net.Network) {
                discoveryScope.launch { scanAttempt++ }
            }
        }
        val registered = runCatching {
            connectivity?.registerNetworkCallback(android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            connectivity != null
        }.getOrDefault(false)
        onDispose {
            if(registered) runCatching {connectivity?.unregisterNetworkCallback(networkCallback)}
            runCatching {if(multicast?.isHeld == true) multicast.release()}
        }
    }

    DisposableEffect(router, selector, scanning) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { routeRevision++ }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { routeRevision++ }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { routeRevision++ }
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) { routeRevision++ }
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) { routeRevision++ }
            override fun onRouteVolumeChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { routeRevision++ }
        }
        if (selector != null) {
            router.addCallback(selector, callback, if (scanning)
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        onDispose { router.removeCallback(callback) }
    }
    LaunchedEffect(scanAttempt) {
        scanning = true
        delay(15000)
        scanning = false
    }
    // Snapshot mutable RouteInfo properties: route changes can keep the same object identity.
    val rows = remember(routeRevision, selector, connected) {
        router.routes.filter { route ->
            selector != null && route.isEnabled && !route.isDefault && !route.isBluetooth &&
                route.matchesSelector(selector)
        }.map { route ->
            CastRouteRow(route, route.id, route.name, route.isSelected, route.volume,
                route.volumeMax, route.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
        }.sortedWith(compareByDescending<CastRouteRow> { it.selected }.thenBy { it.name.lowercase() })
    }
    val selected = rows.firstOrNull { it.selected }
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            delay(15000)
            pendingRoute = null
            connectionFailed = true
        }
    }
    LaunchedEffect(connected, selected?.id) {
        if (connected && selected?.id == pendingRoute) pendingRoute = null
    }

    RegisterSheetBackdrop()
    val sheetGlassColor = orbOverlayGlassBaseColor()
    ModalBottomSheet(
        floatingTitle = stringResource(R.string.cast_audio),
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = null,
        navigationBarInsetInContent = true,
        ) {
        OrbGlassSheetSurface(sheetGlassColor, 36.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(if (connected) Icons.Rounded.CastConnected else Icons.Rounded.Cast, null,
                        Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.cast_audio), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cast_sheet_wifi), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (castContext == null || connectionFailed) {
                Text(stringResource(R.string.cast_failed), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
            if (rows.isEmpty() && castContext != null) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                            Spacer(Modifier.height(16.dp))
                        }
                        Text(stringResource(if (scanning) R.string.cast_sheet_searching else R.string.cast_sheet_empty),
                            style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.cast_sheet_same_wifi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val motionListState = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 320.dp),
                state = motionListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    val isConnected = connected && row.selected
                    val isConnecting = pendingRoute == row.id
                    Surface(
                        onClick = OrbHaptics.transportClick {
                            connectionFailed = false
                            pendingRoute = row.id
                            runCatching { row.route.select() }.onFailure {
                                pendingRoute = null
                                connectionFailed = true
                            }
                        },
                        enabled = pendingRoute == null && !isConnected,
                        modifier = Modifier.orbSheetProgressiveLazyItem(motionListState, index),
                        shape = RoundedCornerShape(24.dp),
                        color = if (isConnected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isConnected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                                null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(when {
                                    isConnected -> R.string.cast_sheet_connected
                                    isConnecting -> R.string.cast_sheet_connecting
                                    else -> R.string.cast_sheet_tap
                                }), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isConnecting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else if (isConnected) Icon(Icons.Rounded.Check, null)
                        }
                    }
                }
            }
            if (connected && selected != null) {
                if (selected.adjustableVolume && selected.volumeMax > 0) {
                    var volume by remember(selected.id, selected.volume) { mutableFloatStateOf(selected.volume.toFloat()) }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.cast_sheet_volume),
                        modifier = Modifier.align(Alignment.Start), style = MaterialTheme.typography.labelLarge)
                    Slider(value = volume, onValueChange = { volume = it },
                        valueRange = 0f..selected.volumeMax.toFloat(),
                        onValueChangeFinished = { selected.route.requestSetVolume(volume.toInt()) })
                }
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = OrbHaptics.transportClick {
                    router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp)) {
                    Text(stringResource(R.string.cast_sheet_disconnect))
                }
            } else if (!scanning && castContext != null) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = OrbHaptics.transportClick {
                    connectionFailed = false
                    scanAttempt++
                }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp)) {
                    Text(stringResource(R.string.cast_sheet_retry))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}
