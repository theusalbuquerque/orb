package com.music.orb.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.components.TimedExperimentalNotice
import com.music.orb.ui.components.FeedbackSheet
import com.music.orb.ui.flavor.OrbFlavorUi
import com.music.orb.data.model.Account
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AutomixVersion
import com.music.orb.data.sources.SourceKind
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.settings.AudioQuality
import com.music.orb.data.settings.OutputPcmMode
import com.music.orb.data.settings.ThemeMode
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.update.BetaUpdateScheduler
import com.music.orb.data.update.ManualUpdateChecker
import com.music.orb.data.update.ManualUpdateResult
import com.music.orb.data.update.UpdateChannel
import com.music.orb.playback.AudioCache
import com.music.orb.playback.AudioOutputStatus
import com.music.orb.playback.DolbyAtmos
import com.music.orb.ui.components.OrbHaptics
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val LocalSettingsEdgeScrollState = staticCompositionLocalOf<ScrollState?> { null }

/**
 * Gives Settings the same edge-depth motion used by the other Orb lists while
 * keeping the page's natural resting state untouched. The Settings page uses a
 * regular ScrollState/Column rather than LazyListState, so each visual item
 * tracks its real position in the root viewport instead of trying to reuse lazy
 * item offsets.
 */
@Composable
private fun settingsVerticalEdgeModifier(): Modifier {
    val scrollState = LocalSettingsEdgeScrollState.current ?: return Modifier
    val density = LocalDensity.current
    val view = LocalView.current
    val topEdgePx = with(density) {
        (if (OrbFlavorUi.expressive) 86.dp else 78.dp).toPx()
    }
    val minDistancePx = with(density) { 78.dp.toPx() }
    var itemTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }

    val edgeState = remember(scrollState, topEdgePx, minDistancePx, view) {
        derivedStateOf {
            val top = itemTopPx
            val height = itemHeightPx
            val bottom = view.height.toFloat()
            if (!top.isFinite() || height <= 0f || bottom <= 0f) {
                return@derivedStateOf Pair(1f, 0f)
            }

            val end = top + height
            val distance = maxOf(minDistancePx, height * 0.90f)
            val hasScrolledFromTop = scrollState.value > 0
            val topProgress = if (!hasScrolledFromTop || top >= topEdgePx) {
                1f
            } else {
                ((end - topEdgePx) / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            val bottomProgress = ((bottom - top) / distance).coerceIn(0f, 1f)
            val raw = minOf(topProgress, bottomProgress)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = when {
                top < topEdgePx && hasScrolledFromTop -> -1f
                top > bottom - distance -> 1f
                else -> 0f
            }
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }

    return Modifier
        .onGloballyPositioned { coordinates ->
            itemTopPx = coordinates.positionInRoot().y
            itemHeightPx = coordinates.size.height.toFloat()
        }
        .graphicsLayer {
            val edge = edgeState.value.first
            val direction = edgeState.value.second
            alpha = edge
            val scale = 0.84f + 0.16f * edge
            scaleX = scale
            scaleY = scale
            translationY = with(density) { (8.dp * direction * (1f - edge)).toPx() }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSources: () -> Unit,
    onLyricsSources: () -> Unit,
    account: Account? = null,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
) {
    val context = LocalContext.current
    val effectiveScrollState = scrollState ?: rememberScrollState()
    val scope = rememberCoroutineScope()

    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val automix by AppSettings.automixEnabled.collectAsStateWithLifecycle()
    val automixVersion by AppSettings.automixVersion.collectAsStateWithLifecycle()
    val automix25Available by AppSettings.automix25Available.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val atmosSupported by DolbyAtmos.supported.collectAsStateWithLifecycle()
    val atmosEnabled by DolbyAtmos.enabledOnDevice.collectAsStateWithLifecycle()
    val atmosStatusReadable by DolbyAtmos.statusReadable.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val hapticFeedback by AppSettings.hapticFeedback.collectAsStateWithLifecycle()
    val setHapticFeedback: (Boolean) -> Unit = { enabled ->
        // Let the setting confirm itself: when enabling, persist first so the
        // haptic is permitted; when disabling, pulse before removing permission.
        if (!enabled) OrbHaptics.perform(context, OrbHaptics.Kind.TOGGLE)
        AppSettings.setHapticFeedback(enabled)
        if (enabled) OrbHaptics.perform(context, OrbHaptics.Kind.TOGGLE)
    }
    val prioritizeAlbumVersions by AppSettings.prioritizeAlbumVersions.collectAsStateWithLifecycle()
    val hideStatusBarNowPlaying by AppSettings.hideStatusBarNowPlaying.collectAsStateWithLifecycle()
    val keepScreenOnNowPlaying by AppSettings.keepScreenOnNowPlaying.collectAsStateWithLifecycle()
    val allowScreenRotation by AppSettings.allowScreenRotation.collectAsStateWithLifecycle()
    val isPhoneLayout = LocalConfiguration.current.smallestScreenWidthDp < 600
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val homeAuraEnabled by AppSettings.homeAuraEnabled.collectAsStateWithLifecycle()
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val betaUpdatesEnabled by AppSettings.betaUpdatesEnabled.collectAsStateWithLifecycle()

    var showJoinBetaDialog by remember { mutableStateOf(false) }
    var showLeaveBetaDialog by remember { mutableStateOf(false) }
    var showAutomixNotice by rememberSaveable { mutableStateOf(false) }
    var showDonationSheet by rememberSaveable { mutableStateOf(false) }
    var checkingForUpdates by remember { mutableStateOf(false) }


    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.update_channel_notification_permission_required),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun notificationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    fun activateAutomaticChannel(beta: Boolean) {
        if (beta) {
            AppSettings.setStableAutoUpdatesEnabled(false)
            AppSettings.setBetaUpdatesEnabled(true)
            BetaUpdateScheduler.followBeta(context)
        } else {
            AppSettings.setBetaUpdatesEnabled(false)
            AppSettings.setStableAutoUpdatesEnabled(true)
            BetaUpdateScheduler.followStable(context)
        }

        if (notificationPermissionMissing()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun checkForUpdatesNow() {
        if (checkingForUpdates) return
        checkingForUpdates = true
        scope.launch {
            val channel = if (betaUpdatesEnabled) UpdateChannel.BETA else UpdateChannel.STABLE
            when (val result = ManualUpdateChecker.check(context, channel)) {
                ManualUpdateResult.UpToDate -> Toast.makeText(
                    context,
                    context.getString(R.string.update_manual_up_to_date),
                    Toast.LENGTH_LONG,
                ).show()

                ManualUpdateResult.NoRelease -> Toast.makeText(
                    context,
                    context.getString(R.string.update_manual_no_release),
                    Toast.LENGTH_LONG,
                ).show()

                ManualUpdateResult.Cancelled -> Unit

                is ManualUpdateResult.Available -> Toast.makeText(
                    context,
                    context.getString(R.string.update_manual_available, result.release.displayName),
                    Toast.LENGTH_LONG,
                ).show()

                is ManualUpdateResult.Ready -> Toast.makeText(
                    context,
                    context.getString(R.string.update_manual_downloaded, result.release.displayName),
                    Toast.LENGTH_LONG,
                ).show()

                is ManualUpdateResult.Failed -> Toast.makeText(
                    context,
                    context.getString(R.string.update_manual_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
            checkingForUpdates = false
        }
    }

    LifecycleResumeEffect(Unit) {
        DolbyAtmos.refresh()
        onPauseOrDispose {}
    }

    CompositionLocalProvider(LocalSettingsEdgeScrollState provides effectiveScrollState) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(effectiveScrollState)
            .padding(contentPadding),
    ) {
        SupportOrbCard(
            onClick = { showDonationSheet = true },
        )

        SettingsGroup(header = stringResource(R.string.settings_header_playback)) {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_audio_sources),
                subtitle = stringResource(R.string.settings_audio_sources_subtitle),
                onClick = onSources,
            )
            RowDivider()
            if (!automix) {
                SliderRow(
                    icon = Icons.Rounded.Waves,
                    title = stringResource(R.string.settings_crossfade),
                    subtitle = stringResource(R.string.settings_crossfade_subtitle),
                    value = if (crossfade == 0) "Off" else "${crossfade}s",
                    sliderValue = crossfade.toFloat(),
                    onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                    enabled = true,
                )
                RowDivider()
            }
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.settings_automix),
                subtitle = stringResource(
                    if (automixVersion == AutomixVersion.V2_5) {
                        R.string.settings_automix_subtitle_v25
                    } else {
                        R.string.settings_automix_subtitle_v20
                    },
                ),
                trailing = {
                    Switch(
                        checked = automix,
                        onCheckedChange = { wanted ->
                            if (wanted && !automix) {
                                AppSettings.setAutomixEnabled(true)
                                showAutomixNotice = true
                            } else {
                                AppSettings.setAutomixEnabled(wanted)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    if (!automix) {
                        AppSettings.setAutomixEnabled(true)
                        showAutomixNotice = true
                    } else {
                        AppSettings.setAutomixEnabled(false)
                    }
                },
            )
            if (automix25Available) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_automix_version),
                    subtitle = stringResource(R.string.settings_automix_25_premium_preview),
                )
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_automix_20),
                        stringResource(R.string.settings_automix_25),
                    ),
                    selectedIndex = if (automixVersion == AutomixVersion.V2_5) 1 else 0,
                    onSelect = { index ->
                        AppSettings.setAutomixVersion(
                            if (index == 1) AutomixVersion.V2_5 else AutomixVersion.V2_0,
                        )
                    },
                    modifier = Modifier.padding(
                        start = ROW_INSET,
                        end = ROW_INSET,
                        bottom = 14.dp,
                    ),
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = stringResource(R.string.settings_prioritize_album_versions),
                subtitle = stringResource(R.string.settings_prioritize_album_versions_subtitle),
                trailing = {
                    Switch(
                        checked = prioritizeAlbumVersions,
                        onCheckedChange = AppSettings::setPrioritizeAlbumVersions,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPrioritizeAlbumVersions(!prioritizeAlbumVersions) },
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
                subtitle = stringResource(R.string.settings_spatial_subtitle_enabled),
                trailing = {
                    Switch(
                        checked = spatialAudio,
                        onCheckedChange = AppSettings::setSpatialAudio,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_dolby_atmos),
                subtitle = when {
                    !atmosSupported -> stringResource(R.string.settings_dolby_atmos_unsupported)
                    !atmosStatusReadable -> stringResource(R.string.settings_dolby_atmos_available)
                    atmosEnabled -> stringResource(R.string.settings_dolby_atmos_active)
                    else -> stringResource(R.string.settings_dolby_atmos_disabled)
                },
                enabled = atmosSupported,
                trailing = if (atmosSupported) {
                    {
                        Text(
                            text = stringResource(
                                when {
                                    !atmosStatusReadable -> R.string.settings_dolby_atmos_system
                                    atmosEnabled -> R.string.settings_dolby_atmos_on
                                    else -> R.string.settings_dolby_atmos_off
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (atmosStatusReadable && atmosEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                } else null,
                onClick = if (atmosSupported) ({ openAtmosSettings(context) }) else null,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_output_precision),
                subtitle = buildString {
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
                onClick = null,
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map { it.label },
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = 58.dp, end = 16.dp, bottom = 14.dp),
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_prefer_usb_dac),
                subtitle = stringResource(R.string.settings_prefer_usb_dac_subtitle),
                trailing = {
                    Switch(
                        checked = preferUsbDac,
                        onCheckedChange = AppSettings::setPreferUsbDac,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setPreferUsbDac(!preferUsbDac) },
            )
        }

        SettingsGroup(header = stringResource(R.string.settings_header_appearance)) {
            val themeLabels = listOf(
                stringResource(R.string.settings_theme_system),
                stringResource(R.string.settings_theme_light),
                stringResource(R.string.settings_theme_dark),
            )
            ThemeSettingsRow(
                labels = themeLabels,
                selectedIndex = ThemeMode.entries.indexOf(theme),
                onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Brightness4,
                title = stringResource(R.string.settings_home_aura),
                subtitle = stringResource(R.string.settings_home_aura_subtitle),
                trailing = {
                    Switch(
                        checked = homeAuraEnabled,
                        onCheckedChange = AppSettings::setHomeAuraEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHomeAuraEnabled(!homeAuraEnabled) },
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
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_hide_status_bar_now_playing),
                subtitle = stringResource(R.string.settings_hide_status_bar_now_playing_subtitle),
                trailing = {
                    Switch(
                        checked = hideStatusBarNowPlaying,
                        onCheckedChange = AppSettings::setHideStatusBarNowPlaying,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setHideStatusBarNowPlaying(!hideStatusBarNowPlaying) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Brightness4,
                title = stringResource(R.string.settings_keep_screen_on_now_playing),
                subtitle = stringResource(R.string.settings_keep_screen_on_now_playing_subtitle),
                trailing = {
                    Switch(
                        checked = keepScreenOnNowPlaying,
                        onCheckedChange = AppSettings::setKeepScreenOnNowPlaying,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setKeepScreenOnNowPlaying(!keepScreenOnNowPlaying) },
            )
            if (isPhoneLayout) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.ScreenRotation,
                    title = stringResource(R.string.settings_allow_screen_rotation),
                    subtitle = stringResource(R.string.settings_allow_screen_rotation_subtitle),
                    trailing = {
                        Switch(
                            checked = allowScreenRotation,
                            onCheckedChange = AppSettings::setAllowScreenRotation,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setAllowScreenRotation(!allowScreenRotation) },
                )
            }
        }

        val cacheLimitMb = (cacheLimitBytes / (1024 * 1024)).toInt()
        SettingsGroup(header = stringResource(R.string.settings_header_storage)) {
            SliderRow(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.settings_cache_limit),
                subtitle = if (cacheLimitMb > CACHE_WARNING_MB) {
                    stringResource(
                        R.string.settings_cache_limit_subtitle_large,
                        formatCacheSize(cacheLimitMb),
                    )
                } else {
                    stringResource(R.string.settings_cache_limit_subtitle_high)
                },
                value = formatCacheSize(cacheLimitMb),
                sliderValue = cacheLimitMb.toFloat(),
                onSliderValue = { AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024L * 1024L) },
                valueRange = 256f..8192f,
                steps = 30,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.settings_clear_song_cache),
                subtitle = stringResource(R.string.settings_clear_song_cache_subtitle),
                onClick = {
                    AudioCache.clear {
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_song_cache_cleared),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
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
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Vibration,
                title = stringResource(R.string.settings_haptic_feedback),
                subtitle = stringResource(R.string.settings_haptic_feedback_subtitle),
                trailing = {
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = setHapticFeedback,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { setHapticFeedback(!hapticFeedback) },
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
                    subtitle = when {
                        lyricsSources.isEmpty() -> stringResource(R.string.settings_lyrics_empty)
                        lyricsSources.size <= 4 -> lyricsSources.sortedBy { it.ordinal }.joinToString(", ") { it.label }
                        else -> stringResource(R.string.settings_lyrics_sources_count, lyricsSources.size)
                    },
                    onClick = onLyricsSources,
                )
            }
        }

        SettingsGroup(header = stringResource(R.string.beta_updates_header)) {
            SettingsRow(
                icon = Icons.Rounded.BugReport,
                title = stringResource(R.string.beta_updates_join_title),
                subtitle = if (betaUpdatesEnabled) {
                    stringResource(R.string.beta_updates_join_subtitle_beta)
                } else {
                    stringResource(R.string.beta_updates_join_subtitle_off)
                },
                trailing = {
                    Switch(
                        checked = betaUpdatesEnabled,
                        onCheckedChange = { checked ->
                            if (checked) showJoinBetaDialog = true else showLeaveBetaDialog = true
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    if (betaUpdatesEnabled) showLeaveBetaDialog = true else showJoinBetaDialog = true
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.update_manual_title),
                subtitle = if (checkingForUpdates) stringResource(R.string.update_manual_checking) else null,
                enabled = !checkingForUpdates,
                trailing = if (checkingForUpdates) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    null
                },
                onClick = if (checkingForUpdates) null else ::checkForUpdatesNow,
            )
        }

        OrbAppFooter(account = account)
    }
    }


    if (showDonationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDonationSheet = false },
        ) {
            DonationSheetContent()
        }
    }

    if (showAutomixNotice) {
        TimedExperimentalNotice(
            title = stringResource(
                if (automixVersion == AutomixVersion.V2_5) {
                    R.string.automix_25_beta_notice_title
                } else {
                    R.string.automix_beta_notice_title
                },
            ),
            message = stringResource(
                if (automixVersion == AutomixVersion.V2_5) {
                    R.string.automix_25_beta_notice_message
                } else {
                    R.string.automix_beta_notice_message
                },
            ),
            confirmLabel = stringResource(R.string.experimental_feature_confirm),
            onConfirmed = { showAutomixNotice = false },
            unlockAfterMs = 6_000L,
        )
    }


    if (showJoinBetaDialog) {
        AlertDialog(
            onDismissRequest = { showJoinBetaDialog = false },
            title = { Text(stringResource(R.string.beta_updates_join_confirm_title)) },
            text = { Text(stringResource(R.string.beta_updates_join_confirm_message)) },
            dismissButton = {
                TextButton(onClick = { showJoinBetaDialog = false }) {
                    Text(stringResource(R.string.beta_updates_join_confirm_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showJoinBetaDialog = false
                        activateAutomaticChannel(beta = true)
                    },
                ) {
                    Text(stringResource(R.string.beta_updates_join_confirm_action))
                }
            },
        )
    }

    if (showLeaveBetaDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveBetaDialog = false },
            title = { Text(stringResource(R.string.beta_updates_leave_title)) },
            text = { Text(stringResource(R.string.beta_updates_leave_message)) },
            dismissButton = {
                TextButton(onClick = { showLeaveBetaDialog = false }) {
                    Text(stringResource(R.string.beta_updates_leave_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveBetaDialog = false
                        activateAutomaticChannel(beta = false)
                    },
                ) {
                    Text(stringResource(R.string.beta_updates_leave_confirm))
                }
            },
        )
    }
}


@Composable
private fun SupportOrbCard(
    onClick: () -> Unit,
) {
    val shape = if (OrbFlavorUi.expressive) RoundedCornerShape(28.dp) else GroupShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(settingsVerticalEdgeModifier())
            .padding(start = GROUP_INSET, top = 18.dp, end = GROUP_INSET)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.donation_card_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.donation_card_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private data class DonationField(
    val label: String,
    val value: String,
)

private data class DonationMethod(
    val flag: String,
    val currencyCode: String,
    val methodLabel: String,
    val title: String,
    val provider: String,
    val fields: List<DonationField>,
)

@Composable
private fun DonationSheetContent() {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.donation_copied)
    val ownerName = BuildConfig.DONATION_RECIPIENT
    var selectedMethodIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    val methods = listOf(
        DonationMethod(
            flag = "🇧🇷",
            currencyCode = "BRL",
            methodLabel = stringResource(R.string.donation_method_pix),
            title = stringResource(R.string.donation_pix_title),
            provider = stringResource(R.string.donation_pix_provider),
            fields = listOf(
                DonationField(stringResource(R.string.donation_field_pix_key), BuildConfig.DONATION_PIX_KEY),
                DonationField(stringResource(R.string.donation_field_recipient), ownerName),
            ),
        ),
        DonationMethod(
            flag = "🇺🇸",
            currencyCode = "USD",
            methodLabel = stringResource(R.string.donation_method_bank_transfer),
            title = stringResource(R.string.donation_usd_title),
            provider = stringResource(R.string.donation_usd_provider),
            fields = listOf(
                DonationField(stringResource(R.string.donation_field_account), BuildConfig.DONATION_USD_ACCOUNT),
                DonationField(stringResource(R.string.donation_field_account_type), BuildConfig.DONATION_USD_ACCOUNT_TYPE),
                DonationField(stringResource(R.string.donation_field_routing), BuildConfig.DONATION_USD_ROUTING),
                DonationField(stringResource(R.string.donation_field_recipient), ownerName),
            ),
        ),
        DonationMethod(
            flag = "🇪🇺",
            currencyCode = "EUR",
            methodLabel = stringResource(R.string.donation_method_bank_transfer),
            title = stringResource(R.string.donation_eur_title),
            provider = stringResource(R.string.donation_wise_provider),
            fields = listOf(
                DonationField(stringResource(R.string.donation_field_iban), BuildConfig.DONATION_EUR_IBAN),
                DonationField(stringResource(R.string.donation_field_recipient), ownerName),
            ),
        ),
        DonationMethod(
            flag = "🇨🇳",
            currencyCode = "CNY",
            methodLabel = stringResource(R.string.donation_method_bank_transfer),
            title = stringResource(R.string.donation_cny_title),
            provider = stringResource(R.string.donation_wise_provider),
            fields = listOf(
                DonationField(stringResource(R.string.donation_field_iban), BuildConfig.DONATION_CNY_IBAN),
                DonationField(stringResource(R.string.donation_field_recipient), ownerName),
            ),
        ),
        DonationMethod(
            flag = "🇬🇧",
            currencyCode = "GBP",
            methodLabel = stringResource(R.string.donation_method_bank_transfer),
            title = stringResource(R.string.donation_gbp_title),
            provider = stringResource(R.string.donation_wise_provider),
            fields = listOf(
                DonationField(stringResource(R.string.donation_field_account), BuildConfig.DONATION_GBP_ACCOUNT),
                DonationField(stringResource(R.string.donation_field_sort_code), BuildConfig.DONATION_GBP_SORT_CODE),
                DonationField(stringResource(R.string.donation_field_iban), BuildConfig.DONATION_GBP_IBAN),
                DonationField(stringResource(R.string.donation_field_recipient), ownerName),
            ),
        ),
    ) .filter { method -> method.fields.all { it.value.isNotBlank() } }

    val selectedMethod = selectedMethodIndex?.let { methods.getOrNull(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 34.dp),
    ) {
        if (selectedMethod == null) {
            Text(
                text = stringResource(R.string.donation_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(if (methods.isEmpty()) R.string.donation_not_configured else R.string.donation_sheet_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.donation_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.donation_choose_currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.donation_choose_currency_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            methods.forEachIndexed { index, method ->
                DonationCurrencyCard(
                    method = method,
                    onClick = { selectedMethodIndex = index },
                )
                if (index != methods.lastIndex) Spacer(Modifier.height(10.dp))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedMethodIndex = null }) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.donation_back),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.donation_transfer_details),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selectedMethod.flag,
                        fontSize = 30.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selectedMethod.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = selectedMethod.provider,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            DonationMethodCard(
                fields = selectedMethod.fields,
                onCopyField = { field ->
                    copyDonationText(context, field.label, field.value, copiedMessage)
                },
                onCopyAll = {
                    val block = buildString {
                        append(selectedMethod.title)
                        append("\n")
                        append(selectedMethod.provider)
                        selectedMethod.fields.forEach { field ->
                            append("\n")
                            append(field.label)
                            append(": ")
                            append(field.value)
                        }
                    }
                    copyDonationText(context, selectedMethod.title, block, copiedMessage)
                },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.donation_details_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DonationCurrencyCard(
    method: DonationMethod,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = method.flag,
                fontSize = 28.sp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = method.currencyCode,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "${method.methodLabel} · ${method.provider}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DonationMethodCard(
    fields: List<DonationField>,
    onCopyField: (DonationField) -> Unit,
    onCopyAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.donation_transfer_data),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopyAll) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.donation_copy_all))
            }
        }

        Spacer(Modifier.height(8.dp))
        fields.forEachIndexed { index, field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onCopyField(field) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = field.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.donation_copy_field, field.label),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
            if (index != fields.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

private fun copyDonationText(
    context: Context,
    label: String,
    value: String,
    copiedMessage: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ThemeSettingsRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(settingsVerticalEdgeModifier())
            .heightIn(min = if (OrbFlavorUi.expressive) 68.dp else 58.dp)
            .padding(horizontal = ROW_INSET, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (OrbFlavorUi.expressive) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Brightness4,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        } else {
            Icon(
                imageVector = Icons.Rounded.Brightness4,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
        }

        Text(
            text = stringResource(R.string.settings_theme),
            style = if (OrbFlavorUi.expressive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 70.dp),
        )
        Spacer(Modifier.width(8.dp))
        SegmentedControl(
            options = labels,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun OrbAppFooter(
    account: Account? = null,
) {
    val context = LocalContext.current
    var feedbackOpen by rememberSaveable { mutableStateOf(false) }
    val orbProfile by SocialRepository.myProfileState.collectAsStateWithLifecycle()
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    LaunchedEffect(account?.email) {
        if (account != null && SocialRepository.currentUserId() != null) {
            runCatching { SocialRepository.myProfile() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(settingsVerticalEdgeModifier())
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = buildAnnotatedString {
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
                append("Orb $version\n")
                append(stringResource(R.string.footer_made_by))
                append(" ")
                withLink(LinkAnnotation.Url("https://www.threads.com/@theusalbuquerque", linkStyles)) {
                    append("THEUS")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.footer_send_feedback),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { feedbackOpen = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }

    if (feedbackOpen) {
        FeedbackSheet(
            version = version,
            username = orbProfile?.username,
            email = account?.email,
            signedIn = SocialRepository.currentUserId() != null,
            onDismiss = { feedbackOpen = false },
        )
    }
}

private enum class SettingsQualityTarget(val title: String, val icon: ImageVector) {
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
    onSignedInClick: () -> Unit = {},
) {
    val shape = if (OrbFlavorUi.expressive) RoundedCornerShape(28.dp) else GroupShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(shape)
            .background(
                if (OrbFlavorUi.expressive) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = if (signedIn) onSignedInClick else onSignIn)
            .padding(
                horizontal = if (OrbFlavorUi.expressive) 18.dp else 14.dp,
                vertical = if (OrbFlavorUi.expressive) 16.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (OrbFlavorUi.expressive) 58.dp else 52.dp)
                    .clip(CircleShape)
                    .thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(if (OrbFlavorUi.expressive) 58.dp else 52.dp)
                    .clip(CircleShape)
                    .background(
                        if (OrbFlavorUi.expressive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = if (OrbFlavorUi.expressive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
                    ?: if (signedIn) stringResource(R.string.account_google_account) else stringResource(R.string.account_tap_signin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

@Composable
private fun QualitySheet(
    target: SettingsQualityTarget,
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
                AudioQuality.AAC -> stringResource(R.string.audio_quality_aac)
            }
            val detail = when (quality) {
                AudioQuality.LOW -> stringResource(R.string.quality_low_detail)
                AudioQuality.MEDIUM -> stringResource(R.string.quality_medium_detail)
                AudioQuality.HIGH -> stringResource(R.string.quality_high_detail)
                AudioQuality.AAC -> stringResource(R.string.audio_quality_aac)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (OrbFlavorUi.expressive) 14.dp else 0.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(if (OrbFlavorUi.expressive) 22.dp else 0.dp))
                    .then(
                        if (OrbFlavorUi.expressive) {
                            Modifier.background(
                                if (chosen) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            )
                        } else Modifier
                    )
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = if (OrbFlavorUi.expressive) 16.dp else 14.dp),
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
                        tint = if (OrbFlavorUi.expressive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
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
            text = if (OrbFlavorUi.expressive) header else header.uppercase(),
            style = if (OrbFlavorUi.expressive) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .then(settingsVerticalEdgeModifier())
                .padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = if (OrbFlavorUi.expressive) 30.dp else 26.dp,
                bottom = if (OrbFlavorUi.expressive) 10.dp else 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(if (OrbFlavorUi.expressive) 30.dp else 26.dp))
    }
    val groupShape = if (OrbFlavorUi.expressive) RoundedCornerShape(24.dp) else GroupShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(groupShape)
            .background(
                if (OrbFlavorUi.expressive) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .then(settingsVerticalEdgeModifier())
                .padding(
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
        modifier = Modifier.padding(
            start = if (OrbFlavorUi.expressive) 82.dp else TEXT_INSET,
        ),
        thickness = 0.5.dp,
        color = if (OrbFlavorUi.expressive) {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.outline
        },
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
    iconContent: (@Composable () -> Unit)? = null,
    iconHasOwnBackground: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(settingsVerticalEdgeModifier())
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = if (OrbFlavorUi.expressive) 68.dp else 52.dp)
            .padding(
                horizontal = ROW_INSET,
                vertical = if (OrbFlavorUi.expressive) 10.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (OrbFlavorUi.expressive) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (iconHasOwnBackground) {
                            Modifier
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (iconContent != null) {
                    iconContent()
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        } else {
            Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
                if (iconContent != null) {
                    iconContent()
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }
            Spacer(Modifier.width(ICON_GAP))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = if (OrbFlavorUi.expressive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
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
            .clip(RoundedCornerShape(if (OrbFlavorUi.expressive) 10.dp else 5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (OrbFlavorUi.expressive) 0.20f else 0.16f))
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
    enabled: Boolean = true,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(
        Modifier
            .then(settingsVerticalEdgeModifier())
            .alpha(if (enabled) 1f else 0.52f)
            .padding(
            start = ROW_INSET,
            end = ROW_INSET,
            top = if (OrbFlavorUi.expressive) 14.dp else 12.dp,
            bottom = if (OrbFlavorUi.expressive) 8.dp else 4.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (OrbFlavorUi.expressive) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(ICON_SIZE),
                )
                Spacer(Modifier.width(ICON_GAP))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (OrbFlavorUi.expressive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
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
            enabled = enabled,
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
            modifier = Modifier.padding(start = if (OrbFlavorUi.expressive) 54.dp else ICON_SIZE + ICON_GAP),
        )
    }
}

@Composable
internal fun DestructiveRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (OrbFlavorUi.expressive) {
                    Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = if (OrbFlavorUi.expressive) 13.dp else 15.dp),
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
            .clip(RoundedCornerShape(if (OrbFlavorUi.expressive) 18.dp else 10.dp))
            .background(if (OrbFlavorUi.expressive) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.outline)
            .padding(if (OrbFlavorUi.expressive) 3.dp else 2.dp),
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
                    .clip(RoundedCornerShape(if (OrbFlavorUi.expressive) 15.dp else 8.dp))
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
