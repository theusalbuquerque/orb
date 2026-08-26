package com.music.orb.ui.screens

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.data.model.Account
import com.music.orb.BuildConfig
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.sources.SourceKind
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.settings.AudioQuality
import com.music.orb.data.settings.ThemeMode
import com.music.orb.playback.AudioCache
import com.music.orb.playback.DolbyAtmos
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onLyricsSources: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val atmosSupported by DolbyAtmos.supported.collectAsStateWithLifecycle()
    val atmosEnabled by DolbyAtmos.enabledOnDevice.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val sourceConfigs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val lossless by AppSettings.losslessAudio.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()

    val losslessConfigured = BuildConfig.MODULE_INDEX_URL.trim().isNotEmpty()
    val moduleEnabled = sourceConfigs.any { it.kind == SourceKind.MODULE && it.enabled && it.isComplete }

    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<QualityTarget?>(null) }
    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }
    var showLastfmLoginDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        DolbyAtmos.refresh()
        onPauseOrDispose {}
    }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        SettingsGroup {
            SettingsRow(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.settings_account_integrations),
                subtitle = account?.email?.takeIf { it.isNotBlank() }
                    ?: if (signedIn) stringResource(R.string.settings_signed_in) else stringResource(R.string.settings_not_signed_in),
                onClick = onAccountScrobbling,
            )
        }

        SettingsGroup(
            header = stringResource(R.string.settings_header_audio_quality),
            footer = stringResource(R.string.settings_footer_audio_quality),
        ) {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_lossless_hq),
                subtitle = if (!losslessConfigured) null else
                    if (moduleEnabled) stringResource(R.string.settings_lossless_subtitle_mod)
                    else stringResource(R.string.settings_lossless_subtitle_on),
                subtitleContent = if (!losslessConfigured) {
                    {
                        Text(
                            text = stringResource(R.string.settings_lossless_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else null,
                trailing = {
                    Switch(
                        checked = moduleEnabled && losslessConfigured,
                        onCheckedChange = {
                            if (losslessConfigured) {
                                SourceRegistry.setModuleEnabled(it)
                                AudioCache.clear {}
                            }
                        },
                        enabled = losslessConfigured,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    if (losslessConfigured) {
                        SourceRegistry.setModuleEnabled(!moduleEnabled)
                        AudioCache.clear {}
                    }
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Wifi,
                title = stringResource(R.string.settings_on_wifi),
                badge = stringResource(R.string.settings_badge_in_use).takeIf { metered == false },
                value = when (wifiQuality) {
                    AudioQuality.LOW -> stringResource(R.string.quality_low_label)
                    AudioQuality.MEDIUM -> stringResource(R.string.quality_medium_label)
                    AudioQuality.HIGH -> stringResource(R.string.quality_high_label)
                },
                onClick = { picking = QualityTarget.WIFI },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SignalCellularAlt,
                title = stringResource(R.string.settings_on_mobile_data),
                badge = stringResource(R.string.settings_badge_in_use).takeIf { metered == true },
                value = when (cellularQuality) {
                    AudioQuality.LOW -> stringResource(R.string.quality_low_label)
                    AudioQuality.MEDIUM -> stringResource(R.string.quality_medium_label)
                    AudioQuality.HIGH -> stringResource(R.string.quality_high_label)
                },
                onClick = { picking = QualityTarget.CELLULAR },
            )
        }

        SettingsGroup(header = stringResource(R.string.settings_header_playback)) {
            if (!smartFade) {
                SliderRow(
                    icon = Icons.Rounded.Waves,
                    title = stringResource(R.string.settings_crossfade),
                    subtitle = stringResource(R.string.settings_crossfade_subtitle),
                    value = if (crossfade == 0) "Off" else "${crossfade}s",
                    sliderValue = crossfade.toFloat(),
                    onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                )
                RowDivider()
            }
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.settings_automix),
                subtitle = if (smartFade) {
                    stringResource(R.string.settings_automix_subtitle_on)
                } else {
                    stringResource(R.string.settings_automix_subtitle_off)
                },
                trailing = {
                    Switch(
                        checked = smartFade,
                        onCheckedChange = AppSettings::setSmartFadeEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeOff,
                title = stringResource(R.string.settings_skip_silence),
                subtitle = stringResource(R.string.settings_skip_silence_subtitle),
                trailing = {
                    Switch(
                        checked = skipSilence,
                        onCheckedChange = AppSettings::setSkipSilence,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSkipSilence(!skipSilence) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = stringResource(R.string.settings_spatial_audio),
                subtitle = when {
                    !atmosSupported -> stringResource(R.string.settings_spatial_subtitle_unsupported)
                    !atmosEnabled -> stringResource(R.string.settings_spatial_subtitle_disabled)
                    else -> stringResource(R.string.settings_spatial_subtitle_enabled)
                },
                enabled = atmosSupported,
                trailing = {
                    Switch(
                        checked = spatialAudio && atmosEnabled,
                        onCheckedChange = { wanted ->
                            if (atmosEnabled) AppSettings.setSpatialAudio(wanted) else openAtmosSettings(context)
                        },
                        enabled = atmosSupported,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = when {
                    !atmosSupported -> null
                    !atmosEnabled -> ({ openAtmosSettings(context) })
                    else -> ({ AppSettings.setSpatialAudio(!spatialAudio) })
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_equalizer),
                subtitle = stringResource(R.string.settings_equalizer_subtitle),
                onClick = { openEqualizer(context, sessionId) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_nerd_stats),
                subtitle = stringResource(R.string.settings_nerd_stats_subtitle),
                trailing = {
                    Switch(
                        checked = nerdStats,
                        onCheckedChange = AppSettings::setShowNerdStats,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setShowNerdStats(!nerdStats) },
            )
        }

        SettingsGroup(header = stringResource(R.string.settings_header_appearance)) {
            SettingsRow(icon = Icons.Rounded.Brightness4, title = stringResource(R.string.settings_theme))
            SegmentedControl(
                options = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(theme),
                onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MotionPhotosOff,
                title = stringResource(R.string.settings_reduce_animation),
                subtitle = stringResource(R.string.settings_reduce_animation_subtitle),
                trailing = {
                    Switch(
                        checked = reduceAnimation,
                        onCheckedChange = AppSettings::setReduceAnimation,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.BlurOff,
                title = stringResource(R.string.settings_reduce_dynamic_blur),
                subtitle = stringResource(R.string.settings_reduce_blur_subtitle),
                trailing = {
                    Switch(
                        checked = reduceDynamicBlur,
                        onCheckedChange = AppSettings::setReduceDynamicBlur,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Animation,
                title = stringResource(R.string.settings_animated_art),
                subtitle = stringResource(R.string.settings_animated_art_subtitle),
                trailing = {
                    Switch(
                        checked = animatedCanvas,
                        onCheckedChange = AppSettings::setAnimatedCanvas,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.settings_synced_lyrics),
                subtitle = stringResource(R.string.settings_synced_lyrics_subtitle),
                trailing = {
                    Switch(
                        checked = syncedLyrics,
                        onCheckedChange = AppSettings::setSyncedLyrics,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
            )
            if (syncedLyrics) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.settings_lyrics_sources),
                    subtitle = lyricsSources
                        .sortedBy { it.ordinal }
                        .joinToString(", ") { it.label }
                        .ifEmpty { stringResource(R.string.settings_lyrics_empty) },
                    trailing = { Chevron() },
                    onClick = onLyricsSources,
                )
            }
        }

        val cacheLimitMb = (cacheLimitBytes / (1024 * 1024)).toInt()
        SettingsGroup(header = stringResource(R.string.settings_header_storage)) {
            SliderRow(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.settings_cache_limit),
                subtitle = if (cacheLimitMb > CACHE_WARNING_MB) {
                    "Up to ${formatCacheSize(cacheLimitMb)} of downloaded audio kept on disk — that's a real chunk of most phones' free storage."
                } else {
                    stringResource(R.string.settings_cache_limit_subtitle_high)
                },
                value = formatCacheSize(cacheLimitMb),
                sliderValue = cacheLimitMb.toFloat(),
                onSliderValue = {
                    AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                },
                valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                        (AppSettings.MAX_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat(),
                steps = 18,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.settings_clear_song_cache),
                subtitle = stringResource(R.string.settings_clear_song_cache_subtitle),
                onClick = {
                    AudioCache.clear {
                        Toast.makeText(context, "Song cache cleared", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.settings_clear_image_cache),
                subtitle = stringResource(R.string.settings_clear_image_cache_subtitle),
                onClick = {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    Toast.makeText(context, "Image cache cleared", Toast.LENGTH_SHORT).show()
                },
            )
        }

        SettingsGroup(
            header = stringResource(R.string.settings_header_misc),
            footer = stringResource(R.string.settings_footer_misc),
        ) {
            SettingsRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = stringResource(R.string.settings_swipe_next),
                subtitle = if (swipeToPlayNext) {
                    stringResource(R.string.settings_swipe_next_subtitle_on)
                } else {
                    stringResource(R.string.settings_swipe_next_subtitle_off)
                },
                trailing = {
                    Switch(
                        checked = swipeToPlayNext,
                        onCheckedChange = AppSettings::setSwipeToPlayNext,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MusicOff,
                title = stringResource(R.string.settings_stop_music_close),
                subtitle = stringResource(R.string.settings_stop_music_subtitle),
                trailing = {
                    Switch(
                        checked = stopOnTaskRemoved,
                        onCheckedChange = AppSettings::setStopOnTaskRemoved,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
            )
        }

        Text(
            text = buildAnnotatedString {
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                append("Orb $version\n")
                append(stringResource(R.string.footer_made_by))
                append(" ") // Espaço adicionado aqui
                withLink(LinkAnnotation.Url("https://www.threads.com/@theusalbuquerque", linkStyles)) {
                    append("THEUS")
                }
                append("\n")
                append(stringResource(R.string.footer_backend))
                append(" ") // Espaço adicionado aqui
                withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                    append("Kushagrasinghx")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
    }

    picking?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { picking = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            QualitySheet(
                target = target,
                selected = when (target) {
                    QualityTarget.WIFI -> wifiQuality
                    QualityTarget.CELLULAR -> cellularQuality
                },
                onSelect = { quality ->
                    when (target) {
                        QualityTarget.WIFI -> AppSettings.setAudioQualityWifi(quality)
                        QualityTarget.CELLULAR -> AppSettings.setAudioQualityCellular(quality)
                    }
                    picking = null
                },
            )
        }
    }
}

private enum class QualityTarget(val title: String, val icon: ImageVector) {
    WIFI("Wi-Fi", Icons.Rounded.Wifi),
    CELLULAR("Mobile data", Icons.Rounded.SignalCellularAlt),
}

private fun openEqualizer(context: Context, sessionId: Int) {
    if (sessionId == 0) {
        Toast.makeText(context, "Play a track first, then open the equalizer", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No system equalizer on this device", Toast.LENGTH_SHORT).show()
    }
}

private fun openAtmosSettings(context: Context) {
    val intent = DolbyAtmos.settingsIntent(context)
    if (intent == null) {
        Toast.makeText(context, "No Dolby Atmos panel on this device", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        Toast.makeText(context, "Couldn't open Dolby Atmos settings", Toast.LENGTH_SHORT).show()
    }
}

private const val CACHE_WARNING_MB = 2048

private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(gb)
}

@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (signedIn) Modifier else Modifier.clickable(onClick = onSignIn))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.name ?: if (signedIn) stringResource(R.string.settings_signed_in) else stringResource(R.string.account_not_signed_in),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.email?.takeIf { it.isNotBlank() }
                    ?: if (signedIn) "YouTube Music account" else stringResource(R.string.account_tap_signin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

@Composable
private fun QualitySheet(
    target: QualityTarget,
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = target.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.quality_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.quality_sheet_subtitle, target.title.lowercase()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        AudioQuality.entries.reversed().forEach { quality ->
            val chosen = quality == selected
            val label = when (quality) {
                AudioQuality.LOW -> stringResource(R.string.quality_low_label)
                AudioQuality.MEDIUM -> stringResource(R.string.quality_medium_label)
                AudioQuality.HIGH -> stringResource(R.string.quality_high_label)
            }
            val detail = when (quality) {
                AudioQuality.LOW -> stringResource(R.string.quality_low_detail)
                AudioQuality.MEDIUM -> stringResource(R.string.quality_medium_detail)
                AudioQuality.HIGH -> stringResource(R.string.quality_high_detail)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "$detail · ${quality.hourly}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(26.dp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

@Composable
internal fun DestructiveRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.outline)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}