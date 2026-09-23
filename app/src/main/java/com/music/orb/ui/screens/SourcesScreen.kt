package com.music.orb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AudioQuality
import com.music.orb.data.sources.SourceConfig
import com.music.orb.data.sources.SourceHealth
import com.music.orb.data.sources.SourceKind
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.addon.AddonClient
import com.music.orb.ui.components.TimedExperimentalNotice
import kotlinx.coroutines.launch

/** Orb audio quality and playback-source settings. */
@Composable
fun SourcesScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifi by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellular by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val losslessEnabled by AppSettings.losslessAudio.collectAsStateWithLifecycle()
    val wifiMaximum by AppSettings.audioQualityWifiMaximum.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAddonDialog by remember { mutableStateOf(false) }
    var addonUrl by remember { mutableStateOf("") }
    var addonName by remember { mutableStateOf("Private addon") }
    var addonError by remember { mutableStateOf<String?>(null) }
    var addonChecking by remember { mutableStateOf(false) }
    var showLosslessNotice by remember { mutableStateOf(false) }
    val addonDuplicateMessage = stringResource(R.string.audio_addon_duplicate)

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        SectionTitle(stringResource(R.string.audio_quality_header))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.audio_lossless_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = losslessEnabled && wifiMaximum,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        // Set the Maximum ceiling first, then flip the master
                        // Lossless flow. Playback observes the latter and will
                        // therefore see both flags true in the same callback.
                        AppSettings.setAudioQualityWifiMaximum(true)
                        AppSettings.setLosslessAudio(true)
                        showLosslessNotice = true
                    } else {
                        AppSettings.setAudioQualityWifiMaximum(false)
                        AppSettings.setLosslessAudio(false)
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        NetworkQualityCard(
            icon = Icons.Rounded.Wifi,
            title = stringResource(R.string.audio_wifi),
            selected = wifi,
            accent = MaterialTheme.colorScheme.secondary,
            onSelect = AppSettings::setAudioQualityWifi,
        )
        Spacer(Modifier.height(10.dp))
        NetworkQualityCard(
            icon = Icons.Rounded.SignalCellularAlt,
            title = stringResource(R.string.audio_mobile_data),
            selected = cellular,
            accent = MaterialTheme.colorScheme.tertiary,
            onSelect = AppSettings::setAudioQualityCellular,
        )

        Text(
            text = stringResource(R.string.audio_quality_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )

        SectionTitle(stringResource(R.string.audio_sources_section))
        configs
            .asSequence()
            .filterNot { it.kind == SourceKind.JIOSAAVN }
            .sortedBy { it.kind.ordinal }
            .forEach { config ->
                SourceRow(
                    config = config,
                    onEnabled = { enabled ->
                        if (config.kind != SourceKind.YOUTUBE) SourceRegistry.setEnabled(config.id, enabled)
                    },
                    onRemove = if (config.kind == SourceKind.ADDON) {
                        { SourceRegistry.remove(config.id) }
                    } else null,
                )
                HorizontalDivider()
            }

        TextButton(
            onClick = {
                addonUrl = ""
                addonName = "Private addon"
                addonError = null
                showAddonDialog = true
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.audio_addon_add))
        }

        Text(
            text = stringResource(R.string.audio_sources_priority_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        val custom = SourceRegistry.customModule()
        if (custom != null) {
            Text(
                text = stringResource(R.string.audio_custom_module_active, localizedSourceName(custom)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (showAddonDialog) {
        AlertDialog(
            onDismissRequest = { if (!addonChecking) showAddonDialog = false },
            title = { Text(stringResource(R.string.audio_addon_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.audio_addon_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addonUrl,
                        onValueChange = { addonUrl = it; addonError = null },
                        label = { Text(stringResource(R.string.audio_addon_url)) },
                        singleLine = true,
                        enabled = !addonChecking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addonName,
                        onValueChange = { addonName = it },
                        label = { Text(stringResource(R.string.audio_addon_name)) },
                        singleLine = true,
                        enabled = !addonChecking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    addonError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = addonUrl.isNotBlank() && !addonChecking,
                    onClick = {
                        val normalized = AddonClient.normalizeBase(addonUrl)
                        if (configs.any {
                                it.kind == SourceKind.ADDON &&
                                    AddonClient.normalizeBase(it.baseUrl).equals(normalized, ignoreCase = true)
                            }) {
                            addonError = addonDuplicateMessage
                            return@TextButton
                        }
                        addonChecking = true
                        addonError = null
                        scope.launch {
                            try {
                                val candidate = SourceConfig(
                                    kind = SourceKind.ADDON,
                                    label = addonName.trim(),
                                    baseUrl = normalized,
                                    enabled = true,
                                )
                                when (val health = SourceRegistry.probeCandidate(candidate)) {
                                    is SourceHealth.Ok -> {
                                        SourceRegistry.add(candidate)
                                        showAddonDialog = false
                                        addonUrl = ""
                                    }
                                    is SourceHealth.Rejected -> addonError = health.reason
                                    is SourceHealth.Unreachable -> addonError = health.reason
                                }
                            } catch (failure: Exception) {
                                addonError = failure.message ?: "Could not validate addon"
                            } finally {
                                addonChecking = false
                            }
                        }
                    },
                ) {
                    Text(if (addonChecking) stringResource(R.string.source_checking) else stringResource(R.string.audio_addon_save))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !addonChecking,
                    onClick = { showAddonDialog = false },
                ) { Text(stringResource(R.string.audio_addon_cancel)) }
            },
        )
    }

    if (showLosslessNotice) {
        TimedExperimentalNotice(
            title = stringResource(R.string.audio_lossless_experimental_title),
            message = stringResource(R.string.audio_lossless_experimental_message),
            confirmLabel = stringResource(R.string.experimental_feature_confirm),
            onConfirmed = { showLosslessNotice = false },
            unlockAfterMs = 6_000L,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun NetworkQualityCard(
    icon: ImageVector,
    title: String,
    selected: AudioQuality,
    accent: Color,
    onSelect: (AudioQuality) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .padding(top = 14.dp)
                .size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        QualityRow(
            title = title,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QualityRow(
    title: String,
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayedSelection = if (selected == AudioQuality.AAC) AudioQuality.HIGH else selected
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(AudioQuality.LOW, AudioQuality.MEDIUM, AudioQuality.HIGH).forEach { quality ->
                val chosen = quality == displayedSelection
                Text(
                    text = if (chosen) "✓ ${localizedQualityName(quality)}" else localizedQualityName(quality),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chosen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable {
                            // The visible High tier is Orb's Hi-Quality Audio
                            // preset. Persist AAC explicitly so playback keeps
                            // the AAC 320 -> AAC 250 -> Opus 128 -> AAC 128
                            // hierarchy instead of treating HIGH as a generic
                            // best-lossy request.
                            onSelect(if (quality == AudioQuality.HIGH) AudioQuality.AAC else quality)
                        }
                        .padding(end = 18.dp, top = 8.dp, bottom = 8.dp),
                )
            }
        }
        Text(
            text = localizedQualityDetail(displayedSelection),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceRow(
    config: SourceConfig,
    onEnabled: (Boolean) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(localizedSourceName(config), style = MaterialTheme.typography.titleSmall)
            Text(
                localizedSourceDetail(config.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.audio_addon_remove))
            }
            Spacer(Modifier.width(4.dp))
        }
        Switch(
            checked = if (config.kind == SourceKind.YOUTUBE) true else config.enabled,
            enabled = config.kind != SourceKind.YOUTUBE,
            onCheckedChange = onEnabled,
        )
    }
}

@Composable
private fun localizedSourceName(config: SourceConfig): String {
    val configuredName = config.label.trim()
    if (config.kind == SourceKind.ADDON && configuredName.contains("navidrome", ignoreCase = true)) {
        return stringResource(R.string.audio_source_addon)
    }
    if (configuredName.isNotEmpty() && config.kind in setOf(SourceKind.ADDON, SourceKind.CUSTOM_MODULE, SourceKind.MODULE)) {
        return configuredName
    }
    return when (config.kind) {
        SourceKind.TIDAL -> stringResource(R.string.audio_source_tidal_hifi)
        SourceKind.ADDON -> stringResource(R.string.audio_source_addon)
        SourceKind.CUSTOM_MODULE -> stringResource(R.string.audio_source_custom_module)
        SourceKind.MODULE -> stringResource(R.string.audio_source_module)
        SourceKind.JIOSAAVN -> stringResource(R.string.audio_source_jiosaavn)
        SourceKind.YOUTUBE -> stringResource(R.string.audio_source_youtube)
    }
}

@Composable
private fun localizedSourceDetail(kind: SourceKind): String = when (kind) {
    SourceKind.TIDAL -> stringResource(R.string.audio_source_tidal_hifi_detail)
    SourceKind.ADDON -> stringResource(R.string.audio_source_addon_detail)
    SourceKind.CUSTOM_MODULE -> stringResource(R.string.audio_source_custom_module_detail)
    SourceKind.MODULE -> stringResource(R.string.audio_source_module_detail)
    SourceKind.JIOSAAVN -> stringResource(R.string.audio_source_jiosaavn_detail)
    SourceKind.YOUTUBE -> stringResource(R.string.audio_source_youtube_detail)
}

@Composable
private fun localizedQualityName(quality: AudioQuality): String = when (quality) {
    AudioQuality.LOW -> stringResource(R.string.audio_quality_low_name)
    AudioQuality.MEDIUM -> stringResource(R.string.audio_quality_medium_name)
    AudioQuality.HIGH, AudioQuality.AAC -> stringResource(R.string.audio_quality_high_name)
}

@Composable
private fun localizedQualityDetail(quality: AudioQuality): String = when (quality) {
    AudioQuality.LOW -> stringResource(R.string.audio_quality_low_detail)
    AudioQuality.MEDIUM -> stringResource(R.string.audio_quality_medium_detail)
    AudioQuality.HIGH, AudioQuality.AAC -> stringResource(R.string.audio_quality_high_detail)
}
