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
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.Text
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
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
    val isPremiumEntitled by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
    val cellularMaximum by AppSettings.audioQualityCellularMaximum.collectAsStateWithLifecycle()
    val losslessMobileSupported by AppSettings.serverLosslessMobileEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAddonDialog by remember { mutableStateOf(false) }
    var addonUrl by remember { mutableStateOf("") }
    var addonName by remember { mutableStateOf("Private addon") }
    var addonError by remember { mutableStateOf<String?>(null) }
    var addonChecking by remember { mutableStateOf(false) }
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
                .clip(RoundedCornerShape(24.dp))
                .orbGlassSurface(
                    pageColor = orbOverlayGlassBaseColor(),
                    cornerRadius = 24.dp,
                    manageDialogBackdrop = false,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.audio_lossless_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = losslessEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        // Enabling the Lossless source also selects Maximum on Wi-Fi.
                        AppSettings.setAudioQualityWifiMaximum(true)
                        AppSettings.setLosslessAudio(true)
                    } else {
                        // Turning it off must clear both network ceilings so a
                        // stale Maximum selection is never shown or restored.
                        AppSettings.setAudioQualityWifiMaximum(false)
                        AppSettings.setAudioQualityCellularMaximum(false)
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
            maximumSelected = losslessEnabled && wifiMaximum,
            maximumAvailable = true,
            isCellular = false,
            accent = MaterialTheme.colorScheme.secondary,
            onSelect = { selection ->
                if (selection == 3) {
                    AppSettings.setAudioQualityWifiMaximum(true)
                    AppSettings.setLosslessAudio(true)
                } else {
                    AppSettings.setAudioQualityWifi(qualityAtIndex(selection))
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        NetworkQualityCard(
            icon = Icons.Rounded.SignalCellularAlt,
            title = stringResource(R.string.audio_mobile_data),
            selected = cellular,
            maximumSelected = losslessEnabled && cellularMaximum && isPremiumEntitled && losslessMobileSupported,
            maximumAvailable = isPremiumEntitled && losslessMobileSupported,
            isCellular = true,
            accent = MaterialTheme.colorScheme.tertiary,
            onSelect = { selection ->
                if (selection == 3) {
                    if (isPremiumEntitled && losslessMobileSupported) {
                        AppSettings.setAudioQualityCellularMaximum(true)
                        AppSettings.setLosslessAudio(true)
                    }
                } else {
                    AppSettings.setAudioQualityCellular(qualityAtIndex(selection))
                }
            },
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
                Spacer(Modifier.height(6.dp))
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
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
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


}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        // Top section begins at the same 18dp offset as Orb Premium in Settings.
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun NetworkQualityCard(
    icon: ImageVector,
    title: String,
    selected: AudioQuality,
    maximumSelected: Boolean,
    maximumAvailable: Boolean,
    isCellular: Boolean,
    accent: Color,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .orbGlassSurface(
                pageColor = orbOverlayGlassBaseColor(),
                cornerRadius = 24.dp,
                manageDialogBackdrop = false,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            maximumSelected = maximumSelected,
            maximumAvailable = maximumAvailable,
            isCellular = isCellular,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QualityRow(
    title: String,
    selected: AudioQuality,
    maximumSelected: Boolean,
    maximumAvailable: Boolean,
    isCellular: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayedSelection = if (selected == AudioQuality.AAC) AudioQuality.HIGH else selected
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        val qualityOptions = listOf(
            localizedQualityName(AudioQuality.LOW),
            localizedQualityName(AudioQuality.MEDIUM),
            localizedQualityName(AudioQuality.HIGH),
            stringResource(R.string.audio_quality_maximum_name),
        )
        SegmentedControl(
            options = qualityOptions,
            selectedIndex = if (maximumSelected) 3 else when (displayedSelection) {
                AudioQuality.LOW -> 0
                AudioQuality.MEDIUM -> 1
                else -> 2
            },
            onSelect = onSelect,
            enabledIndices = if (maximumAvailable) null else setOf(0, 1, 2),
            edgeMotion = false,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                maximumSelected && isCellular ->
                    stringResource(R.string.audio_quality_cellular_maximum_detail)
                maximumSelected ->
                    stringResource(R.string.audio_quality_wifi_maximum_detail)
                else -> localizedQualityDetail(displayedSelection, isCellular)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isCellular && !maximumAvailable) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.audio_quality_maximum_premium_locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceRow(
    config: SourceConfig,
    onEnabled: (Boolean) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .orbGlassSurface(
                pageColor = orbOverlayGlassBaseColor(),
                cornerRadius = 18.dp,
                manageDialogBackdrop = false,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = localizedSourceName(config),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
private fun localizedQualityDetail(
    quality: AudioQuality,
    isCellular: Boolean,
): String = when (quality) {
    AudioQuality.LOW -> if (isCellular) {
        stringResource(R.string.audio_quality_low_cellular_detail)
    } else {
        stringResource(R.string.audio_quality_low_detail)
    }
    AudioQuality.MEDIUM -> if (isCellular) {
        stringResource(R.string.audio_quality_medium_cellular_detail)
    } else {
        stringResource(R.string.audio_quality_medium_detail)
    }
    AudioQuality.HIGH, AudioQuality.AAC -> stringResource(R.string.audio_quality_high_detail)
}

/** Preserve the AAC first-note preset underneath the visible High tier. */
private fun qualityAtIndex(index: Int): AudioQuality = when (index) {
    0 -> AudioQuality.LOW
    1 -> AudioQuality.MEDIUM
    else -> AudioQuality.AAC
}
