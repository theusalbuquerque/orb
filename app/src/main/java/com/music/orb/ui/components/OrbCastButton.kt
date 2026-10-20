package com.music.orb.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.R
import com.music.orb.cast.OrbCastBridge
import com.music.orb.data.settings.AppSettings

@Composable
internal fun OrbCastButton(modifier: Modifier = Modifier) {
    val wifiConnected by AppSettings.wifiConnection.collectAsStateWithLifecycle()
    if (wifiConnected != true) return
    val connected by OrbCastBridge.connected.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    IconButton(onClick = OrbHaptics.transportClick { showSheet = true }, modifier = modifier.size(48.dp)) {
        Icon(
            if (connected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = stringResource(R.string.cast_audio),
            tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
    if (showSheet) OrbCastSheet { showSheet = false }
}
