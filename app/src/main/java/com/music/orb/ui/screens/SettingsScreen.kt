package com.music.orb.ui.screens
import com.music.orb.ui.components.OrbGlassButton

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.orbBadgeGlassSurface
import com.music.orb.data.billing.BillingApi
import com.music.orb.data.billing.BillingException
import com.music.orb.data.billing.PremiumCheckout
import com.music.orb.data.billing.PremiumPriceLocalization
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SystemUpdate
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.music.orb.ui.components.OrbModalBottomSheet as ModalBottomSheet
import com.music.orb.ui.components.OrbSheetMotionContent
import com.music.orb.ui.components.rememberOrbSheetMotionViewport
import com.music.orb.ui.components.orbSheetMotionViewport
import com.music.orb.ui.components.orbSheetProgressiveItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
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
import com.music.orb.ui.icons.OrbIcons
import com.music.orb.data.model.Account
import com.music.orb.BuildConfig
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.download.OfflineDownloadQuality
import com.music.orb.data.settings.AutomixVersion
import com.music.orb.data.settings.PremiumLaunch
import com.music.orb.data.settings.OrbAppLanguage
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
import com.music.orb.ui.components.orbExpressiveGlassContentColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Localized, actionable billing diagnostics; no raw Mercado Pago payloads or credentials. */
private fun premiumBillingErrorText(context: android.content.Context, exception: Exception): String {
    if (exception !is BillingException) return context.getString(R.string.premium_billing_error)
    val messageId = when (exception.code) {
        "MP_CREDENTIALS_REJECTED" -> R.string.premium_billing_credentials_rejected
        "MP_REQUEST_REJECTED" -> R.string.premium_billing_request_rejected
        "MP_RATE_LIMITED" -> R.string.premium_billing_rate_limited
        "MP_CHECKOUT_LINK_MISSING" -> R.string.premium_billing_link_missing
        "MP_INVALID_RESPONSE" -> R.string.premium_billing_invalid_response
        "MP_UNAVAILABLE", "NETWORK_ERROR" -> R.string.premium_billing_service_unavailable
        else -> when (exception.status) {
            401 -> R.string.premium_billing_sign_in
            409 -> R.string.premium_billing_already_active
            502, 503, 504 -> R.string.premium_billing_service_unavailable
            else -> R.string.premium_billing_error
        }
    }
    val base = context.getString(messageId)
    val providerCode = exception.providerCode?.takeIf { code ->
        code != "unknown" && code.matches(Regex("[A-Za-z0-9_.-]{1,64}"))
    }
    return if (providerCode != null && exception.code == "MP_REQUEST_REJECTED")
        "$base ($providerCode)" else base
}

@Composable
internal fun rememberPremiumReleaseReached(): Boolean {
    fun hasReachedPremiumRelease(): Boolean = PremiumLaunch.isPublicLaunchTime()

    var premiumReleaseReached by remember { mutableStateOf(hasReachedPremiumRelease()) }

    LaunchedEffect(Unit) {
        while (true) {
            // Re-evaluate the user's current time zone, including changes while
            // Settings remains open. Initial state avoids a post-launch card flash.
            val releaseMillis = LocalDate.of(2026, 11, 1)
                .atTime(LocalTime.of(8, 0))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val remainingMillis = releaseMillis - System.currentTimeMillis()
            premiumReleaseReached = remainingMillis <= 0L
            delay(if (remainingMillis > 0L) remainingMillis.coerceAtMost(60_000L) else 60_000L)
        }
    }
    return premiumReleaseReached
}

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
    var itemContentTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }

    val edgeState = remember(scrollState, topEdgePx, minDistancePx, view) {
        derivedStateOf {
            val top = itemContentTopPx - scrollState.value
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
            itemContentTopPx = coordinates.positionInRoot().y + scrollState.value
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
    onOrbLockSettings: () -> Unit,
    account: Account? = null,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    highlightPremium: Boolean = false,
) {
    val context = LocalContext.current
    val effectiveScrollState = scrollState ?: rememberScrollState()
    val scope = rememberCoroutineScope()

    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val automix by AppSettings.automixEnabled.collectAsStateWithLifecycle()
    val automixVersion by AppSettings.automixVersion.collectAsStateWithLifecycle()
    val automix25Available by AppSettings.automix25Available.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val atmosSupported by DolbyAtmos.supported.collectAsStateWithLifecycle()
    val atmosEnabled by DolbyAtmos.enabledOnDevice.collectAsStateWithLifecycle()
    val atmosStatusReadable by DolbyAtmos.statusReadable.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val hapticFeedback by AppSettings.hapticFeedback.collectAsStateWithLifecycle()
    val touchTransferEnabled by AppSettings.touchTransferEnabled.collectAsStateWithLifecycle()
    val setHapticFeedback: (Boolean) -> Unit = { enabled ->
        // Let the setting confirm itself: when enabling, persist first so the
        // haptic is permitted; when disabling, pulse before removing permission.
        if (!enabled) OrbHaptics.performToggleBeforeDisabling(context)
        AppSettings.setHapticFeedback(enabled)
        if (enabled) OrbHaptics.perform(context, OrbHaptics.Kind.TOGGLE)
    }
    val hideStatusBarNowPlaying by AppSettings.hideStatusBarNowPlaying.collectAsStateWithLifecycle()
    val keepScreenOnNowPlaying by AppSettings.keepScreenOnNowPlaying.collectAsStateWithLifecycle()
    val allowScreenRotation by AppSettings.allowScreenRotation.collectAsStateWithLifecycle()
    val isPhoneLayout = LocalConfiguration.current.smallestScreenWidthDp < 600
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val homeAuraEnabled by AppSettings.homeAuraEnabled.collectAsStateWithLifecycle()
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val downloadAllowLowerQuality by AppSettings.downloadAllowLowerQuality.collectAsStateWithLifecycle()
    val useDownloadsWhileOnline by AppSettings.useDownloadsWhileOnline.collectAsStateWithLifecycle()
    val betaUpdatesEnabled by AppSettings.betaUpdatesEnabled.collectAsStateWithLifecycle()
    val isOwnerAccount by AppSettings.isOwnerAccount.collectAsStateWithLifecycle()
    val premiumEarlyOfferAccess by AppSettings.premiumEarlyOfferAccess.collectAsStateWithLifecycle()
    val premiumEntitled by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
    val ownerPremiumTestSubscribed by AppSettings.ownerPremiumTestSubscribed.collectAsStateWithLifecycle()
    val premiumSubscriptionVisible = if (isOwnerAccount) ownerPremiumTestSubscribed else premiumEntitled
    val continuePlaybackAfterRecentRemoval by AppSettings.continuePlaybackAfterRecentRemoval.collectAsStateWithLifecycle()
    val serverPremiumCardEnabled by AppSettings.serverPremiumCardEnabled.collectAsStateWithLifecycle()
    val premiumReleaseReached = rememberPremiumReleaseReached()

    // The invited account sees the same offer as public-launch users, but
    // remains locked until the backend confirms a paid subscription.
    val premiumOfferVisible = isOwnerAccount || premiumEarlyOfferAccess || premiumReleaseReached
    val showPremiumCard = premiumOfferVisible
    val premiumFeaturesLocked = premiumOfferVisible && !premiumEntitled && !isOwnerAccount

    // Removed controls must not leave invisible settings enabled for existing users.
    LaunchedEffect(Unit) {
        if (AppSettings.prioritizeAlbumVersions.value) AppSettings.setPrioritizeAlbumVersions(false)
        if (AppSettings.skipSilence.value) AppSettings.setSkipSilence(false)
        if (!AppSettings.syncedLyrics.value) AppSettings.setSyncedLyrics(true)
    }

    var showJoinBetaDialog by remember { mutableStateOf(false) }
    var showLeaveBetaDialog by remember { mutableStateOf(false) }
    var showAutomixNotice by rememberSaveable { mutableStateOf(false) }
    var showDonationSheet by rememberSaveable { mutableStateOf(false) }
    var showPremiumSheet by rememberSaveable { mutableStateOf(false) }
    var showDownloadSettings by rememberSaveable { mutableStateOf(false) }
    var showAppLanguageSheet by rememberSaveable { mutableStateOf(false) }
    var showCachePage by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showCachePage) { showCachePage = false }
    LaunchedEffect(showCachePage) {
        if (showCachePage) effectiveScrollState.scrollTo(0)
    }
    var showCancelPremiumDialog by rememberSaveable { mutableStateOf(false) }
    var cancelPremiumBusy by remember { mutableStateOf(false) }
    var cancelPremiumError by remember { mutableStateOf<String?>(null) }

    fun cancelPremiumSubscription() {
        if (cancelPremiumBusy) return
        cancelPremiumBusy = true
        cancelPremiumError = null
        scope.launch {
            try {
                if (isOwnerAccount) {
                    // Revoke the server-backed complimentary subscription record.
                    // Owner developer entitlement remains independent of billing.
                    withContext(Dispatchers.IO) { BillingApi.ownerZeroCancel() }
                } else {
                    // Cancellation stops renewal, not the already paid entitlement.
                    // Premium may correctly remain true until paidUntil expires.
                    withContext(Dispatchers.IO) { BillingApi.cancelSubscription() }
                }
                showCancelPremiumDialog = false
                Toast.makeText(
                    context,
                    if (isOwnerAccount) R.string.premium_demo_cancel_success else R.string.premium_cancel_success,
                    Toast.LENGTH_LONG,
                ).show()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                cancelPremiumError = premiumBillingErrorText(context, exception)
            } finally {
                cancelPremiumBusy = false
            }
        }
    }
    var premiumMonthlyPrice by remember { mutableStateOf<Double?>(null) }
    var checkingForUpdates by remember { mutableStateOf(false) }

    // The main activity already refreshes account entitlements on a controlled
    // cadence. Opening Settings must never request it a second time just to
    // display a card: the cached, account-scoped StateFlow is immediate.
    LaunchedEffect(showPremiumCard, isOwnerAccount) {
        if (showPremiumCard && !isOwnerAccount && premiumMonthlyPrice == null) {
            premiumMonthlyPrice = withContext(Dispatchers.IO) { BillingApi.fetchMonthlyPrice() }
        }
    }


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
        if (showCachePage) {
            CacheSettingsPage(onBack = { showCachePage = false })
        } else {
        if (showPremiumCard) {
            PremiumOrbCard(
                premiumEntitled = premiumSubscriptionVisible,
                ownerDemoCanceled = isOwnerAccount && !ownerPremiumTestSubscribed,
                highlight = highlightPremium,
                onClick = { showPremiumSheet = true },
            )
        }
        if (!premiumOfferVisible) {
            SupportOrbCard(
                onClick = { showDonationSheet = true },
            )
        }

        SettingsGroup(header = stringResource(R.string.settings_header_playback)) {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_audio_sources),
                subtitle = stringResource(R.string.settings_audio_sources_subtitle),
                onClick = onSources,
            )
            RowDivider()
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
                SettingsSegmentedField(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_automix_version),
                    subtitle = stringResource(R.string.settings_automix_25_premium_preview),
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
                )
            }
            if (premiumFeaturesLocked && !automix25Available) {
                RowDivider()
                PremiumLockedSettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.premium_automix_locked_title),
                    subtitle = stringResource(R.string.premium_automix_locked_subtitle),
                )
            }
            RowDivider()
            SettingsRow(
                icon = OrbIcons.Infinity,
                title = stringResource(R.string.settings_autoplay),
                subtitle = stringResource(R.string.settings_autoplay_subtitle),
                trailing = {
                    Switch(
                        checked = autoplay,
                        onCheckedChange = AppSettings::setAutoplay,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { AppSettings.setAutoplay(!autoplay) },
            )
            if (premiumEntitled || isOwnerAccount) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    title = stringResource(R.string.settings_continue_playback_recents),
                    subtitle = stringResource(R.string.settings_continue_playback_recents_subtitle),
                    trailing = {
                        Switch(
                            checked = continuePlaybackAfterRecentRemoval,
                            onCheckedChange = AppSettings::setContinuePlaybackAfterRecentRemoval,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = {
                        AppSettings.setContinuePlaybackAfterRecentRemoval(!continuePlaybackAfterRecentRemoval)
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.DevicesOther,
                    title = stringResource(R.string.settings_touch_transfer),
                    subtitle = stringResource(R.string.settings_touch_transfer_subtitle),
                    trailing = {
                        Switch(
                            checked = touchTransferEnabled,
                            onCheckedChange = AppSettings::setTouchTransferEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setTouchTransferEnabled(!touchTransferEnabled) },
                )
            } else if (premiumFeaturesLocked) {
                RowDivider()
                PremiumLockedSettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    title = stringResource(R.string.settings_continue_playback_recents),
                    subtitle = stringResource(R.string.settings_continue_playback_recents_subtitle),
                )
                RowDivider()
                PremiumLockedSettingsRow(
                    icon = Icons.Rounded.DevicesOther,
                    title = stringResource(R.string.settings_touch_transfer),
                    subtitle = stringResource(R.string.settings_touch_transfer_subtitle),
                )
            }
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
            SettingsSegmentedField(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_output_precision),
                subtitle = buildString {
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
                options = OutputPcmMode.entries.map { it.label },
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
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
            if (premiumOfferVisible || premiumEntitled) {
                RowDivider()
                if (premiumFeaturesLocked) {
                    PremiumLockedSettingsRow(
                        icon = Icons.Rounded.DevicesOther,
                        title = stringResource(R.string.settings_orb_lockscreen),
                        subtitle = stringResource(R.string.settings_orb_lockscreen_subtitle),
                    )
                } else {
                    SettingsRow(
                        icon = Icons.Rounded.DevicesOther,
                        title = stringResource(R.string.settings_orb_lockscreen),
                        subtitle = stringResource(R.string.settings_orb_lockscreen_subtitle),
                        onClick = onOrbLockSettings,
                        trailing = {
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
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
                title = stringResource(R.string.settings_display_hide_status_bar),
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
        val selectedDownloadQuality = when (downloadQuality) {
            "MEDIUM" -> stringResource(R.string.orb_download_quality_medium)
            "MAXIMUM" -> stringResource(R.string.orb_download_quality_maximum)
            else -> stringResource(R.string.orb_download_quality_high)
        }
        SettingsGroup(header = stringResource(R.string.settings_header_storage)) {
            SettingsRow(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.orb_language_settings_title),
                subtitle = if (com.music.orb.data.settings.OrbAppLanguage.choice(context).isEmpty()) {
                    stringResource(R.string.orb_language_auto_description,
                        com.music.orb.data.settings.OrbAppLanguage.labelFor(
                            com.music.orb.data.settings.OrbAppLanguage.deviceTag()))
                } else {
                    com.music.orb.data.settings.OrbAppLanguage.labelFor(
                        com.music.orb.data.settings.OrbAppLanguage.choice(context))
                },
                trailing = {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick = { showAppLanguageSheet = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.settings_download_preferences),
                // Preserve the selected quality below the title, without a
                // trailing value that squeezes long translations vertically.
                subtitle = stringResource(R.string.settings_downloads_subtitle) +
                    "\n" + selectedDownloadQuality,
                onClick = { showDownloadSettings = true },
            )
            RowDivider()
            SliderRow(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.settings_storage_limit),
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
                title = stringResource(R.string.settings_cache_page_title),
                subtitle = stringResource(R.string.settings_cache_page_subtitle),
                onClick = { showCachePage = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_nerd_stats_short),
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
                        hapticOnChange = false,
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
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.settings_lyrics_origin),
                    subtitle = when {
                        lyricsSources.isEmpty() -> stringResource(R.string.settings_lyrics_empty)
                        lyricsSources.size <= 4 -> lyricsSources.sortedBy { it.ordinal }.joinToString(", ") { it.label }
                        else -> stringResource(R.string.settings_lyrics_sources_count, lyricsSources.size)
                    },
                    onClick = onLyricsSources,
            )
            RowDivider()
            if (!premiumEntitled && !isOwnerAccount) {
                PremiumLockedSettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = stringResource(R.string.premium_feature_offline_lyrics),
                    subtitle = stringResource(R.string.premium_offline_lyrics_subtitle),
                )
            } else {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = stringResource(R.string.premium_feature_offline_lyrics),
                    subtitle = stringResource(R.string.premium_offline_lyrics_subtitle),
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
    }


    if (showDownloadSettings) {
        AlertDialog(
            onDismissRequest = { showDownloadSettings = false },
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.settings_download_preferences)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        OfflineDownloadQuality.MEDIUM to R.string.orb_download_quality_medium,
                        OfflineDownloadQuality.HIGH to R.string.orb_download_quality_high,
                        OfflineDownloadQuality.MAXIMUM to R.string.orb_download_quality_maximum,
                    ).forEach { (quality, labelId) ->
                        val locked = quality == OfflineDownloadQuality.MAXIMUM && !premiumEntitled
                        val selected = quality.name == downloadQuality
                        val shape = RoundedCornerShape(18.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(shape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                                .clickable(enabled = !locked) { AppSettings.setDownloadQuality(quality.name) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(labelId),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (locked) {
                                Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.orb_download_quality_premium))
                            } else if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                            }
                        }
                    }
                    if (downloadQuality != OfflineDownloadQuality.MEDIUM.name) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    AppSettings.setDownloadAllowLowerQuality(!downloadAllowLowerQuality)
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        if (downloadQuality == OfflineDownloadQuality.MAXIMUM.name)
                                            R.string.settings_download_fallback_high_title
                                        else R.string.settings_download_fallback_lower_title,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(
                                        if (downloadQuality == OfflineDownloadQuality.MAXIMUM.name)
                                            R.string.settings_download_fallback_high_summary
                                        else R.string.settings_download_fallback_lower_summary,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = downloadAllowLowerQuality,
                                onCheckedChange = AppSettings::setDownloadAllowLowerQuality,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { AppSettings.setUseDownloadsWhileOnline(!useDownloadsWhileOnline) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.download_prefer_offline_online_title),
                                style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.download_prefer_offline_online_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = useDownloadsWhileOnline,
                            onCheckedChange = AppSettings::setUseDownloadsWhileOnline)
                    }
                    Text(
                        text = if (downloadQuality == OfflineDownloadQuality.MAXIMUM.name) {
                            stringResource(R.string.settings_download_patient_lossless_summary)
                        } else {
                            stringResource(R.string.settings_download_strict_quality_summary)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadSettings = false }) {
                    Text(stringResource(R.string.settings_download_done))
                }
            },
        )
    }

    if (showAppLanguageSheet) {
        OrbAppLanguageSheet(onDismiss = { showAppLanguageSheet = false })
    }

    if (showPremiumSheet) {
        com.music.orb.ui.components.RegisterSheetBackdrop()
        val sheetGlassColor = orbOverlayGlassBaseColor()
        ModalBottomSheet(
            floatingTitle = stringResource(R.string.premium_sheet_title),
            onDismissRequest = { showPremiumSheet = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            // Paint the gesture-navigation inset as part of the same glass.
            navigationBarInsetInContent = true,
            dragHandle = null,
        ) {
            com.music.orb.ui.components.OrbGlassSheetSurface(
                pageColor = sheetGlassColor,
                cornerRadius = 36.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = LocalConfiguration.current.screenHeightDp.dp * 0.70f,
                        max = LocalConfiguration.current.screenHeightDp.dp * 0.84f,
                    ),
            ) {
                val premiumScroll = rememberScrollState()
                val premiumMotion = rememberOrbSheetMotionViewport(premiumScroll)
                Column(
                    Modifier.weight(1f, fill = false)
                        .orbSheetMotionViewport(premiumMotion)
                        .verticalScroll(premiumScroll),
                ) {
                    OrbSheetMotionContent(premiumMotion) {
                    PremiumBillingSheet(
                    accountKey = account?.email,
                    premiumEntitled = premiumEntitled,
                    monthlyPrice = premiumMonthlyPrice,
                    onRequestCancellation = {
                        // Dismiss the bottom sheet in this composition BEFORE
                        // opening an independent, opaque confirmation dialog.
                        showPremiumSheet = false
                        cancelPremiumError = null
                        showCancelPremiumDialog = true
                    },
                    onDismiss = { showPremiumSheet = false },
                    onDonate = {
                        showPremiumSheet = false
                        showDonationSheet = true
                    },
                    )
                    }
                }

            }
        }
    }

    if (showCancelPremiumDialog && !showPremiumSheet) {
        com.music.orb.ui.components.RegisterSheetBackdrop()
        AlertDialog(
            onDismissRequest = {
                if (!cancelPremiumBusy) {
                    showCancelPremiumDialog = false
                    cancelPremiumError = null
                }
            },
            // Use the same frosted dialog material and activity blur as other Orb popups.
            modifier = Modifier.orbGlassSurface(
                orbOverlayGlassBaseColor(), cornerRadius = 28.dp,
                manageDialogBackdrop = false,
            ),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.premium_cancel_subscription_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(
                        if (isOwnerAccount) R.string.premium_demo_cancel_message
                        else R.string.premium_cancel_subscription_confirm_message,
                    ))
                    cancelPremiumError?.let {
                        Spacer(Modifier.height(20.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !cancelPremiumBusy,
                    onClick = ::cancelPremiumSubscription,
                ) {
                    if (cancelPremiumBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.premium_cancel_subscription_confirm_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !cancelPremiumBusy, onClick = {
                    showCancelPremiumDialog = false
                    cancelPremiumError = null
                }) { Text(stringResource(R.string.source_editor_cancel)) }
            },
        )
    }

    if (showDonationSheet) {
        com.music.orb.ui.components.RegisterSheetBackdrop()
        val sheetGlassColor = orbOverlayGlassBaseColor()
        ModalBottomSheet(
            floatingTitle = stringResource(R.string.orb_sheet_donation),
            onDismissRequest = { showDonationSheet = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            navigationBarInsetInContent = true,
            dragHandle = null,
        ) {
            // A tall currency list must scroll *inside* a partial sheet. Without
            // a height cap its verticalScroll child can occupy the whole screen.
            com.music.orb.ui.components.OrbGlassSheetSurface(
                pageColor = sheetGlassColor,
                cornerRadius = 36.dp,
                modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.76f),
            ) {
                DonationSheetContent(modifier = Modifier.weight(1f, fill = false))

            }
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
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
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
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
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
private fun PremiumOrbCard(
    premiumEntitled: Boolean,
    ownerDemoCanceled: Boolean = false,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = if (OrbFlavorUi.expressive) RoundedCornerShape(28.dp) else GroupShape
    val launchHighlight = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (highlight) {
            launchHighlight.snapTo(1f)
            launchHighlight.animateTo(0f, animationSpec = tween(durationMillis = 3600))
        } else {
            launchHighlight.snapTo(0f)
        }
    }
    // The card is frosted glass, not the raw tertiaryContainer. Resolve the
    // foreground against the final glass tone so dark dynamic palettes never
    // produce brown-on-brown / low-contrast Premium copy.
    val premiumContentColor = orbExpressiveGlassContentColor(
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(settingsVerticalEdgeModifier())
            .padding(start = GROUP_INSET, top = 18.dp, end = GROUP_INSET)
            .clip(shape)
            .orbGlassSurface(
                pageColor = MaterialTheme.colorScheme.tertiaryContainer,
                cornerRadius = 28.dp,
                manageDialogBackdrop = false,
            )
            .border(
                width = (1f + launchHighlight.value * 2f).dp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = launchHighlight.value),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (premiumEntitled) R.string.premium_card_subscribed_title else R.string.premium_card_title,
                ),
                style = MaterialTheme.typography.titleLarge,
                color = premiumContentColor,
            )
            if (!premiumEntitled) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(
                        if (ownerDemoCanceled) R.string.premium_demo_canceled
                        else R.string.premium_card_subtitle,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = premiumContentColor.copy(alpha = 0.78f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = premiumContentColor,
        )
    }
}

@Composable
private fun PremiumBillingSheet(
    accountKey: String?,
    premiumEntitled: Boolean,
    monthlyPrice: Double?,
    onRequestCancellation: () -> Unit,
    onDismiss: () -> Unit,
    onDonate: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Cached entitlement is already account-scoped by AppSettings. Never blank
    // the subscription controls while waiting for a redundant network lookup.
    val entitlementReady by AppSettings.serverControlReady.collectAsStateWithLifecycle()
    val isOwner by AppSettings.isOwnerAccount.collectAsStateWithLifecycle()
    val demoSubscribed by AppSettings.ownerPremiumTestSubscribed.collectAsStateWithLifecycle()
    var ready by remember(accountKey) { mutableStateOf(!isOwner && (premiumEntitled || entitlementReady)) }
    val subscribedInBilling = if (isOwner) ready && demoSubscribed else premiumEntitled
    LaunchedEffect(accountKey, entitlementReady, premiumEntitled, isOwner) {
        if (premiumEntitled || isOwner || entitlementReady) ready = true
    }
    var busy by remember(accountKey) { mutableStateOf(false) }
    var error by remember(accountKey) { mutableStateOf<String?>(null) }
    var checkout by remember(accountKey) { mutableStateOf<PremiumCheckout?>(null) }

    // The chosen region (not merely the language or the phone's network) decides
    // which currency and monthly-period translation to display. BRL remains
    // the real charge currency until the backend/checkout supports local charges.
    val pricingTag = OrbAppLanguage.effectiveTag(context)
    val localCurrency = PremiumPriceLocalization.localCurrency(pricingTag)
    var exchangeQuote by remember(accountKey, pricingTag) {
        mutableStateOf<PremiumPriceLocalization.FxQuote?>(null)
    }
    LaunchedEffect(accountKey, pricingTag, isOwner, monthlyPrice != null || checkout != null) {
        if (localCurrency != "BRL" &&
            (monthlyPrice != null || checkout != null) && !isOwner) {
            exchangeQuote = withContext(Dispatchers.IO) {
                PremiumPriceLocalization.quote(pricingTag)
            }
        }
    }

    // Operations suspend on IO; keep every button disabled until verified status arrives.
    suspend fun refresh() {
        withContext(Dispatchers.IO) { BillingApi.refreshSubscription() }
        ready = true
        if (checkout?.requiresConfirmation == false && AppSettings.premiumEntitled.value) onDismiss()
    }

    fun runBilling(showError: Boolean = true, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        if (showError) error = null
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (showError) {
                    error = premiumBillingErrorText(context, exception)
                }
            } finally {
                busy = false
            }
        }
    }

    LifecycleResumeEffect(accountKey) {
        // Owner state comes from Orb's authenticated backend, NOT a fabricated
        // local purchase. Refresh once on sheet entry and when returning from
        // the Mercado Pago card-verification page. Never poll continuously.
        if (isOwner) {
            runBilling(showError = checkout != null) {
                val active = withContext(Dispatchers.IO) { BillingApi.ownerZeroStatus() }
                ready = true
                if (active && checkout != null) {
                    Toast.makeText(context, R.string.premium_demo_subscribe_success, Toast.LENGTH_LONG).show()
                    onDismiss()
                }
            }
        } else if (!ready || checkout != null) {
            runBilling(showError = checkout != null) { refresh() }
        }
        onPauseOrDispose { }
    }

    val pending = checkout
    if (pending == null) {
        PremiumSheetContent(
            premiumEntitled = subscribedInBilling,
            monthlyPrice = if (isOwner) 0.0 else monthlyPrice,
            demoMode = isOwner,
            pricingTag = pricingTag,
            exchangeQuote = exchangeQuote,
            busy = busy,
            ready = ready,
            error = error,
            onAction = {
                if (subscribedInBilling) {
                    onRequestCancellation()
                } else if (isOwner) {
                    runBilling { checkout = withContext(Dispatchers.IO) { BillingApi.ownerZeroCheckout() } }
                } else {
                    runBilling { checkout = withContext(Dispatchers.IO) { BillingApi.checkout() } }
                }
            },
            onRetry = { runBilling { refresh() } },
            onDonate = onDonate,
        )
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                stringResource(if (isOwner) R.string.premium_demo_payment_title else R.string.premium_payment_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.premium_payment_total), style = MaterialTheme.typography.titleMedium)
            val converted = exchangeQuote?.takeIf { it.currencyCode != "BRL" }
            val chargedBrl = PremiumPriceLocalization.formatBrl(pending.amount)
            Text(
                if (converted != null) PremiumPriceLocalization.format(
                    pending.amount * converted.unitsPerBrl,
                    converted.currencyCode,
                    pricingTag,
                ) else chargedBrl,
                style = MaterialTheme.typography.headlineLarge,
            )
            if (converted != null) {
                Text(
                    PremiumPriceLocalization.localizedString(context, R.string.premium_price_approximate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    PremiumPriceLocalization.localizedString(context, R.string.premium_price_actual_brl, chargedBrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PremiumFxAttribution()
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(
                if (isOwner) R.string.premium_demo_payment_notice
                else if (pending.requiresConfirmation) R.string.premium_payment_zero_notice
                else R.string.premium_payment_provider_notice,
            ))
            Spacer(Modifier.height(20.dp))
            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }
            OrbGlassButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (pending.requiresConfirmation) {
                        runBilling {
                            val active = withContext(Dispatchers.IO) { BillingApi.confirm(pending) }
                            check(active)
                            Toast.makeText(context, R.string.premium_payment_success, Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    } else {
                        runBilling {
                            val uri = android.net.Uri.parse(requireNotNull(pending.url))
                            check(uri.scheme == "https")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (pending.requiresConfirmation) R.string.premium_payment_confirm else R.string.premium_payment_continue))
            }
            if (!pending.requiresConfirmation) {
                Spacer(Modifier.height(12.dp))
                TextButton(enabled = !busy, onClick = {
                    runBilling {
                        if (isOwner) {
                            if (withContext(Dispatchers.IO) { BillingApi.ownerZeroStatus() }) onDismiss()
                            else error = context.getString(R.string.premium_payment_pending)
                        } else {
                            refresh()
                            if (AppSettings.premiumEntitled.value) onDismiss()
                            else error = context.getString(R.string.premium_payment_pending)
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.premium_payment_check))
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(enabled = !busy, onClick = { checkout = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.premium_payment_back))
            }
        }
    }


}

@Composable
private fun PremiumSheetContent(
    premiumEntitled: Boolean,
    monthlyPrice: Double?,
    demoMode: Boolean,
    pricingTag: String,
    exchangeQuote: PremiumPriceLocalization.FxQuote?,
    busy: Boolean,
    ready: Boolean,
    error: String?,
    onAction: () -> Unit,
    onRetry: () -> Unit,
    onDonate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        // Title already floats above the glass via floatingTitle.
        val context = LocalContext.current
        val localQuote = exchangeQuote?.takeIf { it.currencyCode != "BRL" && !demoMode }
        val monthlyAmount = monthlyPrice?.let { brl ->
            if (localQuote != null) {
                PremiumPriceLocalization.format(
                    brl * localQuote.unitsPerBrl,
                    localQuote.currencyCode,
                    pricingTag,
                )
            } else PremiumPriceLocalization.formatBrl(brl)
        }
        Text(
            text = if (monthlyAmount != null) {
                PremiumPriceLocalization.monthlyText(context, monthlyAmount)
            } else {
                PremiumPriceLocalization.localizedString(context, R.string.premium_price_unavailable)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (localQuote != null && monthlyPrice != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                PremiumPriceLocalization.localizedString(context, R.string.premium_price_approximate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                PremiumPriceLocalization.localizedString(
                    context,
                    R.string.premium_price_actual_brl,
                    PremiumPriceLocalization.formatBrl(monthlyPrice),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PremiumFxAttribution()
        }
        if (demoMode) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.premium_demo_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (!premiumEntitled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.premium_demo_canceled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        listOf(
            R.string.premium_feature_automix,
            R.string.premium_feature_switch,
            R.string.premium_feature_lossless,
            R.string.premium_feature_offline_lyrics,
            R.string.premium_feature_orb_lock,
            R.string.premium_feature_more,
        ).forEach { resource ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .orbSheetProgressiveItem()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(resource),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OrbGlassButton(
                onClick = onRetry,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.premium_billing_retry))
            }
            Spacer(Modifier.height(16.dp))
        }
        OrbGlassButton(
            onClick = onAction,
            enabled = ready && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy || !ready && error == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    if (premiumEntitled) {
                        R.string.premium_cancel_subscription_button
                    } else {
                        R.string.premium_subscribe_monthly_button
                    },
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        androidx.compose.material3.TextButton(
            onClick = onDonate,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(stringResource(R.string.premium_donation_link))
        }
        Spacer(Modifier.height(20.dp))
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
            .orbGlassSurface(
                pageColor = MaterialTheme.colorScheme.secondaryContainer,
                cornerRadius = 28.dp,
                manageDialogBackdrop = false,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.donation_card_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.donation_card_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
private fun DonationSheetContent(modifier: Modifier = Modifier) {
    val premiumReleaseReached = rememberPremiumReleaseReached()
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

    val donationScroll = rememberScrollState()
    val donationMotion = rememberOrbSheetMotionViewport(donationScroll)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .orbSheetMotionViewport(donationMotion)
            .verticalScroll(donationScroll)
            .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
    ) {
        OrbSheetMotionContent(donationMotion) {
        if (selectedMethod == null) {
            // Avoid repeating the floating donation heading inside the panel.
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
                    text = stringResource(
                        if (premiumReleaseReached) R.string.donation_notice_after_launch else R.string.donation_notice,
                    ),
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
}

@Composable
private fun DonationCurrencyCard(
    method: DonationMethod,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .orbSheetProgressiveItem()
            .clip(RoundedCornerShape(22.dp))
            .orbGlassSurface(
                pageColor = orbOverlayGlassBaseColor(),
                cornerRadius = 22.dp,
                manageDialogBackdrop = false,
            )
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

        Spacer(Modifier.height(20.dp))
        fields.forEachIndexed { index, field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .orbGlassSurface(
                        pageColor = orbOverlayGlassBaseColor(),
                        cornerRadius = 14.dp,
                        manageDialogBackdrop = false,
                    )
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
    // Reuse the same full-width layout as the Automix and PCM selectors.
    // Keeping the title and control on separate rows prevents squeezed labels
    // and keeps every segment aligned with the card's horizontal insets.
    SettingsSegmentedField(
        icon = Icons.Rounded.Brightness4,
        title = stringResource(R.string.settings_theme),
        options = labels,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
    )
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
            .then(
                if (OrbFlavorUi.expressive) {
                    Modifier.orbGlassSurface(
                        pageColor = orbOverlayGlassBaseColor(),
                        cornerRadius = 28.dp,
                        manageDialogBackdrop = false,
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                }
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
    val tactileContext = LocalContext.current
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
                            Modifier.orbGlassSurface(
                                pageColor = if (chosen) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    orbOverlayGlassBaseColor()
                                },
                                cornerRadius = 22.dp,
                                drawRim = chosen,
                                overlayStrength = chosen,
                                manageDialogBackdrop = false,
                            )
                        } else Modifier
                    )
                    .clickable {
                        com.music.orb.ui.components.OrbHaptics.perform(tactileContext, com.music.orb.ui.components.OrbHaptics.Kind.TOGGLE)
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
                        contentDescription = stringResource(R.string.orb_l10n_selected),
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
                bottom = if (OrbFlavorUi.expressive) 14.dp else 12.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(if (OrbFlavorUi.expressive) 26.dp else 24.dp))
    }
    val groupShape = if (OrbFlavorUi.expressive) RoundedCornerShape(24.dp) else GroupShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .then(
                if (OrbFlavorUi.expressive) {
                    // Expressive rows own their glass. Do not leave the old
                    // rounded group container behind them: stacking both layers
                    // is what produced the visible double mould in Settings.
                    Modifier
                } else {
                    Modifier
                        .clip(groupShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
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
    if (OrbFlavorUi.expressive) {
        // Separate glass cards instead of drawing the legacy divider through
        // the former shared group container.
        Spacer(Modifier.height(10.dp))
    } else {
        HorizontalDivider(
            modifier = Modifier
                .then(settingsVerticalEdgeModifier())
                .padding(start = TEXT_INSET),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Premium controls remain discoverable but cannot receive touch input on free accounts. */
@Composable
internal fun PremiumLockedSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        badge = stringResource(R.string.premium_locked_badge),
        enabled = false,
        onClick = null,
        trailing = {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = stringResource(R.string.premium_locked_accessibility),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            .then(
                if (OrbFlavorUi.expressive) {
                    // Every settings field uses one glass surface, clickable or
                    // not. This prevents non-interactive rows from falling back
                    // to the old flat group material.
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .orbGlassSurface(
                            pageColor = orbOverlayGlassBaseColor(),
                            cornerRadius = 18.dp,
                            manageDialogBackdrop = false,
                        )
                } else Modifier
            )
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
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
                            Modifier.orbBadgeGlassSurface(
                                pageColor = MaterialTheme.colorScheme.primaryContainer,
                                cornerRadius = 14.dp,
                            )
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
                    modifier = if (badge != null) Modifier.weight(1f, fill = false) else Modifier,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
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
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
            }
            if (value != null && value.length > 23 && trailing == null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null && value.length <= 23) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

@Composable
internal fun SettingsSegmentedField(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(settingsVerticalEdgeModifier())
            .clip(RoundedCornerShape(18.dp))
            .orbGlassSurface(
                pageColor = orbOverlayGlassBaseColor(),
                cornerRadius = 18.dp,
                manageDialogBackdrop = false,
            )
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .orbBadgeGlassSurface(
                        pageColor = MaterialTheme.colorScheme.primaryContainer,
                        cornerRadius = 14.dp,
                    ),
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
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Full card width, independently of icon/title length.
        SegmentedControl(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            edgeMotion = false,
            embedded = true,
        )
    }
}

@Composable
internal fun Badge(text: String) {
    val glassColor = orbOverlayGlassBaseColor()
    val contentColor = com.music.orb.ui.components.orbExpressiveGlassContentColor(glassColor)
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = Modifier
            .clip(RoundedCornerShape(if (OrbFlavorUi.expressive) 10.dp else 5.dp))
            .orbBadgeGlassSurface(
                pageColor = glassColor,
                cornerRadius = if (OrbFlavorUi.expressive) 10.dp else 5.dp,
            )
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
            .then(
                if (OrbFlavorUi.expressive) {
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .orbGlassSurface(
                            pageColor = orbOverlayGlassBaseColor(),
                            cornerRadius = 18.dp,
                            manageDialogBackdrop = false,
                        )
                } else Modifier
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DestructiveRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = if (onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (OrbFlavorUi.expressive) {
                    Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .orbGlassSurface(
                            pageColor = MaterialTheme.colorScheme.errorContainer,
                            cornerRadius = 18.dp,
                            manageDialogBackdrop = false,
                        )
                } else Modifier
            )
            .then(interaction)
            .padding(vertical = if (OrbFlavorUi.expressive) 13.dp else 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    edgeMotion: Boolean = true,
    embedded: Boolean = false,
    enabledIndices: Set<Int>? = null,
) {
    val tactileContext = LocalContext.current
    val outerShape = RoundedCornerShape(if (OrbFlavorUi.expressive) 18.dp else 10.dp)
    val innerShape = RoundedCornerShape(if (OrbFlavorUi.expressive) 15.dp else 8.dp)
    val spacing = if (OrbFlavorUi.expressive) 2.dp else 1.dp
    val outerPadding = if (OrbFlavorUi.expressive) 3.dp else 2.dp
    val segmentHeight = if (OrbFlavorUi.expressive) 44.dp else 38.dp

    BoxWithConstraints(
        modifier = modifier
            .then(if (edgeMotion) settingsVerticalEdgeModifier() else Modifier)
            .fillMaxWidth()
            .clip(outerShape)
            .then(
                if (embedded) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f))
                } else if (OrbFlavorUi.expressive) {
                    Modifier.orbGlassSurface(
                        pageColor = orbOverlayGlassBaseColor(),
                        cornerRadius = 18.dp,
                        manageDialogBackdrop = false,
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.outlineVariant)
                }
            )
            .padding(outerPadding)
            // An explicit height is essential: with heightIn(min=...) the
            // fillMaxHeight() selection pill could measure to zero and vanish.
            .height(segmentHeight),
    ) {
        val optionCount = options.size.coerceAtLeast(1)
        val segmentWidth = (maxWidth - spacing * (optionCount - 1)) / optionCount
        val selectedOffset by animateDpAsState(
            targetValue = (segmentWidth + spacing) * selectedIndex.coerceIn(0, optionCount - 1),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "segmentOffset",
        )
        // The background selection and labels share exactly the same height.
        Box(Modifier.fillMaxWidth().height(segmentHeight)) {
            Box(
                modifier = Modifier
                    .offset(x = selectedOffset)
                    .width(segmentWidth)
                    .height(segmentHeight)
                    .clip(innerShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, label ->
                    val enabledOption = enabledIndices == null || index in enabledIndices
                    val chosen = index == selectedIndex
                    val labelColor by animateColorAsState(
                        targetValue = if (chosen) {
                            MaterialTheme.colorScheme.onTertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(160),
                        label = "segmentLabel",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(innerShape)
                            .clickable(enabled = enabledOption) {
                                if (!chosen) {
                                    OrbHaptics.perform(tactileContext, OrbHaptics.Kind.TOGGLE)
                                    onSelect(index)
                                }
                            }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = labelColor.copy(alpha = if (enabledOption) 1f else 0.38f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}




/** Attribution required by the exchange-rate provider; only shown for FX quotes. */
@Composable
private fun PremiumFxAttribution() {
    val context = LocalContext.current
    Text(
        text = "Rates By Exchange Rate API",
        modifier = Modifier.clickable {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.exchangerate-api.com")),
            )
        }.padding(top = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
