package com.music.orb.ui.lockscreen

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import android.os.PowerManager
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.music.orb.R
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.OrbLockDisplayMode
import com.music.orb.data.rooms.RoomRepository
import com.music.orb.data.rooms.RoomSession
import com.music.orb.playback.PlayerState
import com.music.orb.playback.QueueShuffle
import com.music.orb.playback.rememberMediaController
import com.music.orb.playback.rememberPlayerState
import com.music.orb.ui.MainViewModel
import com.music.orb.ui.components.MiniPlayer
import com.music.orb.ui.player.NowPlayingCenterControls
import com.music.orb.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shares Android's wall clock (including user time/timezone changes) and refreshes
 * on the *actual minute boundary*, not an arbitrary periodic polling phase.
 * ACTION_TIME_TICK is registered dynamically because Android does not deliver it
 * to manifest receivers; SCREEN_ON catches a tick missed while the display slept.
 */
@Composable
private fun rememberOrbSystemClock(): Date {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var timeSettingsGeneration by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
                if (intent?.action != Intent.ACTION_TIME_TICK) {
                    // Restart the minute-boundary timer when Android's wall clock changes.
                    timeSettingsGeneration++
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        now = System.currentTimeMillis()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(timeSettingsGeneration) {
        while (true) {
            val millis = System.currentTimeMillis()
            val untilNextMinute = 60_000L - millis % 60_000L
            delay(untilNextMinute.coerceAtLeast(1L))
            now = System.currentTimeMillis()
        }
    }
    return Date(now)
}

/** The foreground cover uses the same two-sided card motion as MiniPlayer's artwork. */
@Composable
private fun FlippingLockscreenArtwork(
    song: com.music.orb.data.model.Song,
    artSize: Dp,
    flipRequested: Boolean,
    onFlipComplete: () -> Unit,
) {
    var displayedSong by remember { mutableStateOf(song) }
    var readyArtworkSong by remember { mutableStateOf(song) }
    val context = LocalContext.current
    val flip = remember { Animatable(0f) }
    val onCompleted by rememberUpdatedState(onFlipComplete)

    LaunchedEffect(song.videoId, song.thumbnailUrl) {
        song.artworkAt(900)?.let { url ->
            SingletonImageLoader.get(context).enqueue(
                ImageRequest.Builder(context).data(url).build()
            )
        }
        if (flipRequested && displayedSong.videoId != song.videoId) {
            flip.snapTo(0f)
            flip.animateTo(180f, tween(440, easing = FastOutSlowInEasing)) {
                // Swap artwork only while the image is edge-on; never mirror it.
                if (value >= 90f && displayedSong.videoId != song.videoId) {
                    displayedSong = song
                }
            }
        }
        displayedSong = song
        flip.snapTo(0f)
        onCompleted()
    }

    val coverReady = displayedSong.artworkAt(900) == readyArtworkSong.artworkAt(900)
    Box(
        modifier = Modifier.size(artSize)
            .graphicsLayer {
                rotationY = if (flip.value <= 90f) -flip.value else 180f - flip.value
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.12f)),
    ) {
        if (!coverReady) {
            AsyncImage(
                model = readyArtworkSong.artworkAt(900),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = displayedSong.artworkAt(900),
            contentDescription = displayedSong.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (coverReady) 1f else 0f },
            onSuccess = { readyArtworkSong = displayedSong },
        )
    }
}

/**
 * A lock-aware surface for the existing MainActivity. No new ExoPlayer or lock bypass.
 * Android can only show an already-visible Activity on the keyguard; this does not
 * try to start an Activity from a background playback service.
 */
@Composable
fun OrbLockscreenLayer() {
    val context = LocalContext.current
    val activity = context as? Activity
    val enabled by AppSettings.orbLockscreenEnabled.collectAsStateWithLifecycle()
    val entitled by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
    val isOwnerAccount by AppSettings.isOwnerAccount.collectAsStateWithLifecycle()
    val mode by AppSettings.orbLockscreenMode.collectAsStateWithLifecycle()

    if (!enabled || mode == OrbLockDisplayMode.OFF || !(entitled || isOwnerAccount)) {
        DisposableEffect(activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) activity?.setShowWhenLocked(false)
            onDispose { }
        }
        return
    }

    val controller = rememberMediaController() // Another MediaController, NEVER another player.
    val playback = rememberPlayerState(controller)
    var heldWhileLocked by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val keyguard = remember(context) { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var deviceLocked by remember { mutableStateOf(keyguard.isKeyguardLocked || !power.isInteractive) }
    // A successful system unlock must never re-display the Orb layer merely
    // because Samsung still reports keyguardLocked for a few transition frames.
    // Reset only on a NEW screen-off cycle.
    var unlockedForCurrentCycle by remember { mutableStateOf(false) }
    var overlayExitRequested by remember { mutableStateOf(false) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val overlayTranslationY by animateFloatAsState(
        targetValue = if (overlayExitRequested) -overlayHeightPx.toFloat() else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "orbLockscreenOverlayExitUp",
    )

    LaunchedEffect(overlayExitRequested) {
        if (overlayExitRequested) {
            delay(340L)
            unlockedForCurrentCycle = true
            deviceLocked = false
            overlayExitRequested = false
        }
    }

    DisposableEffect(context, owner) {
        fun update() {
            val locked = keyguard.isKeyguardLocked || !power.isInteractive
            if (!locked && deviceLocked && !unlockedForCurrentCycle) {
                overlayExitRequested = true
            }
            deviceLocked = locked
            if (!locked && !overlayExitRequested) unlockedForCurrentCycle = true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        overlayExitRequested = false
                        unlockedForCurrentCycle = false
                        deviceLocked = true
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        if (!unlockedForCurrentCycle) overlayExitRequested = true
                        deviceLocked = false
                    }
                    else -> update()
                }
            }
        }
        val actions = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // These are system-only broadcasts registered at runtime, never exported.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, actions, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, actions)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) update()
        }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // A tap on Pause must not make the locked screen vanish immediately.
    LaunchedEffect(deviceLocked, playback.isPlaying, playback.song?.videoId) {
        if (!deviceLocked || playback.song == null) heldWhileLocked = false
        else if (playback.isPlaying) heldWhileLocked = true
    }
    val active = playback.song != null && controller?.playbackState != Player.STATE_ENDED &&
        (playback.isPlaying || playback.isLoading || heldWhileLocked)

    val canOccludeKeyguard = active && (!unlockedForCurrentCycle || overlayExitRequested)
    DisposableEffect(activity, canOccludeKeyguard) {
        // Only an Activity the OS already permits to be visible may occlude
        // the keyguard. PlaybackService cannot start this from the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity?.setShowWhenLocked(canOccludeKeyguard)
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) activity?.setShowWhenLocked(false)
        }
    }

    if (((deviceLocked && active && !unlockedForCurrentCycle) || overlayExitRequested) && controller != null) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { overlayHeightPx = it.height }
                .graphicsLayer { translationY = overlayTranslationY },
        ) {
            OrbLockscreenScreen(
                playback = playback,
                controller = controller,
                onSystemUnlocked = { overlayExitRequested = true },
            )
        }
    }
}


/** Same visual surface in a dedicated task, bound to the existing MediaSession. */
@Composable
fun OrbLockscreenContent(onUnlock: () -> Unit, onPlaybackUnavailable: () -> Unit) {
    val controller = rememberMediaController()
    val playback = rememberPlayerState(controller)
    var hadSong by remember { mutableStateOf(false) }
    LaunchedEffect(controller, playback.song, playback.isPlaying, playback.isLoading) {
        if (playback.song != null) hadSong = true
        if (controller != null &&
            (controller.currentMediaItem == null || controller.playbackState == Player.STATE_ENDED) &&
            hadSong
        ) onPlaybackUnavailable()
    }
    if (controller != null && playback.song != null) {
        OrbLockscreenScreen(playback, controller, onUnlock)
    } else {
        // Never reveal the caller's last foreground screen before Media3 attaches.
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
private fun OrbLockscreenScreen(
    playback: PlayerState,
    controller: MediaController,
    onSystemUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val song = playback.song ?: return
    val activity = context as? Activity
    val keyguard = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }
    val shuffled by QueueShuffle.enabled.collectAsStateWithLifecycle()
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val listeningRoom by RoomRepository.playbackRoom.collectAsStateWithLifecycle()
    val guest = listeningRoom?.host == false
    var showLyrics by remember { mutableStateOf(false) }
    // Display the same wall-clock time as the Android system, rather than polling
    // at an arbitrary 15-second interval (which can lag after a minute rollover).
    val clock = rememberOrbSystemClock()
    val vm: MainViewModel = viewModel()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsChecked by vm.lyricsChecked.collectAsStateWithLifecycle()

    LaunchedEffect(showLyrics, song.videoId, playback.durationMs) {
        if (showLyrics) vm.loadLyrics(song.videoId, song.title, song.artist, playback.durationMs, song.albumName)
    }
    // Prevent Back from navigating the private app below this locked overlay.
    BackHandler(enabled = true) { showLyrics = false }

    val locale = Locale.getDefault()
    val date = remember(clock, locale) {
        if (locale.language == "pt") {
            val weekday = SimpleDateFormat("EEEE", locale).format(clock)
                .substringBefore("-feira")
                .replaceFirstChar { it.titlecase(locale) }
            val month = SimpleDateFormat("MMMM", locale).format(clock)
                .replaceFirstChar { it.titlecase(locale) }
            val day = SimpleDateFormat("d", locale).format(clock)
            "$weekday, $day de $month"
        } else {
            SimpleDateFormat("EEEE, d MMMM", locale).format(clock)
                .replaceFirstChar { it.titlecase(locale) }
        }
    }
    val time = remember(clock, locale) {
        SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", locale).format(clock)
    }
    val cover = remember(song.videoId, song.thumbnailUrl) { song.artworkAt(900) }
    var coverFlipRequested by remember { mutableStateOf(false) }
    // Re-arm if Skip Next did not change the track (for example, queue end).
    LaunchedEffect(coverFlipRequested) {
        if (coverFlipRequested) {
            delay(2_000L)
            coverFlipRequested = false
        }
    }
    val palette = rememberArtworkPalette(song.thumbnailUrl, dark = true)
    val powerSaving by AppSettings.orbLockscreenPowerSaving.collectAsStateWithLifecycle()
    val miniPlayerHaze = remember { HazeState() }
    // Android/Samsung own the face scan. A swipe only asks the OS to dismiss
    // its keyguard; PIN/biometric checks are never bypassed by Orb.
    var unlockRequestPending by remember { mutableStateOf(false) }
    val requestSystemUnlock: () -> Unit = {
        if (activity != null && !unlockRequestPending) {
            unlockRequestPending = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!keyguard.isKeyguardLocked) {
                    onSystemUnlocked()
                } else {
                    // The system chooses facial recognition, PIN or another
                    // permitted unlock method. Orb never authenticates users.
                    keyguard.requestDismissKeyguard(
                        activity,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() { onSystemUnlocked() }
                            override fun onDismissCancelled() { unlockRequestPending = false }
                            override fun onDismissError() { unlockRequestPending = false }
                        },
                    )
                }
            } else {
                unlockRequestPending = false
            }
        }
    }
    // Keep the listener installed once, while using the current callback after
    // each recomposition. The parent observes the INITIAL pointer pass so a
    // vertical swipe beginning on the artwork, player, buttons or lyrics
    // is recognized before a child scroll or click handler consumes it.
    val currentUnlockRequest by rememberUpdatedState(requestSystemUnlock)
    val backgroundTouches = remember { MutableInteractionSource() }

    // Explicitly capture touches on every empty pixel. Previously the background
    // only painted pixels, allowing a tap to activate OrbApp beneath this overlay.
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val thresholdPx = 56.dp.toPx()
                    var dragX = 0f
                    var dragY = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val distance = change.positionChange()
                        dragX += distance.x
                        dragY += distance.y
                        if (shouldRequestOrbUnlock(dragX, dragY, thresholdPx)) {
                            // Cancel a click/scroll in the same frame, rather
                            // than accidentally activating controls underneath.
                            change.consume()
                            currentUnlockRequest()
                            break
                        }
                    }
                }
            }
            .background(Color.Black).clickable(
            interactionSource = backgroundTouches,
            indication = null,
            onClick = {},
        )
    ) {
        // 70% perceptual blur (~42 dp at this screen's design scale) and EXACT
        // 10% black overlay. No zoomed-in *foreground* album art.
        if (!powerSaving) {
            AsyncImage(
                model = song.artworkAt(480), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(42.dp),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Size the artwork around the genuinely available height, instead
            // of pushing it away from the clock with weight(1f) spacers.
            // The mini-player's built-in 16 dp horizontal margins are taken
            // into account so its glass edges align with the album image.
            val shortDisplay = maxHeight < 720.dp
            val artSize: Dp = minOf(
                maxWidth - 48.dp,
                maxHeight * (if (shortDisplay) 0.32f else 0.37f),
                400.dp,
            ).coerceAtLeast(160.dp)
            val clockFontSize = if (maxWidth < 340.dp) 82.sp else 100.sp
            val playerOuterWidth = artSize + 32.dp
            // The center cluster is aligned to the artwork and mini-player, not
            // distributed with the Cast/Queue outer buttons on this surface.
            val coverGap = if (shortDisplay) 18.dp else 26.dp
            Column(
                modifier = Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (shortDisplay) 38.dp else 56.dp))
                Text(
                    date,
                    fontSize = 17.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    time,
                    fontSize = clockFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 102.sp,
                    textAlign = TextAlign.Center,
                )
                // Compact fixed gap places the artwork directly under the
                // clock. Remaining vertical space moves *below* the controls.
                Spacer(Modifier.height(coverGap))
                FlippingLockscreenArtwork(
                    song = song,
                    artSize = artSize,
                    flipRequested = coverFlipRequested,
                    onFlipComplete = { coverFlipRequested = false },
                )
                // Match the clock-to-cover gap, giving the artwork breathing room
                // without pushing the controls out of the visible viewport.
                Spacer(Modifier.height(coverGap))
                MiniPlayer(
                    song = song,
                    isPlaying = playback.isPlaying,
                    isLoading = playback.isLoading,
                    hazeState = miniPlayerHaze,
                    onPlayPause = {
                        if (guest) {
                            if (RoomSession.guestPaused.value) controller.play() else controller.pause()
                        } else if (playback.isPlaying) controller.pause() else controller.play()
                    },
                    onNext = {
                        if (!guest && controller.hasNextMediaItem()) {
                            coverFlipRequested = true
                            controller.seekToNextMediaItem()
                        }
                    },
                    onExpand = {}, // Do not expose private app UI before authentication.
                    onClearQueue = {},
                    controlsEnabled = !guest,
                    includeArtwork = false,
                    // Artwork tint is the *glass pigment*, not a solid fill.
                    solidBackgroundColor = if (powerSaving) Color.Black else palette.playerBackground,
                    solidContentColor = Color.White,
                    lockscreenGlass = !powerSaving,
                    // Transport glyphs and outline always remain white regardless of artwork.
                    playPauseBackgroundColor = if (powerSaving) Color.Transparent else Color.White,
                    playPauseForegroundColor = Color.White,
                    lockscreenOutlinedControls = powerSaving,
                    enableSwipeToClear = false,
                    modifier = Modifier.width(playerOuterWidth),
                )
                Spacer(Modifier.height(if (shortDisplay) 8.dp else 10.dp))
                // Only Shuffle / Repeat / Lyrics, with the exact same
                // three-part segmented component and 42dp height as Now Playing.
                NowPlayingCenterControls(
                    shuffleEnabled = shuffled,
                    repeatMode = playback.repeatMode,
                    lyricsOpen = showLyrics,
                    lyricsEnabled = syncedLyricsEnabled,
                    transportEnabled = listeningRoom == null,
                    tintColor = Color.White,
                    surfaceColor = if (powerSaving) Color.Black else palette.playerBackground,
                    onToggleShuffle = { if (listeningRoom == null) QueueShuffle.toggle(controller) },
                    onCycleRepeat = {
                        if (listeningRoom == null) controller.repeatMode = when (controller.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    },
                    onToggleLyrics = { showLyrics = !showLyrics },
                    modifier = Modifier.width(artSize),
                )
                Spacer(Modifier.weight(1f))
                // Gesture hint only: the whole lockscreen now handles swipes
                // in EITHER vertical direction, starting on ANY visible area.
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.KeyboardArrowUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = .8f),
                    )
                    Text(
                        stringResource(R.string.orb_lockscreen_swipe_to_unlock),
                        color = Color.White.copy(alpha = .8f),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (showLyrics) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(28.dp), color = Color(0xF2212028)) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.orb_lockscreen_sync_lyrics), modifier = Modifier.weight(1f),
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        IconButton(onClick = { showLyrics = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.orb_lockscreen_close), tint = Color.White)
                        }
                    }
                    if (!lyricsChecked || lyrics == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (lyricsChecked) stringResource(R.string.orb_lockscreen_lyrics_unavailable) else stringResource(R.string.orb_lockscreen_lyrics_loading),
                                color = Color.White.copy(alpha = .8f))
                        }
                    } else {
                        val activeLine = lyrics.orEmpty().indexOfLast { it.timeMs <= playback.positionMs }
                        val lyricListState = rememberLazyListState()
                        LaunchedEffect(activeLine) {
                            if (activeLine >= 0) lyricListState.animateScrollToItem(activeLine)
                        }
                        LazyColumn(state = lyricListState) {
                            itemsIndexed(lyrics.orEmpty()) { index, line ->
                                if (line.text.isNotBlank()) {
                                    Text(
                                        line.text, fontSize = 23.sp, lineHeight = 30.sp,
                                        fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.White.copy(alpha = if (index == activeLine) 1f else .55f),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
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
