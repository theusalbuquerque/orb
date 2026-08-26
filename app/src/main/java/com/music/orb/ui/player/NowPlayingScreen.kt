package com.music.orb.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateDpAsState
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.icons.BitChordIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.NerdStats
import com.music.orb.data.settings.TrackAnalysisState
import com.music.orb.data.canvas.CanvasArtwork
import com.music.orb.data.canvas.CanvasRepository
import com.music.orb.data.lyrics.LyricLine
import com.music.orb.data.lyrics.LyricsSource
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AudioQuality
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.playback.BACK_RESTARTS_AFTER_MS
import com.music.orb.playback.autoplaySectionStart
import java.util.concurrent.TimeUnit

private const val ART_PX = 1200
private const val ALBUM_SETTLE_MS = 700L
private const val SEEK_SETTLE_TOLERANCE_MS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
private val ART_BOX_TOP_PAD = 14.dp
private const val HERO_FADE_FRACTION = 0.42f
private val PLAYER_GUTTER = 30.dp
private val PLAYER_MAX_WIDTH = 560.dp

@Composable
fun fullBleedArtworkAvailable(): Boolean =
    LocalConfiguration.current.screenWidthDp.dp <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

private const val LYRIC_FADE_FRACTION = 0.28f
private const val LYRIC_FADE_MIN_MS = 160f
private const val LYRIC_FADE_MAX_MS = 700f

private const val UNSUNG_ALPHA = 0.45f
private const val UNSUNG_ALPHA_STRIP = 0.55f

private const val GLOW_ALPHA = 0.62f
private val GLOW_RADIUS = 9.dp
private val GLOW_TRAIL = 62.dp
private const val GLOW_TRAIL_FLOOR = 0.55f
private val GLOW_ROOM = 10.dp

private const val INSTRUMENTAL_MARK = "Instrumental"

private val INTRO_LINES = listOf(
    "Beat's landing", "Song's starting", "Intro's cooking", "Warming up", "Here we go",
    "Setting the mood", "Drums are in", "Bass first, words later", "Turn it up", "Vibe check"
)

private val LYRICS_LOADING_LINES = listOf(
    "Getting lyrics", "Chasing the words", "Digging up the lyrics", "Words incoming", "On the hunt for lyrics"
)

private const val LYRICS_UNAVAILABLE_HOLD_MS = 5_000L
private const val LYRICS_UNAVAILABLE_FADE_MS = 900

@Composable
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()

    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    var canvas by remember(song.videoId) { mutableStateOf<CanvasArtwork?>(null) }
    var canvasRendered by remember(song.videoId) { mutableStateOf(false) }
    var canvasFrame by remember(song.videoId) { mutableStateOf<Bitmap?>(null) }
    val canvasCover = remember(song.videoId) { mutableFloatStateOf(0f) }
    val stillCovered by remember(song.videoId) {
        derivedStateOf { canvasCover.floatValue > 0.999f }
    }
    val meshColors = rememberArtworkColors(song.thumbnailUrl, canvasFrame)
    LaunchedEffect(song.videoId, song.albumName, canvasEnabled) {
        if (!canvasEnabled) {
            canvas = null
            return@LaunchedEffect
        }
        canvas = CanvasRepository.cached(song) ?: canvas
        if (canvas == null && song.albumName == null) delay(ALBUM_SETTLE_MS)
        canvas = CanvasRepository.canvasFor(song) ?: canvas
    }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var queueOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(song.videoId) { lyricsOpen = false }

    BackHandler(enabled = lyricsOpen) { lyricsOpen = false }

    val queueProgress by animateFloatAsState(
        targetValue = if (queueOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "queueProgress",
    )

    val swipeThreshold = with(density) { 72.dp.toPx() }
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeSettle by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "swipeOffset",
    )

    var pendingSeek by remember { mutableStateOf<Float?>(null) }

    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val shown = when {
        scrubbing -> scrubValue
        pendingSeek != null -> pendingSeek!!
        else -> fraction.coerceIn(0f, 1f)
    }

    LaunchedEffect(positionMs, durationMs, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (durationMs > 0 && abs(positionMs - (target * durationMs).toLong()) < SEEK_SETTLE_TOLERANCE_MS) {
            pendingSeek = null
        }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        pendingSeek = null
    }
    LaunchedEffect(song.videoId) { pendingSeek = null }

    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artScale",
    )

    val p = if (lyricsOpen) 1f else queueProgress
    val fullBleedArt by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val heroMode = fullBleedArt && fullBleedArtworkAvailable()
    var artLoaded by remember(song.videoId) { mutableStateOf(false) }
    var heroSettled by remember { mutableStateOf(false) }
    LaunchedEffect(artLoaded) { if (artLoaded) heroSettled = true }
    val heroClip = canvas?.takeIf { heroMode && p < 0.5f }
    val heroT by animateFloatAsState(
        targetValue = if (
            heroMode && p < 0.5f && (canvasRendered || artLoaded || heroSettled)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "heroCanvas",
    )
    var heroHeight by remember { mutableStateOf(0.dp) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = modifier.fillMaxSize()) {
        MeshGradientBackground(palette = meshColors, trackKey = song.videoId)

        if (heroHeight > 0.dp) {
            if (heroMode && !(stillCovered && heroClip != null) &&
                (p < 0.5f || heroT > 0.001f)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(song.artworkAt(ART_PX))
                        .size(ART_PX)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(heroHeight)
                        .graphicsLayer {
                            alpha = heroT *
                                    (1f - if (heroClip != null) canvasCover.floatValue else 0f)
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startY = size.height * (1f - HERO_FADE_FRACTION),
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }

            if (heroMode) {
                heroClip?.let { clip ->
                    CanvasArtworkPlayer(
                        canvas = clip,
                        isPlaying = isPlaying,
                        onRenderedChanged = { canvasRendered = it },
                        onFrameCaptured = { canvasFrame = it },
                        onCoverChanged = { canvasCover.floatValue = it },
                        bottomFade = HERO_FADE_FRACTION,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(heroHeight),
                    )
                }
            }

            if (heroT > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(statusBarTop + 24.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.38f * heroT),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragCancel = { swipeOffset = 0f },
                        onDragEnd = {
                            when {
                                total <= -swipeThreshold && hasNext -> onNext()
                                total >= swipeThreshold && hasPrevious -> onPrevious()
                            }
                            swipeOffset = 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            total += delta
                            swipeOffset = total * 0.35f
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = PLAYER_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = PLAYER_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(top = ART_BOX_TOP_PAD, bottom = 18.dp),
                ) {
                    val fullArt = minOf(maxWidth, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT)
                        .coerceAtLeast(THUMB_SIZE)
                    val groupTop = ((maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT) / 2)
                        .coerceAtLeast(0.dp)
                    val artSize = lerp(fullArt, THUMB_SIZE, p)
                    val artTop = lerp(groupTop, 0.dp, p)
                    val artStart = lerp((maxWidth - fullArt) / 2, 0.dp, p)
                    val titleTop = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p)
                    val titleStart = lerp(0.dp, THUMB_SIZE + 12.dp, p)

                    val bannerBottom = statusBarTop + ART_BOX_TOP_PAD +
                            groupTop + fullArt + ART_TITLE_GAP / 2
                    SideEffect { heroHeight = bannerBottom }

                    Box(
                        modifier = Modifier
                            .offset(x = artStart, y = artTop)
                            .size(artSize)
                            .graphicsLayer {
                                val idle = artScale + (1f - artScale) * p
                                scaleX = idle
                                scaleY = idle
                                translationX = swipeSettle * (1f - p)
                            }
                            .then(
                                if (queueOpen || lyricsOpen) {
                                    Modifier.clickable {
                                        queueOpen = false
                                        lyricsOpen = false
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = 1f - heroT }
                                .shadow(
                                    if (artLoaded) lerp(14.dp, 6.dp, p) else 0.dp,
                                    RoundedCornerShape(lerp(10.dp, 7.dp, p)),
                                )
                                .clip(RoundedCornerShape(lerp(10.dp, 7.dp, p)))
                                .background(Color.Black.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!artLoaded) {
                                Icon(
                                    imageVector = BitChordIcons.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(lerp(40.dp, 20.dp, p)),
                                )
                            }
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(song.artworkAt(ART_PX))
                                    .size(ART_PX)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onState = { artLoaded = it is AsyncImagePainter.State.Success },
                                modifier = Modifier.fillMaxSize(),
                            )

                            if (!heroMode) {
                                canvas?.takeIf { p < 0.5f }?.let { clip ->
                                    CanvasArtworkPlayer(
                                        canvas = clip,
                                        isPlaying = isPlaying,
                                        onRenderedChanged = { canvasRendered = it },
                                        onFrameCaptured = { canvasFrame = it },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }

                    val swipeHintProgress = (abs(swipeSettle) / swipeThreshold)
                        .coerceIn(0f, 1f) * (1f - p)
                    if (swipeHintProgress > 0.01f) {
                        val showNext = swipeSettle < 0f
                        val enabled = if (showNext) hasNext else hasPrevious
                        Icon(
                            imageVector = if (showNext) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                            contentDescription = null,
                            tint = Color.White.copy(
                                alpha = swipeHintProgress * if (enabled) 0.85f else 0.3f,
                            ),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = artTop + artSize + (ART_TITLE_GAP - 16.dp) / 2)
                                .size(16.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = titleTop)
                            .padding(start = titleStart)
                            .height(HEADER_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val titleSize = lerp(20.sp, 16.sp, p)
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = titleSize,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.W500,
                                    fontSize = titleSize,
                                ),
                                color = Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.opensPage(song.artistId, onOpenArtist),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (signedIn && song.localUri == null) {
                            val liked = likeStatus == LikeStatus.LIKE
                            CircleGlyph(
                                icon = if (liked) BitChordIcons.HeartFilled else BitChordIcons.Heart,
                                contentDescription = if (liked) "Remove from Liked Music" else "Like",
                                onClick = onToggleLike,
                                active = liked,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        CircleGlyph(
                            icon = Icons.Rounded.MoreHoriz,
                            contentDescription = "More",
                            onClick = onOpenMenu,
                        )
                    }

                    if (lyricsOpen) {
                        LyricsPanel(
                            lines = lyrics.orEmpty(),
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                            onSeekToLine = onSeek,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = HEADER_HEIGHT + 10.dp),
                        )
                    }

                    if (!lyricsOpen && queueProgress > 0.01f) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = HEADER_HEIGHT + 10.dp)
                                .graphicsLayer {
                                    alpha = ((queueProgress - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                    translationY = (1f - queueProgress) * 26.dp.toPx()
                                },
                        ) {
                            InlineQueue(
                                queue = queue,
                                currentIndex = queueIndex,
                                autoplayEnabled = autoplayEnabled,
                                onJumpTo = onJumpTo,
                                onRemove = onRemoveFromQueue,
                                onMove = onMoveInQueue,
                                onClear = onClearQueue,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = PLAYER_MAX_WIDTH)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!lyricsOpen && syncedLyricsEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (-18).dp)
                                .graphicsLayer { alpha = 1f - queueProgress },
                        ) {
                            if (!lyrics.isNullOrEmpty()) {
                                CurrentLyricLine(
                                    lines = lyrics,
                                    trackKey = song.videoId,
                                    positionMs = positionMs,
                                    isPlaying = isPlaying,
                                    durationMs = durationMs,
                                    onClick = { if (!queueOpen) lyricsOpen = true },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (lyricsUnavailable) {
                                LyricsUnavailableLine(
                                    trackKey = song.videoId,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LyricsLoadingLine(
                                    trackKey = song.videoId,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    val mixing by AppSettings.smartMixInProgress.collectAsStateWithLifecycle()
                    val transitionWindow by AppSettings.smartTransitionWindow.collectAsStateWithLifecycle()

                    Box(modifier = Modifier.offset(y = (-24).dp)) {
                        ThinSlider(
                            value = shown,
                            onValueChange = {
                                scrubbing = true
                                scrubValue = it
                            },
                            onValueChangeFinished = {
                                pendingSeek = scrubValue
                                onSeekFraction(scrubValue)
                                scrubbing = false
                            },
                            mixing = mixing && !scrubbing,
                            transitionWindow = transitionWindow
                                ?.takeIf { !scrubbing && it.end > it.start }
                                ?.let { it.start..it.end },
                        )
                    }

                    val losslessOn by AppSettings.losslessAudio.collectAsStateWithLifecycle()
                    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
                    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
                    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
                    val losslessRequested = losslessOn &&
                            (if (metered == true) cellularQuality else wifiQuality) == AudioQuality.HIGH
                    val racingLossless by NerdStats.racingLossless.collectAsStateWithLifecycle()
                    val stillRacing = song.videoId in racingLossless

                    val showNerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
                    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-33).dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatTime((shown * durationMs).toLong()),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                            Text(
                                text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }

                        LosslessOrStats(
                            isLoading = isLoading,
                            stillRacing = stillRacing,
                            losslessRequested = losslessRequested,
                            nerdStats = nerdStats,
                            showNerdStats = showNerdStats,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 8.dp),
                        )
                    }

                    if (lyricsOpen) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(Color.White.copy(alpha = 0.10f))
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = lyricsSource?.let { "Lyrics by ${it.label}" }
                                    ?: "No lyrics found",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                    } else {
                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (-24).dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TransportGlyph(
                                icon = Icons.Rounded.FastRewind,
                                contentDescription = "Previous",
                                size = 46.dp,
                                onClick = onPrevious,
                                enabled = hasPrevious || positionMs > BACK_RESTARTS_AFTER_MS,
                            )
                            if (isLoading) {
                                Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(38.dp),
                                    )
                                }
                            } else {
                                TransportGlyph(
                                    icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    size = 62.dp,
                                    onClick = onPlayPause,
                                )
                            }
                            TransportGlyph(
                                icon = Icons.Rounded.FastForward,
                                contentDescription = "Next",
                                size = 46.dp,
                                onClick = onNext,
                                enabled = hasNext,
                            )
                        }

                        Spacer(Modifier.height(62.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (-12).dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BottomGlyph(
                                icon = BitChordIcons.Shuffle,
                                contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                                onClick = onToggleShuffle,
                                highlighted = shuffleEnabled,
                            )
                            BottomGlyph(
                                icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                    BitChordIcons.RepeatOne
                                } else {
                                    BitChordIcons.Repeat
                                },
                                contentDescription = when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> "Repeat one"
                                    Player.REPEAT_MODE_ALL -> "Repeat all"
                                    else -> "Repeat off"
                                },
                                onClick = onCycleRepeat,
                                highlighted = repeatMode != Player.REPEAT_MODE_OFF,
                            )
                            BottomGlyph(
                                icon = BitChordIcons.Infinity,
                                contentDescription = if (autoplayEnabled) "AutoPlay on" else "AutoPlay off",
                                onClick = onToggleAutoplay,
                                highlighted = autoplayEnabled,
                            )
                            BottomGlyph(
                                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Up next",
                                onClick = {
                                    lyricsOpen = false
                                    queueOpen = !queueOpen
                                },
                                highlighted = queueOpen,
                            )
                        }

                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberLyricClock(positionMs: Long, isPlaying: Boolean): MutableLongState {
    val clock = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        clock.longValue = positionMs
        if (!isPlaying) return@LaunchedEffect
        var previousFrame = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame ->
                clock.longValue += frame - previousFrame
                previousFrame = frame
            }
        }
    }
    return clock
}

@Composable
private fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    val sweep = Modifier.drawWithContent {
        val position = clock.longValue
        when {
            position >= line.endMs -> drawContent()
            position <= line.timeMs -> Unit
            else -> layout?.let { sweepTo(it, line.revealedChars(position)) }
        }
    }

    Box(modifier) {
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = room,
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = Color.White,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha * line.glowIntensity(clock.longValue) }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        val measured = layout ?: return@drawWithContent
                        val position = clock.longValue
                        glowAt(
                            layout = measured,
                            revealedChars = line.revealedChars(position),
                            intensity = line.glowIntensity(position),
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            maxLines = maxLines,
            overflow = overflow,
            modifier = room.then(sweep),
        )
    }
}

private fun ContentDrawScope.glowAt(
    layout: TextLayoutResult,
    revealedChars: Float,
    intensity: Float,
) {
    val length = layout.layoutInput.text.length
    if (length == 0 || revealedChars <= 0f || intensity <= 0f) return

    val edge = revealedChars.coerceIn(0f, length.toFloat())
    val visualLine = layout.getLineForOffset(edge.toInt().coerceIn(0, length - 1))
    val lineStart = layout.getLineStart(visualLine)
    val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)

    val right = horizontalAt(layout, edge.coerceIn(lineStart.toFloat(), lineEnd.toFloat()), lineStart, lineEnd)
    val trail = GLOW_TRAIL.toPx() * (GLOW_TRAIL_FLOOR + (1f - GLOW_TRAIL_FLOOR) * intensity)
    val left = (right - trail).coerceAtLeast(layout.getLineLeft(visualLine))
    if (right <= left) return

    clipRect(
        left = left,
        top = layout.getLineTop(visualLine),
        right = right,
        bottom = layout.getLineBottom(visualLine),
    ) {
        this@glowAt.drawContent()
    }

    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.45f to Color.White.copy(alpha = 0.22f),
            1f to Color.White,
            startX = left,
            endX = right,
        ),
        blendMode = BlendMode.DstIn,
    )
}

private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    lineStart: Int,
    lineEnd: Int,
): Float {
    val index = chars.toInt().coerceIn(lineStart, lineEnd)
    val here = layout.getHorizontalPosition(index, usePrimaryDirection = true)
    val next = layout.getHorizontalPosition(
        (index + 1).coerceAtMost(lineEnd),
        usePrimaryDirection = true,
    )
    return here + (next - here) * (chars - index)
}

private fun ContentDrawScope.sweepTo(layout: TextLayoutResult, revealedChars: Float) {
    if (revealedChars <= 0f) return
    if (revealedChars >= layout.layoutInput.text.length) {
        drawContent()
        return
    }
    for (visualLine in 0 until layout.lineCount) {
        val start = layout.getLineStart(visualLine)
        if (revealedChars <= start) return
        val end = layout.getLineEnd(visualLine, visibleEnd = true)
        val right = if (revealedChars >= end) {
            layout.getLineRight(visualLine)
        } else {
            horizontalAt(layout, revealedChars, start, end)
        }
        clipRect(
            left = layout.getLineLeft(visualLine),
            top = layout.getLineTop(visualLine),
            right = right,
            bottom = layout.getLineBottom(visualLine),
        ) {
            this@sweepTo.drawContent()
        }
    }
}

@Composable
private fun LyricsPanel(
    lines: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = rememberLyricClock(positionMs, isPlaying)
    val activeLine by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
    }
    val listState = rememberLazyListState()
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    var browsing by remember { mutableStateOf(false) }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    val glowing = !reduceAnimation && !reduceDynamicBlur &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) browsing = true
        }
    }

    val currentLine by rememberUpdatedState(activeLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine }
        }
    }
    LaunchedEffect(browsing, activeOnScreen, listState.isScrollInProgress) {
        if (browsing && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsing = false
        }
    }

    LaunchedEffect(browsing, listState.isScrollInProgress) {
        if (browsing && !listState.isScrollInProgress) {
            delay(5_000)
            browsing = false
        }
    }

    var placed by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(activeLine, browsing) {
        if (!browsing && !listState.isScrollInProgress &&
            activeLine >= 0 && activeLine in lines.indices
        ) {
            val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }
                .first { it > 0 }
            val third = viewport / 3
            if (placed) {
                listState.animateScrollToItem(activeLine, scrollOffset = -third)
            } else {
                listState.scrollToItem(activeLine, scrollOffset = -third)
                placed = true
            }
        }
    }

    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No lyrics for this track",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .bleedHorizontally(PLAYER_GUTTER)
            .nestedScroll(keepScroll)
            .fadingEdges(),
        contentPadding = PaddingValues(
            vertical = 40.dp - GLOW_ROOM,
            horizontal = PLAYER_GUTTER - GLOW_ROOM,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val distance = if (activeLine < 0) 0 else abs(index - activeLine)
            val isActive = index == activeLine
            val blur by animateDpAsState(
                targetValue = when {
                    reduceDynamicBlur || browsing || isActive -> 0.dp
                    else -> (distance * 1.6f).coerceAtMost(7f).dp
                },
                label = "lyricBlur",
            )
            val lineAlpha by animateFloatAsState(
                targetValue = when {
                    browsing -> 1f
                    isActive -> 1f
                    else -> (0.5f - distance * 0.06f).coerceAtLeast(0.22f)
                },
                label = "lyricAlpha",
            )
            if (line.isGap) {
                val noteSize by animateDpAsState(
                    targetValue = if (isActive) 34.dp else 26.dp,
                    label = "noteSize",
                )
                Icon(
                    imageVector = BitChordIcons.MusicNote,
                    contentDescription = "Instrumental",
                    tint = Color.White.copy(alpha = lineAlpha),
                    modifier = Modifier
                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSeekToLine(line.timeMs) }
                        .padding(GLOW_ROOM)
                        .size(noteSize),
                )
            } else {
                val style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 27.sp,
                    lineHeight = 33.sp,
                )
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.04f else 1f,
                    label = "lyricScale",
                )
                val glow by animateFloatAsState(
                    targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                    animationSpec = tween(durationMillis = 420),
                    label = "lyricGlow",
                )
                val shape = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        alpha = lineAlpha
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSeekToLine(line.timeMs) }
                if (line.isWordSynced && !browsing) {
                    val tail by animateFloatAsState(
                        targetValue = if (isActive) UNSUNG_ALPHA else 1f,
                        label = "lyricTail",
                    )
                    SweptLyricLine(
                        line = line,
                        clock = clock,
                        style = style,
                        dimAlpha = tail,
                        modifier = shape,
                        glowAlpha = glow,
                        glowRoom = GLOW_ROOM,
                    )
                } else {
                    Text(
                        text = line.text,
                        style = style,
                        color = Color.White,
                        modifier = shape.padding(GLOW_ROOM),
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentLyricLine(
    lines: List<LyricLine>,
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = rememberLyricClock(positionMs, isPlaying)

    val index by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
    }
    val current = lines.getOrNull(index)
    val instrumental = current == null || current.isGap
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    val intro = instrumental && firstSung >= 0 && index < firstSung
    val introLine = remember(trackKey) { INTRO_LINES.random() }
    val text = when {
        intro -> introLine
        instrumental -> INSTRUMENTAL_MARK
        else -> current!!.text
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .graphicsLayer {
                if (instrumental) {
                    alpha = 0.5f
                    return@graphicsLayer
                }
                val start = lines.getOrNull(index)?.timeMs ?: 0L
                val end = lines.getOrNull(index + 1)?.timeMs
                    ?: durationMs.takeIf { it > start }
                    ?: (start + 4_000L)
                val fade = ((end - start) * LYRIC_FADE_FRACTION)
                    .coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                val remaining = (end - clock.longValue).toFloat()
                alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
            },
    ) {
        if (instrumental) {
            Icon(
                imageVector = BitChordIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        val swept = current?.takeIf { !instrumental && it.isWordSynced }
        if (swept != null) {
            SweptLyricLine(
                line = swept,
                clock = clock,
                style = MaterialTheme.typography.titleMedium,
                dimAlpha = UNSUNG_ALPHA_STRIP,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun LyricsUnavailableLine(trackKey: Any, modifier: Modifier = Modifier) {
    var visible by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        delay(LYRICS_UNAVAILABLE_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.55f else 0f,
        animationSpec = tween(durationMillis = LYRICS_UNAVAILABLE_FADE_MS),
        label = "lyricsUnavailableAlpha",
    )
    Text(
        text = "Lyrics not available",
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun LyricsLoadingLine(trackKey: Any, modifier: Modifier = Modifier) {
    val text = remember(trackKey) { LYRICS_LOADING_LINES.random() }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "glyphDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun TransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )
    Box(
        modifier = Modifier
            .size(size + 12.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun BottomGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.20f) else Color.Transparent,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f),
            modifier = Modifier.size(26.dp),
        )
    }
}

private fun keepScrollInList(listState: LazyListState) = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = available

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (available.y > 0f && !listState.canScrollBackward) available else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

private fun Modifier.opensPage(browseId: String?, onOpen: (String) -> Unit): Modifier =
    if (browseId == null) {
        this
    } else {
        clip(RoundedCornerShape(6.dp)).clickable { onOpen(browseId) }
    }

private fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) {
        placeable.place(-(placeable.width - width) / 2, 0)
    }
}

private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 28.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = fade,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun InlineQueue(
    queue: List<Song>,
    currentIndex: Int,
    autoplayEnabled: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    val autoplayStart = remember(queue, currentIndex) {
        autoplaySectionStart(queue.map { it.fromAutoplay }, currentIndex)
    }
    LaunchedEffect(currentIndex) {
        if (currentIndex in queue.indices) {
            listState.scrollToItem(currentIndex + if (currentIndex >= autoplayStart) 1 else 0)
        }
    }

    val manualRows = queue.subList(0, autoplayStart)
    val autoplayRows = queue.subList(autoplayStart, queue.size)
    val manualKeys = remember(manualRows) { manualRows.stableQueueKeys() }
    val autoplayKeys = remember(autoplayRows) { autoplayRows.stableQueueKeys("autoplay/") }

    val headingShown = autoplayEnabled || autoplayStart < queue.size
    val headingCount = if (headingShown) 1 else 0
    val firstMovable = (currentIndex + 1).coerceIn(0, autoplayStart)
    val manualDrag = rememberQueueDragState(
        listState = listState,
        lazyRange = firstMovable until autoplayStart,
        lazyOffset = 0,
        onMove = onMove,
    )
    val autoplayDrag = rememberQueueDragState(
        listState = listState,
        lazyRange = (autoplayStart + headingCount) until (autoplayStart + headingCount + autoplayRows.size),
        lazyOffset = headingCount,
        onMove = onMove,
    )

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Clear",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .bleedHorizontally(PLAYER_GUTTER)
                .nestedScroll(keepScroll)
                .fadingEdges(),
            contentPadding = PaddingValues(horizontal = PLAYER_GUTTER),
        ) {
            itemsIndexed(
                items = manualRows,
                key = { index, _ -> manualKeys[index] },
            ) { index, song ->
                val key = manualKeys[index]
                val dragging = manualDrag.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = index == currentIndex,
                    onClick = { onJumpTo(index) },
                    onRemove = { onRemove(index) },
                    draggable = index >= firstMovable,
                    dragging = dragging,
                    onDragStart = { manualDrag.onDragStart(key) },
                    onDrag = manualDrag::onDrag,
                    onDragEnd = manualDrag::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) manualDrag.dragOffset else 0f }
                        .then(if (dragging) Modifier else Modifier.animateItem()),
                )
            }
            if (autoplayEnabled || autoplayStart < queue.size) {
                item(key = "autoplay-heading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            BitChordIcons.Infinity,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "AutoPlay",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Text(
                                text = if (autoplayStart < queue.size) {
                                    "Similar music, picked to follow on"
                                } else {
                                    "Similar music will keep playing"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
            itemsIndexed(
                items = autoplayRows,
                key = { index, _ -> autoplayKeys[index] },
            ) { index, song ->
                val at = autoplayStart + index
                val key = autoplayKeys[index]
                val dragging = autoplayDrag.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = at == currentIndex,
                    onClick = { onJumpTo(at) },
                    onRemove = { onRemove(at) },
                    draggable = true,
                    dragging = dragging,
                    onDragStart = { autoplayDrag.onDragStart(key) },
                    onDrag = autoplayDrag::onDrag,
                    onDragEnd = autoplayDrag::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) autoplayDrag.dragOffset else 0f }
                        .then(if (dragging) Modifier else Modifier.animateItem()),
                )
            }
        }
    }
}

private fun List<Song>.stableQueueKeys(prefix: String = ""): List<String> {
    val seen = HashMap<String, Int>()
    return map { song ->
        val n = seen.getOrDefault(song.videoId, 0)
        seen[song.videoId] = n + 1
        if (n == 0) "$prefix${song.videoId}" else "$prefix${song.videoId}#$n"
    }
}

@Composable
private fun rememberQueueDragState(
    listState: LazyListState,
    lazyRange: IntRange,
    lazyOffset: Int,
    onMove: (Int, Int) -> Unit,
): QueueDragState {
    val state = remember(listState) { QueueDragState(listState) }
    state.lazyRange = lazyRange
    state.lazyOffset = lazyOffset
    state.onMove = onMove
    return state
}

private class QueueDragState(private val listState: LazyListState) {
    var lazyRange: IntRange = IntRange.EMPTY
    var lazyOffset: Int = 0
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    var draggedKey by mutableStateOf<Any?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    private var awaiting: Int? = null

    fun onDragStart(key: Any) {
        draggedKey = key
        dragOffset = 0f
        awaiting = null
    }

    fun onDrag(deltaY: Float) {
        val key = draggedKey ?: return
        dragOffset += deltaY
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.find { it.key == key } ?: return
        awaiting?.let { if (dragged.index != it) return else awaiting = null }
        val draggedCenter = dragged.offset + dragged.size / 2f + dragOffset
        val target = items
            .filter { it.index in lazyRange && it.index != dragged.index }
            .minByOrNull { abs((it.offset + it.size / 2f) - draggedCenter) }
            ?: return
        if (abs(draggedCenter - (target.offset + target.size / 2f)) > target.size / 2f) return
        onMove(dragged.index - lazyOffset, target.index - lazyOffset)
        dragOffset += (dragged.offset - target.offset)
        awaiting = target.index
    }

    fun onDragEnd() {
        draggedKey = null
        dragOffset = 0f
        awaiting = null
    }
}

@Composable
private fun InlineQueueRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
    dragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (dragging) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draggable) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(20.dp)
                    .offset(x = (-4).dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                        )
                    },
            )
            Spacer(Modifier.width(4.dp))
        }
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .thumbnailBorder(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = "Now playing",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun LosslessOrStats(
    isLoading: Boolean,
    stillRacing: Boolean,
    losslessRequested: Boolean,
    nerdStats: NerdStats.Snapshot?,
    showNerdStats: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        (stillRacing || (isLoading && losslessRequested)) && nerdStats?.isLossless != true -> LosslessLabel(
            text = "Upgrading Quality",
            animated = false,
            modifier = modifier,
        )
        nerdStats?.isLossless == true -> LosslessLabel(
            text = if (nerdStats.isHiRes) "Hi-Res Lossless" else "Lossless",
            animated = true,
            modifier = modifier,
        )
        showNerdStats && nerdStats != null -> NerdStatsLine(
            nerdStats = nerdStats,
            modifier = modifier,
        )
        nerdStats?.isHiQuality == true -> LosslessLabel(
            text = "Hi-Quality",
            animated = false,
            modifier = modifier,
        )
        else -> {}
    }
}

@Composable
private fun NerdStatsLine(
    nerdStats: NerdStats.Snapshot,
    modifier: Modifier = Modifier,
) {
    Text(
        text = nerdStats.describe(),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.65f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun LosslessLabel(text: String, animated: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (animated) 0.7f else 0.45f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        if (animated) {
            ShimmerText(text = text)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
                ),
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShimmerText(text: String) {
    var widthPx by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "lossless-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lossless-shimmer-progress",
    )
    val baseColor = Color.White.copy(alpha = 0.55f)
    val brush = if (widthPx <= 0) {
        Brush.linearGradient(listOf(baseColor, baseColor))
    } else {
        val band = widthPx * 0.6f
        val center = -band + progress * (widthPx + 2 * band)
        Brush.linearGradient(
            colorStops = arrayOf(0f to baseColor, 0.5f to Color.White, 1f to baseColor),
            start = Offset(center - band, 0f),
            end = Offset(center + band, 0f),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            brush = brush,
            fontWeight = FontWeight.SemiBold,
            fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.onSizeChanged { widthPx = it.width },
    )
}

private fun NerdStats.Snapshot.describe(): String {
    val parts = buildList {
        codecLabel(mimeType)?.let(::add)
        bitDepth?.let { add("$it-bit") }
        if (!isLossless) bitrateKbps?.let { add("$it kbps") }
        sampleRateHz?.let { add("%.1f kHz".format(it / 1000f)) }
        channels?.let {
            add(
                when (it) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    else -> "$it ch"
                },
            )
        }
        if (downgraded) add("↓ from ${claimed?.summary}")
    }
    return parts.joinToString(" · ")
}

private fun codecLabel(mimeType: String?): String? = when {
    mimeType == null -> null
    mimeType.endsWith("opus") -> "Opus"
    mimeType.endsWith("mp4a-latm") -> "AAC"
    mimeType.endsWith("vorbis") -> "Vorbis"
    mimeType.endsWith("mpeg") -> "MP3"
    mimeType.endsWith("flac") -> "FLAC"
    mimeType.endsWith("alac") -> "ALAC"
    else -> mimeType.substringAfter('/').uppercase()
}

private fun TrackAnalysisState.label(): String = when (this) {
    TrackAnalysisState.ANALYSED -> "analysed"
    TrackAnalysisState.REFINING -> "analysed, refining…"
    TrackAnalysisState.ANALYSING -> "analysing…"
    TrackAnalysisState.WAITING -> "waiting"
    TrackAnalysisState.FAILED -> "failed"
}