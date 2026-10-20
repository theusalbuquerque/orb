package com.music.orb.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.music.orb.R
import com.music.orb.playback.AudioCache
import com.music.orb.ui.components.OrbConfirmationDialog
import com.music.orb.ui.components.OrbGlassTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CacheType { SONGS, COVERS, APP }

/** Settings subpage; it does not delete offline downloads, app preferences or account data. */
@Composable
internal fun CacheSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<CacheType?>(null) }
    var clearing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_cache_back))
        Text(
            stringResource(R.string.settings_cache_page_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
    Text(
        stringResource(R.string.settings_cache_page_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
    SettingsGroup {
        SettingsRow(
            icon = Icons.Rounded.Storage,
            title = stringResource(R.string.settings_clear_song_cache),
            subtitle = stringResource(R.string.settings_clear_song_cache_subtitle),
            enabled = !clearing,
            onClick = { pending = CacheType.SONGS },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Image,
            title = stringResource(R.string.settings_clear_covers_cache),
            subtitle = stringResource(R.string.settings_clear_image_cache_subtitle),
            enabled = !clearing,
            onClick = { pending = CacheType.COVERS },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.DeleteSweep,
            title = stringResource(R.string.settings_clear_app_cache),
            subtitle = stringResource(R.string.settings_clear_app_cache_subtitle),
            enabled = !clearing,
            onClick = { pending = CacheType.APP },
        )
    }

    val chosen = pending
    if (chosen != null) {
        val label = when (chosen) {
            CacheType.SONGS -> stringResource(R.string.settings_clear_song_cache)
            CacheType.COVERS -> stringResource(R.string.settings_clear_covers_cache)
            CacheType.APP -> stringResource(R.string.settings_clear_app_cache)
        }
        OrbConfirmationDialog(
            onDismissRequest = { if (!clearing) pending = null },
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(28.dp),
            title = { Text(label) },
            text = { Text(stringResource(R.string.settings_clear_cache_confirm)) },
            dismissButton = {
                OrbGlassTextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.settings_clear_cache_cancel))
                }
            },
            confirmButton = {
                OrbGlassTextButton(
                    enabled = !clearing,
                    onClick = {
                        pending = null
                        clearing = true
                        if (chosen == CacheType.SONGS) {
                            AudioCache.clear {
                                clearing = false
                                Toast.makeText(context, R.string.settings_song_cache_cleared, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            scope.launch {
                                val succeeded = withContext(Dispatchers.IO) {
                                    runCatching {
                                        when (chosen) {
                                            CacheType.COVERS -> {
                                                val imageLoader = SingletonImageLoader.get(context)
                                                imageLoader.memoryCache?.clear()
                                                imageLoader.diskCache?.clear()
                                                true
                                            }
                                            CacheType.APP -> clearTemporaryAppCache(context)
                                            CacheType.SONGS -> true
                                        }
                                    }.getOrDefault(false)
                                }
                                clearing = false
                                Toast.makeText(
                                    context,
                                    if (succeeded) R.string.settings_clear_cache_success
                                    else R.string.settings_clear_cache_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.settings_clear_cache_confirm_action)) }
            },
        )
    }
}

/** Keep live Media3/Coil cache directories: those are cleared through their own APIs. */
private fun clearTemporaryAppCache(context: Context): Boolean {
    val activeCacheNames = setOf("audio", "image_cache")
    val files = context.cacheDir.listFiles() ?: return true
    return files.filterNot { it.name in activeCacheNames }
        .all { file -> !file.exists() || file.deleteRecursively() }
}
