package com.music.orb.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.lerp as lerpColor
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.music.orb.R
import com.music.orb.ui.components.thumbnailBorder
import com.music.orb.ui.components.bestForegroundForArtworkSurface
import com.music.orb.ui.components.rememberArtworkBottomEdgeField
import com.music.orb.ui.components.rememberArtworkRightEdgeField
import com.music.orb.ui.components.ExplicitTitle
import com.music.orb.ui.components.DownloadStatusGlyph
import com.music.orb.ui.components.LosslessWordmark
import com.music.orb.ui.components.HiQualityWordmark
import com.music.orb.ui.components.LosslessBadgeState
import com.music.orb.ui.components.rememberTrackLosslessBadgeState
import com.music.orb.ui.theme.rememberArtworkPalette
import com.music.orb.ui.icons.BitChordIcons
import com.music.orb.ui.flavor.OrbFlavorUi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.NerdStats
import com.music.orb.data.canvas.CanvasArtwork
import com.music.orb.data.canvas.CanvasRepository
import com.music.orb.data.lyrics.LyricLine
import com.music.orb.data.lyrics.LyricsSource
import com.music.orb.data.lyrics.LyricsTranslation
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AudioQuality
import com.music.orb.data.settings.SmartAnalysis
import com.music.orb.data.settings.TrackAnalysisState
import com.music.orb.data.settings.TransitionWindow
import com.music.orb.data.model.ArtistLink
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
// Now Playing artwork-to-controls transition.  The artwork is allowed to
// "breathe" below its sharp edge through a soft, heavily blurred echo before
// it disappears into the palette surface.  This makes the upper and lower
// halves read as one composition instead of artwork + a separate colour block.
private val HERO_MERGE_BAND = 360.dp
private val HERO_MERGE_BLUR = 44.dp
private val HERO_COLOR_FLOW_HEIGHT = 250.dp
private val HERO_COLOR_FLOW_OVERLAP = 76.dp
// Landscape needs a longer runway than portrait because the eye reads the
// artwork/surface split as a vertical seam. Keep the physical right-edge
// colours alive farther into the controls surface and overlap farther back
// into the cover before converging to one solid colour.
private val LANDSCAPE_HERO_COLOR_FLOW_WIDTH = 360.dp
private val LANDSCAPE_HERO_COLOR_FLOW_OVERLAP = 156.dp
private val LANDSCAPE_HERO_MERGE_BAND = 480.dp
private val LANDSCAPE_HERO_MERGE_OVERLAP = 240.dp
private val LANDSCAPE_HERO_MERGE_BLUR = 60.dp
// Portrait tablet keeps the same overlap values as the established vertical
// treatment; only landscape uses the expanded lateral runway above.
private val TABLET_HERO_COLOR_FLOW_OVERLAP = HERO_COLOR_FLOW_OVERLAP
private val TABLET_HERO_MERGE_OVERLAP = HERO_MERGE_BAND * 0.46f
private val PLAYER_GUTTER = 30.dp
private val PLAYER_MAX_WIDTH = 560.dp
private val TABLET_PLAYER_MAX_WIDTH = 640.dp
private val TABLET_ARTWORK_MAX = 760.dp
private val TABLET_ARTWORK_MIN = 300.dp
// Large-screen artwork is allowed to meet the window edge. The system bars
// already render edge-to-edge over the player, so reserving a second visual
// gutter made the cover look artificially cropped on tablets/foldables.
private val TABLET_ARTWORK_START = 0.dp
private val TABLET_CONTROLS_GAP = 32.dp
private val TABLET_CONTROLS_MIN_WIDTH = 320.dp
private val TABLET_VERTICAL_SAFE = 0.dp
private const val TABLET_TWO_PANE_MIN_WIDTH_DP = 800

/**
 * Applies display-only behavior to the window that actually hosts Now Playing.
 *
 * Material3 ModalBottomSheet is backed by a dialog window. Using that window
 * first keeps this reliable on One UI and other Android builds where changing
 * only the Activity window does not affect system bars above a modal dialog.
 */
@Composable
private fun NowPlayingWindowBehavior(
    hideStatusBar: Boolean,
    keepScreenOn: Boolean,
    darkStatusIcons: Boolean,
    darkNavigationIcons: Boolean,
) {
    val view = LocalView.current

    DisposableEffect(
        view,
        hideStatusBar,
        keepScreenOn,
        darkStatusIcons,
        darkNavigationIcons,
    ) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: view.context.findActivity()?.window

        val insetsController = window?.let {
            WindowInsetsControllerCompat(it, it.decorView)
        }

        // Now Playing is edge-to-edge over the source artwork. A white cover
        // needs dark status icons; dark artwork needs light ones. Navigation
        // icons follow the generated player surface at the bottom.
        insetsController?.isAppearanceLightStatusBars = darkStatusIcons
        insetsController?.isAppearanceLightNavigationBars = darkNavigationIcons

        if (hideStatusBar) {
            insetsController?.hide(WindowInsetsCompat.Type.statusBars())
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }

        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            // Both preferences are scoped to the full Now Playing window only.
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun fullBleedArtworkAvailable(): Boolean {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    return isTablet ||
        configuration.screenWidthDp.dp <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2
}

/**
 * Reveals B over A from the top edge down with a feathered boundary.
 * Artwork is the first visible element allowed to acknowledge B; title and
 * lyrics intentionally remain owned by A until later in the handoff.
 */
private fun Modifier.automixTopDownArtworkReveal(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            if (p <= 0f) return@drawWithContent

            // Mask the *whole* incoming artwork, not only the feather strip.
            // The feather itself travels continuously from just above the top
            // edge to just below the bottom edge. That geometry matters: at
            // p=1 the mask is already fully white over the whole cover, so there
            // is no last-frame jump when B finally becomes the only artwork.
            drawContent()
            val feather = (size.height * AUTOMIX_ARTWORK_FEATHER_FRACTION).coerceAtLeast(1f)
            val endY = p * (size.height + feather)
            val startY = endY - feather
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startY = startY,
                    endY = endY.coerceAtLeast(startY + 1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * The same travelling boundary continues through the generated colour surface
 * below the square artwork. This is what makes the Automix transition one
 * continuous top-to-bottom motion instead of "cover finishes, controls jump".
 */
private fun Modifier.automixTopDownSurfaceReveal(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            if (p <= 0f) return@drawWithContent
            drawContent()
            val feather = (size.height * AUTOMIX_SURFACE_FEATHER_FRACTION).coerceAtLeast(1f)
            val endY = p * (size.height + feather)
            val startY = endY - feather
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startY = startY,
                    endY = endY.coerceAtLeast(startY + 1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * Tablet-landscape equivalent of the Automix travelling boundary. The visual
 * handoff starts at the artwork side and travels toward the controls, matching
 * the horizontal artwork-to-surface composition instead of forcing a phone-
 * shaped top-to-bottom wipe onto a wide screen.
 */
private fun Modifier.automixLeftToRightArtworkReveal(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            if (p <= 0f) return@drawWithContent
            drawContent()
            val feather = (size.width * AUTOMIX_ARTWORK_FEATHER_FRACTION).coerceAtLeast(1f)
            val endX = p * (size.width + feather)
            val startX = endX - feather
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startX = startX,
                    endX = endX.coerceAtLeast(startX + 1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

private fun Modifier.automixLeftToRightSurfaceReveal(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            if (p <= 0f) return@drawWithContent
            drawContent()
            val feather = (size.width * AUTOMIX_SURFACE_FEATHER_FRACTION).coerceAtLeast(1f)
            val endX = p * (size.width + feather)
            val startX = endX - feather
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startX = startX,
                    endX = endX.coerceAtLeast(startX + 1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * After the square artwork is completely B, the lower player no longer uses
 * one long wipe. Two fronts move at once: the upper front travels through
 * title/artist and stops at ThinSlider, while the control surface rises from
 * the bottom to the same line. Nothing below ThinSlider receives a second
 * downward pass, so the two halves form one continuous meeting.
 */
private fun Modifier.automixTopToMeetingSurfaceReveal(
    progress: Float,
    meetingFraction: Float = AUTOMIX_SURFACE_MEETING_FRACTION,
): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return this.graphicsLayer { alpha = 0f }
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val meetingY = size.height * meetingFraction.coerceIn(0.2f, 0.8f)
            val feather = (size.height * AUTOMIX_SURFACE_FEATHER_FRACTION).coerceAtLeast(1f)
            val endY = p * (meetingY + feather)
            val startY = endY - feather
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startY = startY,
                    endY = endY.coerceAtLeast(startY + 1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

private fun Modifier.automixBottomToMeetingSurfaceReveal(
    progress: Float,
    meetingFraction: Float = AUTOMIX_SURFACE_MEETING_FRACTION,
): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return this.graphicsLayer { alpha = 0f }
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val meetingY = size.height * meetingFraction.coerceIn(0.2f, 0.8f)
            val feather = (size.height * AUTOMIX_SURFACE_FEATHER_FRACTION).coerceAtLeast(1f)
            val startY = size.height - p * ((size.height - meetingY) + feather)
            val endY = startY + feather
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.White),
                    startY = startY,
                    endY = endY.coerceAtLeast(startY + 1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

private fun Modifier.normalIncomingArtworkTransition(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    return graphicsLayer {
        alpha = p
        val scale = 1.018f - 0.018f * p
        scaleX = scale
        scaleY = scale
    }
}

private fun Modifier.normalOutgoingArtworkTransition(progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    return graphicsLayer {
        // B dissolves over A while A recedes by only a few percent. This keeps
        // a normal track change polished without borrowing Automix's wipe.
        alpha = 1f - 0.10f * p
        val scale = 1f - 0.012f * p
        scaleX = scale
        scaleY = scale
    }
}

private const val AUTOMIX_ARTWORK_FEATHER_FRACTION = 0.16f
private const val AUTOMIX_SURFACE_FEATHER_FRACTION = 0.075f
private const val AUTOMIX_SURFACE_MEETING_FRACTION = 0.35f
private const val NORMAL_IDENTITY_SWITCH_PROGRESS = 0.52f
private const val NORMAL_TRACK_ARTWORK_TRANSITION_MS = 720
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

private const val LYRICS_UNAVAILABLE_HOLD_MS = 5_000L
private const val LYRICS_UNAVAILABLE_FADE_MS = 900

@Composable
fun NowPlayingScreen(
    albumSequential: Boolean = false,
    song: Song,
    transitionFromSong: Song? = null,
    trackTransitionProgress: Float = 1f,
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
    onOpenArtist: (String?, String) -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    lyricsOpen: Boolean,
    onLyricsOpenChange: (Boolean) -> Unit,
    queueOpen: Boolean,
    onQueueOpenChange: (Boolean) -> Unit,
    onBackFromNowPlaying: () -> Unit,
    transportEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val translationScope = rememberCoroutineScope()
    val translationTarget = remember(context.resources.configuration) {
        context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
    }
    var translatedLyrics by remember(song.videoId, lyrics, translationTarget) {
        mutableStateOf<List<LyricLine>?>(null)
    }
    var showingTranslation by remember(song.videoId, lyrics, translationTarget) { mutableStateOf(false) }
    var translatingLyrics by remember(song.videoId, lyrics, translationTarget) { mutableStateOf(false) }
    var lyricsAlreadyInAppLanguage by remember(song.videoId, lyrics, translationTarget) {
        mutableStateOf<Boolean?>(null)
    }
    val displayedLyrics = if (showingTranslation) translatedLyrics ?: lyrics else lyrics

    LaunchedEffect(song.videoId, lyrics, translationTarget) {
        lyricsAlreadyInAppLanguage = null
        if (lyrics.isNullOrEmpty()) return@LaunchedEffect
        lyricsAlreadyInAppLanguage = LyricsTranslation.lyricsAlreadyInLanguage(
            context = context,
            trackId = song.videoId,
            lines = lyrics,
            targetLanguageTag = translationTarget,
        )
    }

    fun toggleTranslation() {
        if (translatingLyrics || lyrics.isNullOrEmpty()) return
        if (showingTranslation) {
            showingTranslation = false
            return
        }
        if (lyricsAlreadyInAppLanguage == true) return
        if (translatedLyrics != null) {
            showingTranslation = true
            return
        }
        translatingLyrics = true
        translationScope.launch {
            when (val result = LyricsTranslation.translate(
                context = context,
                trackId = song.videoId,
                lines = lyrics,
                targetLanguageTag = translationTarget,
            )) {
                is LyricsTranslation.Result.Translated -> {
                    translatedLyrics = result.lines
                    lyricsAlreadyInAppLanguage = false
                    showingTranslation = true
                }
                is LyricsTranslation.Result.SameLanguage -> {
                    lyricsAlreadyInAppLanguage = true
                    Toast.makeText(
                        context,
                        context.getString(R.string.lyrics_translation_same_language),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                LyricsTranslation.Result.Unavailable -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.lyrics_translation_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            translatingLyrics = false
        }
    }

    // Automix can keep A perceptually dominant while B is already playing.
    // That external transition owns the special top-down wipe. Ordinary track
    // changes get their own short dissolve/scale treatment instead of snapping
    // the cover and palette to the next song in one frame.
    val automixFromSong = transitionFromSong
        ?.takeIf { it.videoId != song.videoId && trackTransitionProgress < 0.999f }

    var lastVisualSong by remember { mutableStateOf(song) }
    var normalTransitionFromSong by remember { mutableStateOf<Song?>(null) }
    val normalTransitionProgress = remember { Animatable(1f) }

    LaunchedEffect(song.videoId, automixFromSong?.videoId) {
        val previous = lastVisualSong
        if (previous.videoId != song.videoId) {
            lastVisualSong = song
            if (automixFromSong == null) {
                normalTransitionFromSong = previous
                normalTransitionProgress.snapTo(0f)
                normalTransitionProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = NORMAL_TRACK_ARTWORK_TRANSITION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
                normalTransitionFromSong = null
            } else {
                normalTransitionFromSong = null
                normalTransitionProgress.snapTo(1f)
            }
        } else if (automixFromSong != null) {
            // If an Automix ownership handoff arrives while an ordinary
            // transition is still settling, the Automix visual must win.
            normalTransitionFromSong = null
            normalTransitionProgress.snapTo(1f)
        }
    }

    val normalFromSong = normalTransitionFromSong
        ?.takeIf { automixFromSong == null && it.videoId != song.videoId && normalTransitionProgress.value < 0.999f }
    val visualFromSong = automixFromSong ?: normalFromSong
    val isAutomixArtworkTransition = automixFromSong != null
    val visualTrackProgress = when {
        automixFromSong != null -> trackTransitionProgress.coerceIn(0f, 1f)
        normalFromSong != null -> normalTransitionProgress.value.coerceIn(0f, 1f)
        else -> 1f
    }
    val visualConfiguration = LocalConfiguration.current
    val density = LocalDensity.current
    var playerViewportSize by remember { mutableStateOf(IntSize.Zero) }
    val configurationIsLandscape =
        visualConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val measuredViewportMatchesOrientation =
        playerViewportSize.width > 0 && playerViewportSize.height > 0 &&
            ((playerViewportSize.width >= playerViewportSize.height) == configurationIsLandscape)
    // During the rotation frame onSizeChanged may still contain the old
    // orientation. Fall back to the fresh Configuration for that single frame
    // so the spring never launches toward a stale portrait/landscape size.
    val viewportWidth = if (measuredViewportMatchesOrientation) {
        with(density) { playerViewportSize.width.toDp() }
    } else {
        visualConfiguration.screenWidthDp.dp
    }
    val viewportHeight = if (measuredViewportMatchesOrientation) {
        with(density) { playerViewportSize.height.toDp() }
    } else {
        visualConfiguration.screenHeightDp.dp
    }

    val isTablet = visualConfiguration.smallestScreenWidthDp >= 600
    val isPhoneLandscape =
        !isTablet && visualConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Expanded lyrics/queue on a phone need a denser, reading-first layout.
    // This state exists only inside Now Playing; the rest of the phone UI stays portrait.
    val mobileLandscapeFocusRequested = isPhoneLandscape && (lyricsOpen || queueOpen)
    val mobileLandscapeFocusProgress by animateFloatAsState(
        targetValue = if (mobileLandscapeFocusRequested) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "mobile-landscape-focus-pane",
    )
    // A rotated phone intentionally reuses the exact same two-pane player
    // geometry as a landscape tablet. Phone landscape is only reachable while
    // Now Playing owns rotation, so this cannot affect the rest of the app.
    val isTabletLandscape =
        visualConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (isPhoneLandscape || visualConfiguration.screenWidthDp >= TABLET_TWO_PANE_MIN_WIDTH_DP)
    val isTabletPortrait = isTablet && !isTabletLandscape
    val usesWidePlayerGeometry = isTablet || isPhoneLandscape

    // On a landscape tablet the cover owns the complete short axis whenever
    // the remaining width can still host the controls. The old 54%-of-width
    // cap is what left the visible empty strip under the artwork.
    val landscapeArtworkTarget = if (isTabletLandscape) {
        val maxArtworkLeavingControls = (
            viewportWidth - TABLET_ARTWORK_START - TABLET_CONTROLS_GAP - 20.dp -
                TABLET_CONTROLS_MIN_WIDTH
            ).coerceAtLeast(if (isPhoneLandscape) 180.dp else TABLET_ARTWORK_MIN)
        val fullHeightArtwork = minOf(
            (viewportHeight - TABLET_VERTICAL_SAFE).coerceAtLeast(180.dp),
            maxArtworkLeavingControls,
        )
        // Keep the landscape artwork physically unchanged when Lyrics/Queue
        // enters Focus Pane. Space is recovered entirely from the controls
        // surface; the cover never shrinks, recenters or jumps on expansion.
        fullHeightArtwork.coerceAtLeast(if (isPhoneLandscape) 180.dp else TABLET_ARTWORK_MIN)
    } else {
        0.dp
    }
    val portraitArtworkTarget = if (isTablet) {
        viewportWidth.coerceAtLeast(THUMB_SIZE)
    } else {
        0.dp
    }
    val tabletArtworkTarget = if (isTabletLandscape) {
        landscapeArtworkTarget
    } else {
        portraitArtworkTarget
    }
    val tabletArtworkAnimated by animateDpAsState(
        targetValue = tabletArtworkTarget,
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "tablet-now-playing-artwork-geometry",
    )
    val tabletArtworkSize = if (isTabletLandscape) tabletArtworkAnimated else 0.dp
    val tabletPortraitArtworkSize = if (isTabletPortrait) tabletArtworkAnimated else 0.dp
    // Focus Pane must not move the cover either: keep the exact same top-left
    // artwork geometry in normal, Lyrics and Queue states.
    val tabletArtworkTopTarget = 0.dp
    val tabletArtworkTop by animateDpAsState(
        targetValue = tabletArtworkTopTarget,
        animationSpec = spring(
            dampingRatio = 0.88f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "mobile-focus-artwork-vertical-position",
    )

    val tabletControlsStartTarget = if (isTabletLandscape) {
        TABLET_ARTWORK_START + landscapeArtworkTarget + TABLET_CONTROLS_GAP
    } else {
        PLAYER_GUTTER
    }
    val tabletControlsStart by animateDpAsState(
        targetValue = tabletControlsStartTarget,
        animationSpec = spring(
            dampingRatio = 0.88f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "tablet-now-playing-controls-position",
    )

    // Keep the sheet alive across a configuration change, then let the new
    // arrangement settle instead of flashing from one orientation to the other.
    val orientationSettle = remember { Animatable(1f) }
    var previousTabletLandscape by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isTabletLandscape, usesWidePlayerGeometry) {
        val previous = previousTabletLandscape
        previousTabletLandscape = isTabletLandscape
        if (previous != null && previous != isTabletLandscape) {
            orientationSettle.snapTo(0f)
            orientationSettle.animateTo(
                targetValue = 1f,
                animationSpec = tween(420, easing = FastOutSlowInEasing),
            )
        } else {
            orientationSettle.snapTo(1f)
        }
    }
    val automixArtworkEndFraction = when {
        isTabletLandscape ->
            ((TABLET_ARTWORK_START.value + tabletArtworkSize.value) /
                viewportWidth.value.coerceAtLeast(1f))
                .coerceIn(0.32f, 0.68f)
        isTabletPortrait ->
            (tabletPortraitArtworkSize.value /
                viewportHeight.value.coerceAtLeast(1f))
                .coerceIn(0.30f, 0.72f)
        else ->
            (visualConfiguration.screenWidthDp.toFloat() /
                visualConfiguration.screenHeightDp.coerceAtLeast(1).toFloat())
                .coerceIn(0.30f, 0.72f)
    }
    val automixLowerSpan = (1f - automixArtworkEndFraction).coerceAtLeast(0.01f)
    // Keep every visible identity change behind the same physical frontier.
    // The artwork owns the opening phase. Only after it is fully B may the
    // title/artist change; the ThinSlider follows later; transport/control
    // surfaces occupy the remainder of the trip to the bottom edge.
    val automixIdentitySwitchProgress =
        (automixArtworkEndFraction + automixLowerSpan * 0.22f).coerceIn(0f, 1f)
    val automixSliderSwitchProgress =
        (automixArtworkEndFraction + automixLowerSpan * 0.48f).coerceIn(0f, 1f)
    // One global boundary travels from the top of the artwork to the bottom of
    // the whole Now Playing window. Convert that clock into artwork-local
    // progress so the cover is complete exactly when the frontier reaches its
    // lower edge; the same frontier then keeps moving through title/artist,
    // ThinSlider and finally the transport/control area.
    val automixArtworkProgress = if (isAutomixArtworkTransition) {
        (visualTrackProgress / automixArtworkEndFraction).coerceIn(0f, 1f)
    } else {
        visualTrackProgress
    }
    // The lower-edge colour field belongs to the control-surface phase, not
    // the artwork phase. Keep it completely on A until the square cover has
    // finished its own top-down reveal; only then let the same boundary begin
    // travelling through the continuation/control area.
    val automixEdgeFlowProgress = if (isAutomixArtworkTransition) {
        val lowerProgress = ((visualTrackProgress - automixArtworkEndFraction) /
            (1f - automixArtworkEndFraction)).coerceIn(0f, 1f)
        // The colour continuation immediately under the cover belongs to the
        // upper frontier. Finish it before that frontier reaches ThinSlider;
        // otherwise this short band keeps travelling downward after the main
        // surface has already split into top/bottom fronts and reads as a
        // second, duplicate descent below the slider.
        (lowerProgress / 0.58f).coerceIn(0f, 1f)
    } else {
        visualTrackProgress
    }

    val hideStatusBarNowPlaying by
    AppSettings.hideStatusBarNowPlaying.collectAsStateWithLifecycle()
    val keepScreenOnNowPlaying by
    AppSettings.keepScreenOnNowPlaying.collectAsStateWithLifecycle()

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()

    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    var canvas by remember(song.videoId) { mutableStateOf<CanvasArtwork?>(null) }
    var canvasRendered by remember(song.videoId) { mutableStateOf(false) }
    val canvasCover = remember(song.videoId) { mutableFloatStateOf(0f) }
    val stillCovered by remember(song.videoId) {
        derivedStateOf { canvasCover.floatValue > 0.999f }
    }

    // Album pages and Now Playing now consume the same palette fields and the
    // same ArtworkWash renderer. There is no second mesh colour system here.
    val sourceArtworkPalette = rememberArtworkPalette(song.thumbnailUrl)
    val outgoingArtworkPalette = rememberArtworkPalette(visualFromSong?.thumbnailUrl)
    val playerArtworkUrl = song.artworkAt(ART_PX)
    val outgoingArtworkUrl = visualFromSong?.artworkAt(ART_PX)
    val playerBottomEdgeField = if (isTabletLandscape) {
        rememberArtworkRightEdgeField(
            image = playerArtworkUrl,
            fallback = sourceArtworkPalette.playerBackground,
            // Landscape mirrors the approved portrait rule on the X axis:
            // complete the surface from the final 5% of colours touching the
            // artwork's right edge, rather than from the whole-cover palette.
            sampleStartFraction = 0.95f,
        )
    } else {
        rememberArtworkBottomEdgeField(
            image = playerArtworkUrl,
            fallback = sourceArtworkPalette.playerBackground,
        )
    }
    val outgoingBottomEdgeField = if (isTabletLandscape) {
        rememberArtworkRightEdgeField(
            image = outgoingArtworkUrl,
            fallback = outgoingArtworkPalette.playerBackground,
            sampleStartFraction = 0.95f,
        )
    } else {
        rememberArtworkBottomEdgeField(
            image = outgoingArtworkUrl,
            fallback = outgoingArtworkPalette.playerBackground,
        )
    }
    // The colour/controls surface starts changing the instant the travelling
    // Automix boundary reaches the bottom of the square artwork, then continues
    // smoothly until the boundary reaches the bottom of the window.
    val visualSurfaceProgress = when {
        visualFromSong == null -> 1f
        isAutomixArtworkTransition -> {
            // The lower surface does not get a second independent animation.
            // Convert the same global frontier to the area below the artwork;
            // title, slider and controls are therefore reached in strict Y
            // order instead of all acknowledging B at the handoff.
            ((visualTrackProgress - automixArtworkEndFraction) /
                (1f - automixArtworkEndFraction)).coerceIn(0f, 1f)
        }
        else -> visualTrackProgress
    }
    // Read these here as named phase boundaries as well as at the identity
    // switch above. Keeping them in one place makes the intended ordering
    // explicit and prevents a future "single threshold" regression.
    val automixSliderPhaseComplete =
        !isAutomixArtworkTransition || visualTrackProgress >= automixSliderSwitchProgress
    // During Automix the lower surface must not globally morph to B. A remains
    // the physical base colour and B is painted over it by the spatial wipe
    // below. A global lerp here was visible through haze/control surfaces and
    // made the whole lower half change colour as soon as the mix started.
    val playerBottomEdgeColor = when {
        visualFromSong == null -> playerBottomEdgeField.solidColor
        isAutomixArtworkTransition -> outgoingBottomEdgeField.solidColor
        else -> lerpColor(
            outgoingBottomEdgeField.solidColor,
            playerBottomEdgeField.solidColor,
            visualSurfaceProgress,
        )
    }
    val activeBottomEdgeField = when {
        visualFromSong == null -> playerBottomEdgeField
        isAutomixArtworkTransition && !automixSliderPhaseComplete -> outgoingBottomEdgeField
        isAutomixArtworkTransition -> playerBottomEdgeField
        visualSurfaceProgress < 0.5f -> outgoingBottomEdgeField
        else -> playerBottomEdgeField
    }
    val windowArtworkPalette = when {
        visualFromSong == null -> sourceArtworkPalette
        isAutomixArtworkTransition && automixArtworkProgress < 0.5f -> outgoingArtworkPalette
        isAutomixArtworkTransition -> sourceArtworkPalette
        visualSurfaceProgress < 0.5f -> outgoingArtworkPalette
        else -> sourceArtworkPalette
    }

    // Resolve foreground from the ACTUAL control-surface colour. Previously
    // this reused playerOnBackground from the whole-cover palette, which could
    // choose black icons/text even when the lower surface had resolved to black.
    val playerContentColor = bestForegroundForArtworkSurface(playerBottomEdgeColor)
    val outgoingSurfaceContentColor = bestForegroundForArtworkSurface(outgoingBottomEdgeField.solidColor)
    val incomingSurfaceContentColor = bestForegroundForArtworkSurface(playerBottomEdgeField.solidColor)
    fun automixAreaContentColor(start: Float, end: Float): Color {
        if (!isAutomixArtworkTransition || visualFromSong == null) return playerContentColor
        val raw = ((visualSurfaceProgress - start) / (end - start).coerceAtLeast(0.001f))
            .coerceIn(0f, 1f)
        val eased = raw * raw * (3f - 2f * raw)
        return lerpColor(outgoingSurfaceContentColor, incomingSurfaceContentColor, eased)
    }
    // Text/icons follow the travelling colour frontier in their own physical
    // bands instead of all flipping after the transition has completed.
    val titleContentColor = automixAreaContentColor(0.00f, 0.30f)
    val sliderContentColor = automixAreaContentColor(0.28f, 0.56f)
    val controlsContentColor = automixAreaContentColor(0.52f, 0.98f)

    val playerPalette = sourceArtworkPalette.copy(
        background = playerBottomEdgeColor,
        wash = playerBottomEdgeColor,
        accent = playerContentColor,
        onBackground = playerContentColor,
        onBackgroundVariant = playerContentColor.copy(
            alpha = if (playerContentColor == Color.White) 0.78f else 0.72f,
        ),
        divider = playerContentColor.copy(
            alpha = if (playerContentColor == Color.White) 0.12f else 0.10f,
        ),
    )

    NowPlayingWindowBehavior(
        hideStatusBar = hideStatusBarNowPlaying,
        // Expanded lyrics are a reading surface: never let Android dim/lock it
        // while the user is following the song, regardless of the global
        // "keep screen on in Now Playing" preference.
        keepScreenOn = keepScreenOnNowPlaying || lyricsOpen,
        // The first sampled artwork band sits behind the fixed status bar.
        darkStatusIcons = windowArtworkPalette.statusBarProfile.darkIconsAt(0f),
        // The navigation/gesture region sits on the generated player surface.
        darkNavigationIcons = controlsContentColor == Color.Black,
    )

    // Readability and visual coherence take priority in Now Playing.
    // One safe foreground drives the title, artist, slider, lyrics, quality
    // labels and the rest of the main player controls.
    LaunchedEffect(song.videoId, song.albumName, canvasEnabled) {
        if (!canvasEnabled) {
            canvas = null
            canvasRendered = false
            canvasCover.floatValue = 0f
            return@LaunchedEffect
        }
        canvas = CanvasRepository.cached(song) ?: canvas
        if (canvas == null && song.albumName == null) delay(ALBUM_SETTLE_MS)
        canvas = CanvasRepository.canvasFor(song) ?: canvas
    }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var lyricsDismissProgress by remember { mutableFloatStateOf(0f) }
    var queueDismissProgress by remember { mutableFloatStateOf(0f) }

    // Changing tracks must not collapse the internal Now Playing navigation.
    // Queue and expanded lyrics belong to the open player window, not to one
    // mediaId. Only transient drag progress is track-scoped.
    LaunchedEffect(song.videoId) {
        lyricsDismissProgress = 0f
        queueDismissProgress = 0f
    }

    // Back must be owned inside the ModalBottomSheet content itself.
    // This is deliberately one callback for every Now Playing state so the
    // dispatcher never changes owner halfway through the navigation chain:
    //
    // lyrics -> main Now Playing
    // queue  -> main Now Playing
    // main   -> page that was underneath (album/artist/etc.)
    BackHandler(enabled = true) {
        when {
            lyricsOpen -> {
                lyricsDismissProgress = 0f
                onLyricsOpenChange(false)
            }
            queueOpen -> {
                onQueueOpenChange(false)
            }
            else -> {
                onBackFromNowPlaying()
            }
        }
    }

    LaunchedEffect(lyricsOpen) {
        if (!lyricsOpen) {
            // Give the header morph time to start before clearing the transient
            // drag offset. The lyrics layer is already gone, so this reset is
            // invisible and cannot cause a one-frame bounce.
            delay(380)
            lyricsDismissProgress = 0f
        }
    }

    LaunchedEffect(queueOpen) {
        if (!queueOpen) {
            // Queue dismissal uses the exact same 360 ms geometry morph as
            // expanded lyrics. Keep the transient drag pinned at its final
            // value until that morph has settled so the compact header cannot
            // travel past its real resting position and snap upward.
            delay(380)
            queueDismissProgress = 0f
        }
    }

    // Lyrics and queue are two contents of the same expanded player panel.
    // Their switch must never collapse/re-expand the artwork/header; only the
    // panel body itself is allowed to change.  Keeping one OR-driven geometry
    // progress also removes the one-frame jump that happened when one mode
    // animated out before the other had animated in.
    val panelOpen = lyricsOpen || queueOpen
    val panelProgress by animateFloatAsState(
        targetValue = if (panelOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "panelProgress",
    )
    val panelMode = when {
        lyricsOpen -> 1
        queueOpen -> 2
        else -> 0
    }
    // Keep the Focus Pane composition alive until the closing morph is nearly
    // complete so normal controls never pop back in halfway through the exit.
    val mobileLandscapeFocusLayout =
        isPhoneLandscape && (panelOpen || panelProgress > 0.03f)

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

    // The upper music identity/artwork geometry belongs to the expanded panel
    // as a whole, not to Lyrics or Queue individually.  Switching between the
    // two therefore keeps `p` pinned at 1 and leaves the upper half untouched.
    val p = panelProgress

    // Keep the title/header on the approved lyrics animation, but let the artwork
    // itself follow a downward lyrics-dismiss gesture immediately. While the
    // gesture settles to 1f, the artwork grows continuously from the compact
    // top-left position instead of waiting for lyricsOpen=false and appearing to
    // recenter before expanding.
    //
    // lyricsDismissProgress is intentionally kept for a short moment after the
    // lyrics layer closes (see LaunchedEffect above), so artP remains continuous
    // while panelProgress catches up to the normal-player state.
    val lyricsDismiss = lyricsDismissProgress.coerceIn(0f, 1f)
    val queueDismiss = queueDismissProgress.coerceIn(0f, 1f)
    val panelDismiss = maxOf(lyricsDismiss, queueDismiss)
    // Lyrics and queue share one dismissal geometry. The compact header can
    // approach the normal-player position, but never pass it: progress=1 is
    // exactly the final resting geometry, not an overshoot that later snaps up.
    val dismissMorph = panelDismiss * panelDismiss
    val artP = if (panelDismiss > 0f) {
        minOf(p, (1f - dismissMorph).coerceAtLeast(0f))
    } else {
        p
    }

    val fullBleedArt by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val heroMode = fullBleedArt && fullBleedArtworkAvailable()
    // A tablet in landscape always keeps a dedicated artwork pane. The
    // full-bleed preference still controls Canvas/full-bleed behaviour, but it
    // no longer decides whether a wide layout gets artwork at all.
    val heroVisualEnabled = heroMode || isTabletLandscape
    var artLoaded by remember(song.videoId) { mutableStateOf(false) }
    var heroSettled by remember { mutableStateOf(false) }
    LaunchedEffect(artLoaded) { if (artLoaded) heroSettled = true }
    // Animated artwork is controlled only by the dedicated setting.
    // Full-bleed decides the geometry, not whether a Canvas may play. Wide
    // tablet/phone landscape panes therefore keep motion artwork even when
    // full-bleed itself is disabled.
    val heroClip = canvas?.takeIf { canvasEnabled && (heroMode || isTabletLandscape) }
    val heroReady by animateFloatAsState(
        targetValue = if (
            heroVisualEnabled && (canvasRendered || artLoaded || heroSettled)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "heroReady",
    )

    // On a wide tablet the artwork is a permanent left pane; opening lyrics or
    // queue changes only the right pane. Phones/portrait keep the approved
    // hero-to-thumbnail morph unchanged.
    val heroT = if (isTabletLandscape) {
        heroReady
    } else {
        heroReady * if (lyricsOpen) {
            val remaining = (1f - artP).coerceIn(0f, 1f)
            remaining * remaining
        } else {
            val compact = artP.coerceIn(0f, 1f)
            1f - compact * compact
        }
    }
    // The source artwork remains square in every layout. Portrait extends its
    // lower edge downward; landscape tablets extend the right edge sideways.
    val heroArtworkHeight = when {
        isTabletLandscape -> tabletArtworkSize
        isTabletPortrait -> tabletPortraitArtworkSize
        else -> visualConfiguration.screenWidthDp.dp
    }

    val heroHaze = remember { HazeState() }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size != playerViewportSize) playerViewportSize = size
            },
    ) {
        // Exact same background renderer used by album DetailScreen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(heroHaze),
        ) {
            // The Automix wipe is spatial, not one global colour lerp: A is
            // the base and B's lower-edge surface is revealed by the exact same
            // top-to-bottom frontier that crosses the artwork. Once the frontier
            // leaves the cover it simply keeps descending through the controls.
            val baseSurfaceColor = if (
                isAutomixArtworkTransition && visualFromSong != null
            ) {
                outgoingBottomEdgeField.solidColor
            } else {
                playerBottomEdgeColor
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(baseSurfaceColor),
            )
            if (isAutomixArtworkTransition && visualFromSong != null) {
                // The same physical frontier continues beyond the artwork. On
                // phones/portrait it travels downward; on a landscape tablet it
                // leaves the right edge and travels horizontally into controls.
                val incomingSurfaceModifier = if (isTabletLandscape) {
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = TABLET_ARTWORK_START + tabletArtworkSize -
                                LANDSCAPE_HERO_COLOR_FLOW_OVERLAP,
                        )
                        .automixLeftToRightSurfaceReveal(visualSurfaceProgress)
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(top = heroArtworkHeight)
                        .automixTopDownSurfaceReveal(visualSurfaceProgress)
                }
                Box(
                    modifier = incomingSurfaceModifier
                        .background(playerBottomEdgeField.solidColor),
                )
            }

            if (heroArtworkHeight > 0.dp) {
                val heroArtworkModifier = when {
                    isTabletLandscape ->
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(x = TABLET_ARTWORK_START, y = tabletArtworkTop)
                            .size(tabletArtworkSize)
                    isTabletPortrait ->
                        Modifier
                            .align(Alignment.TopCenter)
                            .size(tabletPortraitArtworkSize)
                    else ->
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(heroArtworkHeight)
                }

                if (heroVisualEnabled && !(stillCovered && heroClip != null) && heroT > 0.001f) {
                    visualFromSong?.let { from ->
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(from.artworkAt(ART_PX))
                                .size(ART_PX)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.TopCenter,
                            modifier = heroArtworkModifier
                                .then(
                                    if (!isAutomixArtworkTransition) {
                                        Modifier.normalOutgoingArtworkTransition(visualTrackProgress)
                                    } else {
                                        Modifier
                                    },
                                )
                                .graphicsLayer { alpha = heroT },
                        )
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(song.artworkAt(ART_PX))
                            .size(ART_PX)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopCenter,
                        onState = { artLoaded = it is AsyncImagePainter.State.Success },
                        modifier = heroArtworkModifier
                            .then(
                                when {
                                    visualFromSong == null -> Modifier
                                    isAutomixArtworkTransition ->
                                        if (isTabletLandscape) {
                                            Modifier.automixLeftToRightArtworkReveal(automixArtworkProgress)
                                        } else {
                                            Modifier.automixTopDownArtworkReveal(automixArtworkProgress)
                                        }
                                    else ->
                                        Modifier.normalIncomingArtworkTransition(visualTrackProgress)
                                },
                            )
                            .graphicsLayer {
                                alpha = heroT *
                                    (1f - if (heroClip != null && visualFromSong == null) canvasCover.floatValue else 0f)
                            },
                    )
                }

                if (heroClip != null && visualFromSong == null) {
                    heroClip?.let { clip ->
                        CanvasArtworkPlayer(
                            canvas = clip,
                            isPlaying = isPlaying,
                            onRenderedChanged = { canvasRendered = it },
                            onCoverChanged = { canvasCover.floatValue = it },
                            modifier = heroArtworkModifier
                                .graphicsLayer { alpha = heroT },
                        )
                    }
                }

                // Continue only the colours that physically touch the lower
                // edge of the cover. During Automix the edge field itself also
                // changes gradually as the travelling boundary passes through
                // this merge band; it no longer flips from A's field to B's in
                // one frame after the square artwork has already finished.
                fun edgeFlowModifier(extra: Modifier = Modifier): Modifier {
                    val geometry = when {
                        isTabletLandscape ->
                            Modifier
                                .align(Alignment.TopStart)
                                .width(LANDSCAPE_HERO_COLOR_FLOW_WIDTH)
                                .height(tabletArtworkSize)
                                .offset(
                                    x = TABLET_ARTWORK_START + tabletArtworkSize -
                                        LANDSCAPE_HERO_COLOR_FLOW_OVERLAP,
                                    y = tabletArtworkTop,
                                )
                        isTabletPortrait ->
                            Modifier
                                .align(Alignment.TopCenter)
                                .width(tabletPortraitArtworkSize)
                                .height(HERO_COLOR_FLOW_HEIGHT)
                                .offset(y = heroArtworkHeight - TABLET_HERO_COLOR_FLOW_OVERLAP)
                        else ->
                            Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .height(HERO_COLOR_FLOW_HEIGHT)
                                .offset(y = heroArtworkHeight - HERO_COLOR_FLOW_OVERLAP)
                    }
                    return geometry
                        .graphicsLayer(
                            alpha = heroT,
                            compositingStrategy = CompositingStrategy.Offscreen,
                        )
                        .then(extra)
                        .drawWithContent {
                            drawContent()
                            val maskBrush = if (isTabletLandscape) {
                                Brush.horizontalGradient(
                                    0.00f to Color.Transparent,
                                    0.10f to Color.White.copy(alpha = 0.12f),
                                    0.24f to Color.White.copy(alpha = 0.52f),
                                    0.42f to Color.White.copy(alpha = 0.92f),
                                    0.56f to Color.White,
                                    1.00f to Color.White,
                                )
                            } else {
                                Brush.verticalGradient(
                                    0.00f to Color.Transparent,
                                    0.16f to Color.White.copy(alpha = 0.30f),
                                    0.30f to Color.White.copy(alpha = 0.82f),
                                    0.42f to Color.White,
                                    1.00f to Color.White,
                                )
                            }
                            drawRect(brush = maskBrush, blendMode = BlendMode.DstIn)
                        }
                }

                if (isAutomixArtworkTransition && visualFromSong != null) {
                    // A's lower-edge continuation stays underneath. B's field is
                    // revealed spatially from top to bottom only after the cover
                    // has completed, matching the control-surface wipe instead
                    // of cross-fading this whole band at once.
                    outgoingBottomEdgeField.edgeFlow?.let { edgeFlow ->
                        if (heroT > 0.001f) {
                            Image(
                                bitmap = edgeFlow,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = edgeFlowModifier(),
                            )
                        }
                    }
                    playerBottomEdgeField.edgeFlow?.let { edgeFlow ->
                        if (heroT > 0.001f && automixEdgeFlowProgress > 0.001f) {
                            Image(
                                bitmap = edgeFlow,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = edgeFlowModifier(
                                    if (isTabletLandscape) {
                                        Modifier.automixLeftToRightSurfaceReveal(automixEdgeFlowProgress)
                                    } else {
                                        Modifier.automixTopDownSurfaceReveal(automixEdgeFlowProgress)
                                    },
                                ),
                            )
                        }
                    }
                } else {
                    activeBottomEdgeField.edgeFlow?.let { edgeFlow ->
                        if (heroT > 0.001f) {
                            Image(
                                bitmap = edgeFlow,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = edgeFlowModifier(),
                            )
                        }
                    }
                }
            }
        }

        // Blur only the physical artwork/surface boundary. Portrait keeps the
        // lower-edge band; a landscape tablet rotates the exact same treatment
        // onto the right edge of the square cover.
        if (heroVisualEnabled && heroArtworkHeight > 0.dp && heroT > 0.01f && !reduceDynamicBlur) {
            val mergeGeometry = when {
                isTabletLandscape ->
                    Modifier
                        .width(LANDSCAPE_HERO_MERGE_BAND)
                        .height(tabletArtworkSize)
                        .offset(
                            x = TABLET_ARTWORK_START + tabletArtworkSize - LANDSCAPE_HERO_MERGE_OVERLAP,
                            y = tabletArtworkTop,
                        )
                isTabletPortrait ->
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(tabletPortraitArtworkSize)
                        .height(HERO_MERGE_BAND)
                        .offset(y = heroArtworkHeight - TABLET_HERO_MERGE_OVERLAP)
                else ->
                    Modifier
                        .fillMaxWidth()
                        .height(HERO_MERGE_BAND)
                        .offset(y = heroArtworkHeight - HERO_MERGE_BAND * 0.46f)
            }
            Box(
                modifier = mergeGeometry
                    .hazeEffect(heroHaze) {
                        canDrawArea = { true }
                        blurRadius = if (isTabletLandscape) LANDSCAPE_HERO_MERGE_BLUR else HERO_MERGE_BLUR
                        noiseFactor = 0f
                        tints = listOf(HazeTint(Color.Transparent))
                        backgroundColor = playerBottomEdgeColor
                        mask = if (isTabletLandscape) {
                            Brush.horizontalGradient(
                                0.00f to Color.Transparent,
                                0.08f to Color.Black.copy(alpha = 0.10f),
                                0.22f to Color.Black.copy(alpha = 0.48f),
                                0.38f to Color.Black.copy(alpha = 0.90f),
                                0.62f to Color.Black,
                                0.82f to Color.Black.copy(alpha = 0.76f),
                                0.94f to Color.Black.copy(alpha = 0.24f),
                                1.00f to Color.Transparent,
                            )
                        } else {
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.10f to Color.Black.copy(alpha = 0.18f),
                                0.26f to Color.Black.copy(alpha = 0.72f),
                                0.42f to Color.Black,
                                0.72f to Color.Black,
                                0.90f to Color.Black.copy(alpha = 0.46f),
                                1.00f to Color.Transparent,
                            )
                        }
                    },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer {
                    val settle = orientationSettle.value.coerceIn(0f, 1f)
                    alpha = 0.78f + 0.22f * settle
                    val settleScale = 0.988f + 0.012f * settle
                    scaleX = settleScale
                    scaleY = settleScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        start = tabletControlsStart,
                        end = if (isTabletLandscape) 28.dp else PLAYER_GUTTER,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Keep the wide-player vertical geometry stable whether the
                // lyrics/queue panel is open or closed. The expanded-panel
                // position is the approved resting position for transport and
                // persistent controls; only the panel body itself animates.
                verticalArrangement = Arrangement.Top,
            ) {
                // Shared by both the upper identity area (inside BoxWithConstraints)
                // and the lower playback controls. Keep it in this parent scope so
                // phone-landscape spacing remains consistent across the whole panel.
                val phoneLandscapeMainShift = if (isPhoneLandscape) {
                    lerp(15.dp, 0.dp, mobileLandscapeFocusProgress)
                } else {
                    0.dp
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = if (usesWidePlayerGeometry) TABLET_PLAYER_MAX_WIDTH else PLAYER_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(
                            top = if (isTabletLandscape) 0.dp else ART_BOX_TOP_PAD,
                            bottom = if (isTabletLandscape) 4.dp else 18.dp,
                        ),
                ) {
                    // Keep the identity row's physical height fixed while Lyrics/Queue
                    // morph in. On narrow phone landscape we reclaim space by shrinking
                    // typography, not by moving/re-centering the title and artist.
                    val focusHeaderHeight = HEADER_HEIGHT
                    val fullArt = if (isTabletLandscape) {
                        0.dp
                    } else {
                        minOf(maxWidth, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT)
                            .coerceAtLeast(THUMB_SIZE)
                    }
                    val groupTop = if (isTabletLandscape) {
                        0.dp
                    } else {
                        ((maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT) / 2)
                            .coerceAtLeast(0.dp)
                    }
                    val artSize = if (isTabletLandscape) 0.dp else lerp(fullArt, THUMB_SIZE, artP)
                    val artTop = if (isTabletLandscape) 0.dp else lerp(groupTop, 0.dp, artP)
                    val artStart = if (isTabletLandscape) 0.dp else lerp((maxWidth - fullArt) / 2, 0.dp, artP)
                    // On real tablets only, opening Lyrics/Queue drops the
                    // identity row (title + like + action button) by 10dp.
                    val tabletExpandedIdentityShift = if (isTablet && isTabletLandscape) {
                        lerp(0.dp, 10.dp, panelProgress)
                    } else {
                        0.dp
                    }
                    val titleTop = if (isTabletLandscape) {
                        // Landscape identity: phones use a compact focus header at the top
                        // when Lyrics/Queue are expanded; tablets keep the requested +10dp
                        // expanded-state shift.
                        val closedTitleTop = if (syncedLyricsEnabled) {
                            (maxHeight - focusHeaderHeight - 38.dp).coerceAtLeast(0.dp)
                        } else {
                            0.dp
                        }
                        if (isPhoneLandscape) {
                            // In phone-landscape focus mode the compact identity belongs to
                            // the top of the right pane, above Lyrics/Queue. Do not let the
                            // expanded body render behind it. To avoid the old "title flying"
                            // animation, the row crossfades through its midpoint while its
                            // anchor changes from the normal resting position to the focus top.
                            if (mobileLandscapeFocusProgress >= 0.5f) {
                                4.dp
                            } else {
                                closedTitleTop + 15.dp
                            }
                        } else {
                            closedTitleTop + tabletExpandedIdentityShift
                        }
                    } else {
                        lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, artP)
                    }
                    val titleStart = if (isTabletLandscape) 0.dp else lerp(0.dp, THUMB_SIZE + 12.dp, artP)

                    Box(
                        modifier = Modifier
                            .offset(x = artStart, y = artTop)
                            .size(artSize)
                            .lyricsDismissSwipe(
                                enabled = lyricsOpen || queueOpen,
                                onDragProgress = { progress ->
                                    if (lyricsOpen) {
                                        lyricsDismissProgress = progress
                                    } else if (queueOpen) {
                                        queueDismissProgress = progress
                                    }
                                },
                                onDismissLyrics = {
                                    when {
                                        lyricsOpen -> onLyricsOpenChange(false)
                                        queueOpen -> onQueueOpenChange(false)
                                    }
                                },
                            )
                            .graphicsLayer {
                                val idle = artScale + (1f - artScale) * artP
                                scaleX = idle
                                scaleY = idle
                            }
                            .then(
                                if (queueOpen || lyricsOpen) {
                                    Modifier.clickable {
                                        onQueueOpenChange(false)
                                        onLyricsOpenChange(false)
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
                                    if (artLoaded) lerp(14.dp, 6.dp, artP) else 0.dp,
                                    RoundedCornerShape(lerp(10.dp, 7.dp, artP)),
                                )
                                .clip(RoundedCornerShape(lerp(10.dp, 7.dp, artP)))
                                .background(Color.Black.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!artLoaded) {
                                Icon(
                                    imageVector = BitChordIcons.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(lerp(40.dp, 20.dp, artP)),
                                )
                            }
                            visualFromSong?.let { from ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(from.artworkAt(ART_PX))
                                        .size(ART_PX)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (!isAutomixArtworkTransition) {
                                                Modifier.normalOutgoingArtworkTransition(visualTrackProgress)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                )
                            }
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(song.artworkAt(ART_PX))
                                    .size(ART_PX)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                onState = { artLoaded = it is AsyncImagePainter.State.Success },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        when {
                                            visualFromSong == null -> Modifier
                                            isAutomixArtworkTransition ->
                                                Modifier.automixTopDownArtworkReveal(automixArtworkProgress)
                                            else ->
                                                Modifier.normalIncomingArtworkTransition(visualTrackProgress)
                                        },
                                    ),
                            )

                            if (!heroMode && visualFromSong == null) {
                                canvas?.takeIf { canvasEnabled && p < 0.5f }?.let { clip ->
                                    CanvasArtworkPlayer(
                                        canvas = clip,
                                        isPlaying = isPlaying,
                                        onRenderedChanged = { canvasRendered = it },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }

                    val identityAnchorCrossfadeAlpha = if (isPhoneLandscape) {
                        // 1 -> 0 -> 1 around the anchor swap. The title never travels across
                        // the lyrics/queue; it briefly fades while switching to the fixed
                        // focus header at the top, then returns at the smaller type size.
                        kotlin.math.abs(1f - 2f * mobileLandscapeFocusProgress.coerceIn(0f, 1f))
                    } else {
                        1f
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = titleTop)
                            .padding(start = titleStart)
                            .height(focusHeaderHeight)
                            .graphicsLayer { alpha = identityAnchorCrossfadeAlpha }
                            .lyricsDismissSwipe(
                                enabled = lyricsOpen || queueOpen,
                                onDragProgress = { progress ->
                                    if (lyricsOpen) {
                                        lyricsDismissProgress = progress
                                    } else if (queueOpen) {
                                        queueDismissProgress = progress
                                    }
                                },
                                onDismissLyrics = {
                                    when {
                                        lyricsOpen -> onLyricsOpenChange(false)
                                        queueOpen -> onQueueOpenChange(false)
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val identitySwitchAt = if (isAutomixArtworkTransition) {
                            automixIdentitySwitchProgress
                        } else {
                            NORMAL_IDENTITY_SWITCH_PROGRESS
                        }
                        val identitySong = if (visualFromSong != null && visualTrackProgress < identitySwitchAt) {
                            visualFromSong
                        } else {
                            song
                        }
                        Crossfade(
                            targetState = identitySong,
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                            label = "nowPlayingTrackIdentity",
                            modifier = Modifier.weight(1f),
                        ) { shownSong ->
                            Column {
                                val titleSize = if (isTabletLandscape) {
                                    if (isPhoneLandscape) {
                                        lerp(24.sp, 18.sp, mobileLandscapeFocusProgress)
                                    } else {
                                        24.sp
                                    }
                                } else {
                                    lerp(20.sp, 16.sp, artP)
                                }
                                val artistSize = if (isTabletLandscape) {
                                    if (isPhoneLandscape) {
                                        lerp(17.sp, 13.sp, mobileLandscapeFocusProgress)
                                    } else {
                                        17.sp
                                    }
                                } else {
                                    lerp(16.sp, 14.sp, artP)
                                }
                                ExplicitTitle(
                                    text = shownSong.title,
                                    isExplicit = shownSong.isExplicit,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = titleSize,
                                    ),
                                    color = titleContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    badgeSpacing = 5.dp,
                                    badgeSize = 13.dp,
                                    badgeFontSize = 10.sp,
                                    fadeOverflow = true,
                                    keepBadgeVisible = true,
                                    modifier = Modifier.opensPage(shownSong.albumId, onOpenAlbum),
                                )
                                ArtistCreditsText(
                                    text = shownSong.artist,
                                    artists = shownSong.artistLinks,
                                    fallbackArtistId = shownSong.artistId,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.W500,
                                        fontSize = artistSize,
                                    ),
                                    color = titleContentColor,
                                    onOpenArtist = onOpenArtist,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Row(
                            modifier = Modifier
                                .align(Alignment.Top)
                                .offset(
                                    x = if (OrbFlavorUi.expressive) 2.2.dp else 0.dp,
                                    y = if (OrbFlavorUi.expressive) 0.1.dp else 0.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Crossfade(
                                targetState = mobileLandscapeFocusLayout,
                                animationSpec = tween(
                                    durationMillis = 360,
                                    easing = FastOutSlowInEasing,
                                ),
                                label = "mobileFocusHeaderActions",
                            ) { focused ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (focused) {
                                        if (lyricsOpen) {
                                            val focusTranslationEnabled =
                                                !lyrics.isNullOrEmpty() && !translatingLyrics &&
                                                    (showingTranslation || lyricsAlreadyInAppLanguage != true)
                                            CircleGlyph(
                                                icon = Icons.Rounded.Translate,
                                                contentDescription = stringResource(
                                                    if (showingTranslation) {
                                                        R.string.lyrics_translation_show_original
                                                    } else {
                                                        R.string.lyrics_translation_translate
                                                    },
                                                ),
                                                onClick = {
                                                    if (focusTranslationEnabled) toggleTranslation()
                                                },
                                                active = false,
                                                tintColor = titleContentColor.copy(
                                                    alpha = if (focusTranslationEnabled) 1f else 0.42f,
                                                ),
                                                touchSize = 34.dp,
                                                iconSize = 19.dp,
                                            )
                                            Spacer(Modifier.width(2.dp))
                                        }
                                        CircleGlyph(
                                            icon = Icons.Rounded.Close,
                                            contentDescription = "Close expanded panel",
                                            onClick = {
                                                onLyricsOpenChange(false)
                                                onQueueOpenChange(false)
                                            },
                                            tintColor = titleContentColor,
                                            touchSize = 36.dp,
                                            iconSize = 21.dp,
                                        )
                                    } else {
                                        if (signedIn && song.localUri == null) {
                                            val liked = likeStatus == LikeStatus.LIKE
                                            CircleGlyph(
                                                icon = if (liked) BitChordIcons.HeartFilled else BitChordIcons.Heart,
                                                contentDescription = if (liked) "Remove from Liked Music" else "Like",
                                                onClick = onToggleLike,
                                                active = liked,
                                                tintColor = titleContentColor,
                                                touchSize = if (OrbFlavorUi.expressive) 38.dp else 36.dp,
                                                iconSize = if (OrbFlavorUi.expressive) 25.dp else 20.dp,
                                            )
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        CircleGlyph(
                                            icon = if (OrbFlavorUi.expressive) Icons.Rounded.MoreVert else Icons.Rounded.MoreHoriz,
                                            contentDescription = "More",
                                            onClick = onOpenMenu,
                                            tintColor = titleContentColor,
                                            touchSize = if (OrbFlavorUi.expressive) 32.dp else 36.dp,
                                            iconSize = if (OrbFlavorUi.expressive) 24.dp else 20.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Lyrics and Queue share one body slot. On phone landscape the body
                    // always begins below the compact focus header, so it can never render
                    // behind the title/artist.
                    val panelReveal = ((panelProgress - 0.20f) / 0.80f).coerceIn(0f, 1f)
                    Crossfade(
                        targetState = panelMode,
                        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
                        label = "nowPlayingPanelContent",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = focusHeaderHeight + when {
                                    // Phone-landscape focus identity is fixed at y=4dp; keep
                                    // Lyrics/Queue entirely below that header so title/artist
                                    // can never overlap the expanded content.
                                    mobileLandscapeFocusLayout -> 8.dp
                                    isTabletLandscape -> 30.dp
                                    else -> 10.dp
                                },
                                bottom = if (mobileLandscapeFocusLayout) 4.dp else 0.dp,
                            ),
                    ) { mode ->
                        when (mode) {
                            1 -> {
                                LyricsPanel(
                                    lines = displayedLyrics.orEmpty(),
                                    positionMs = positionMs,
                                    isPlaying = isPlaying,
                                    onSeekToLine = onSeek,
                                    contentColor = playerContentColor,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val dismiss = lyricsDismissProgress.coerceIn(0f, 1f)
                                            val dismissFade = (1f - dismiss / 0.48f).coerceIn(0f, 1f)
                                            alpha = panelReveal * dismissFade
                                            translationY =
                                                (1f - panelReveal) * 20.dp.toPx() +
                                                    dismiss * 156.dp.toPx()
                                            val scale = 1f - dismiss * 0.006f
                                            scaleX = scale
                                            scaleY = scale
                                            transformOrigin = TransformOrigin(0.5f, 0f)
                                        },
                                )
                            }
                            2 -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val dismiss = queueDismissProgress.coerceIn(0f, 1f)
                                            val dismissFade = (1f - dismiss / 0.48f).coerceIn(0f, 1f)
                                            alpha = panelReveal * dismissFade
                                            translationY =
                                                (1f - panelReveal) * 20.dp.toPx() +
                                                    dismiss * 156.dp.toPx()
                                            val scale = 1f - dismiss * 0.006f
                                            scaleX = scale
                                            scaleY = scale
                                            transformOrigin = TransformOrigin(0.5f, 0f)
                                        },
                                ) {
                                    InlineQueue(
                                        queue = queue,
                                        currentIndex = queueIndex,
                                        autoplayEnabled = autoplayEnabled,
                                        shuffleEnabled = shuffleEnabled,
                                        onJumpTo = onJumpTo,
                                        onRemove = onRemoveFromQueue,
                                        onMove = onMoveInQueue,
                                        onClear = onClearQueue,
                                        contentColor = playerContentColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = if (usesWidePlayerGeometry) TABLET_PLAYER_MAX_WIDTH else PLAYER_MAX_WIDTH)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (mobileLandscapeFocusLayout) {
                        // Phone landscape Focus Pane: reserve nearly all vertical
                        // space for Lyrics/Queue and keep only progress + essential
                        // transport below it. Secondary controls, quality badge and
                        // compact lyric strip intentionally disappear while focused.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val focus = mobileLandscapeFocusProgress.coerceIn(0f, 1f)
                                    alpha = focus
                                    translationY = (1f - focus) * 16.dp.toPx()
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
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
                                mixing = false,
                                transitionWindow = null,
                                contentColor = sliderContentColor,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatTime((shown * durationMs).toLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sliderContentColor.copy(alpha = 0.54f),
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FocusTransportGlyph(
                                        icon = Icons.Rounded.SkipPrevious,
                                        contentDescription = "Previous",
                                        enabled = transportEnabled &&
                                            (hasPrevious || positionMs > BACK_RESTARTS_AFTER_MS),
                                        tintColor = controlsContentColor,
                                        onClick = onPrevious,
                                    )
                                    if (isLoading) {
                                        Box(
                                            modifier = Modifier.size(42.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                color = controlsContentColor,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    } else {
                                        FocusTransportGlyph(
                                            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            enabled = transportEnabled,
                                            tintColor = controlsContentColor,
                                            prominent = true,
                                            onClick = onPlayPause,
                                        )
                                    }
                                    FocusTransportGlyph(
                                        icon = Icons.Rounded.SkipNext,
                                        contentDescription = "Next",
                                        enabled = transportEnabled && hasNext,
                                        tintColor = controlsContentColor,
                                        onClick = onNext,
                                    )
                                }
                                Text(
                                    text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sliderContentColor.copy(alpha = 0.54f),
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    } else {
                    if (syncedLyricsEnabled) {
                        // Reserve the compact lyric strip even while expanded
                        // lyrics are open. During a downward drag it fades back
                        // in immediately instead of being inserted only after
                        // lyricsOpen flips to false; that late insertion was the
                        // last source of the title/artist snap at the end.
                        val compactLyricsReveal = if (!lyricsOpen) {
                            1f
                        } else {
                            ((lyricsDismiss - 0.05f) / 0.28f).coerceIn(0f, 1f)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(
                                    y = if (isTabletLandscape) {
                                        (-8).dp + phoneLandscapeMainShift
                                    } else {
                                        (-18).dp
                                    },
                                )
                                .graphicsLayer {
                                    alpha = compactLyricsReveal
                                    translationY = (1f - compactLyricsReveal) * 8.dp.toPx()
                                },
                        ) {
                            if (!displayedLyrics.isNullOrEmpty()) {
                                CurrentLyricLine(
                                    lines = displayedLyrics,
                                    trackKey = song.videoId,
                                    positionMs = positionMs,
                                    isPlaying = isPlaying,
                                    durationMs = durationMs,
                                    onClick = {
                                        if (!lyricsOpen) onLyricsOpenChange(true)
                                    },
                                    contentColor = sliderContentColor,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (lyricsUnavailable) {
                                LyricsUnavailableLine(
                                    trackKey = song.videoId,
                                    contentColor = sliderContentColor,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LyricsLoadingLine(
                                    trackKey = song.videoId,
                                    contentColor = sliderContentColor,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.offset(
                            y = if (isTabletLandscape) {
                                (-13).dp + phoneLandscapeMainShift
                            } else {
                                (-23).dp
                            },
                        ),
                    ) {
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
                            mixing = false,
                            transitionWindow = null,
                            contentColor = sliderContentColor,
                        )
                    }

                    val qualitySearchHintFor by NerdStats.qualitySearchHintFor.collectAsStateWithLifecycle()
                    val showQualitySearch = qualitySearchHintFor == song.videoId

                    val showNerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
                    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
                    val automixStats by NerdStats.automix.collectAsStateWithLifecycle()
                    val badgeQuality = rememberTrackLosslessBadgeState(song, allowProbe = false)
                    val smartAnalysis by AppSettings.smartAnalysis.collectAsStateWithLifecycle()
                    val smartTransitionWindow by AppSettings.smartTransitionWindow.collectAsStateWithLifecycle()
                    val onWifi by AppSettings.wifiConnection.collectAsStateWithLifecycle()
                    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
                    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
                    val wifiMaximum by AppSettings.audioQualityWifiMaximum.collectAsStateWithLifecycle()
                    val cellularMaximum by AppSettings.audioQualityCellularMaximum.collectAsStateWithLifecycle()
                    val activeQuality = if (onWifi == true) wifiQuality else cellularQuality
                    val activeMaximum = if (onWifi == true) wifiMaximum else cellularMaximum
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(
                                y = if (isTabletLandscape) {
                                    (-23).dp + phoneLandscapeMainShift
                                } else {
                                    (-33).dp
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatTime((shown * durationMs).toLong()),
                                style = MaterialTheme.typography.labelMedium,
                                color = sliderContentColor.copy(alpha = 0.60f),
                            )
                            Text(
                                text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                                style = MaterialTheme.typography.labelMedium,
                                color = sliderContentColor.copy(alpha = 0.60f),
                            )
                        }

                        LosslessOrStats(
                            albumSequential = albumSequential && !shuffleEnabled,
                            showQualitySearch = showQualitySearch,
                            nerdStats = nerdStats,
                            automixStats = automixStats,
                            badgeQuality = badgeQuality,
                            currentMediaId = song.videoId,
                            showNerdStats = showNerdStats,
                            smartAnalysis = smartAnalysis,
                            transitionWindow = smartTransitionWindow,
                            onWifi = onWifi,
                            activeQuality = activeQuality,
                            activeMaximum = activeMaximum,
                            trackDurationMs = durationMs,
                            contentColor = sliderContentColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 8.dp),
                        )
                    }

                    // Keep the complete normal control area in the composition at
                    // all times. Previously the lyrics branch was much shorter than
                    // the normal-player branch; when lyricsOpen finally flipped to
                    // false, Compose inserted the missing shuffle/repeat/autoplay/
                    // queue row, changed the available height, and the artwork/header
                    // visibly jumped/retracted.
                    //
                    // Now geometry is final from the first frame. Lyrics attribution
                    // and player controls only cross-fade inside that fixed footprint.
                    val controlsReveal = if (lyricsOpen) {
                        ((lyricsDismiss - 0.20f) / 0.42f).coerceIn(0f, 1f)
                    } else {
                        1f
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Spacer(Modifier.height(14.dp))

                            // Transport controls may still fade out while lyrics are
                            // expanded, but they keep their footprint so no geometry
                            // below them moves.
                            if (OrbFlavorUi.expressive) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(
                                            y = if (isTabletLandscape) {
                                                (-1).dp + phoneLandscapeMainShift
                                            } else {
                                                (-21).dp
                                            },
                                        )
                                        .graphicsLayer {
                                            alpha = controlsReveal
                                            translationY =
                                                if (lyricsOpen) {
                                                    (1f - controlsReveal) * 10.dp.toPx()
                                                } else {
                                                    0f
                                                }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ExpressiveTransportCluster(
                                        isPlaying = isPlaying,
                                        isLoading = isLoading,
                                        canGoPrevious = transportEnabled && !lyricsOpen &&
                                                (hasPrevious || positionMs > BACK_RESTARTS_AFTER_MS),
                                        canGoNext = transportEnabled && !lyricsOpen && hasNext,
                                        canPlayPause = transportEnabled && !lyricsOpen,
                                        tintColor = controlsContentColor,
                                        onPrevious = onPrevious,
                                        onPlayPause = onPlayPause,
                                        onNext = onNext,
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(
                                            y = if (isTabletLandscape) {
                                                (-1).dp + phoneLandscapeMainShift
                                            } else {
                                                (-21).dp
                                            },
                                        )
                                        .graphicsLayer {
                                            alpha = controlsReveal
                                            translationY =
                                                if (lyricsOpen) {
                                                    (1f - controlsReveal) * 10.dp.toPx()
                                                } else {
                                                    0f
                                                }
                                        },
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TransportGlyph(
                                        icon = Icons.Rounded.FastRewind,
                                        contentDescription = "Previous",
                                        size = 46.dp,
                                        onClick = onPrevious,
                                        enabled = transportEnabled && !lyricsOpen &&
                                                (hasPrevious || positionMs > BACK_RESTARTS_AFTER_MS),
                                        tintColor = controlsContentColor,
                                    )
                                    if (isLoading) {
                                        Box(
                                            modifier = Modifier.size(74.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                color = controlsContentColor,
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
                                            enabled = transportEnabled && !lyricsOpen,
                                            tintColor = controlsContentColor,
                                        )
                                    }
                                    TransportGlyph(
                                        icon = Icons.Rounded.FastForward,
                                        contentDescription = "Next",
                                        size = 46.dp,
                                        onClick = onNext,
                                        enabled = transportEnabled && !lyricsOpen && hasNext,
                                        tintColor = controlsContentColor,
                                    )
                                }
                            }

                            Spacer(Modifier.height(62.dp))

                            // Shuffle / repeat / AutoPlay / queue are persistent
                            // controls. Expanding lyrics must not fade, move or
                            // remove this row; it stays in exactly the same position
                            // and remains interactive.
                            if (OrbFlavorUi.expressive) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(y = if (isTabletLandscape) (-2).dp else (-12).dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        modifier = Modifier,
                                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BottomGlyph(
                                            icon = BitChordIcons.Shuffle,
                                            contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                                            onClick = onToggleShuffle,
                                            enabled = true,
                                            highlighted = shuffleEnabled,
                                            tintColor = controlsContentColor,
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
                                            enabled = true,
                                            highlighted = repeatMode != Player.REPEAT_MODE_OFF,
                                            tintColor = controlsContentColor,
                                        )
                                        BottomGlyph(
                                            icon = BitChordIcons.Infinity,
                                            contentDescription = if (autoplayEnabled) "AutoPlay on" else "AutoPlay off",
                                            onClick = onToggleAutoplay,
                                            enabled = true,
                                            highlighted = autoplayEnabled,
                                            tintColor = controlsContentColor,
                                        )
                                        BottomGlyph(
                                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = "Up next",
                                            onClick = {
                                                onQueueOpenChange(!queueOpen)
                                            },
                                            enabled = true,
                                            highlighted = queueOpen,
                                            tintColor = controlsContentColor,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(y = if (isTabletLandscape) (-2).dp else (-12).dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BottomGlyph(
                                        icon = BitChordIcons.Shuffle,
                                        contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                                        onClick = onToggleShuffle,
                                        enabled = true,
                                        highlighted = shuffleEnabled,
                                        tintColor = controlsContentColor,
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
                                        enabled = true,
                                        highlighted = repeatMode != Player.REPEAT_MODE_OFF,
                                        tintColor = controlsContentColor,
                                    )
                                    BottomGlyph(
                                        icon = BitChordIcons.Infinity,
                                        contentDescription = if (autoplayEnabled) "AutoPlay on" else "AutoPlay off",
                                        onClick = onToggleAutoplay,
                                        enabled = true,
                                        highlighted = autoplayEnabled,
                                        tintColor = controlsContentColor,
                                    )
                                    BottomGlyph(
                                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = "Up next",
                                        onClick = {
                                            onQueueOpenChange(!queueOpen)
                                        },
                                        enabled = true,
                                        highlighted = queueOpen,
                                        tintColor = controlsContentColor,
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))
                        }

                        if (lyricsOpen) {
                            val attributionAlpha =
                                (1f - lyricsDismiss / 0.34f).coerceIn(0f, 1f)
                            val translationEnabled =
                                !lyrics.isNullOrEmpty() && !translatingLyrics &&
                                    (showingTranslation || lyricsAlreadyInAppLanguage != true)
                            val translationLabel = stringResource(
                                if (showingTranslation) {
                                    R.string.lyrics_translation_show_original
                                } else {
                                    R.string.lyrics_translation_translate
                                },
                            )
                            val sourceText = lyricsSource?.let { "Lyrics by ${it.label}" }
                                ?: if (lyricsUnavailable) "No lyrics found" else null

                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 16.dp)
                                    .graphicsLayer {
                                        alpha = attributionAlpha
                                        translationY = lyricsDismiss * 24.dp.toPx()
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(controlsContentColor.copy(alpha = 0.10f))
                                        .clickable(
                                            enabled = translationEnabled,
                                            onClick = ::toggleTranslation,
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (translatingLyrics) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(15.dp),
                                            strokeWidth = 1.5.dp,
                                            color = controlsContentColor.copy(alpha = 0.75f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Translate,
                                            contentDescription = translationLabel,
                                            tint = when {
                                                showingTranslation -> controlsContentColor.copy(alpha = 0.80f)
                                                translationEnabled -> controlsContentColor.copy(alpha = 0.80f)
                                                else -> controlsContentColor.copy(alpha = 0.34f)
                                            },
                                            modifier = Modifier.size(17.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = translationLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = when {
                                            showingTranslation -> controlsContentColor.copy(alpha = 0.82f)
                                            translationEnabled -> controlsContentColor.copy(alpha = 0.82f)
                                            else -> controlsContentColor.copy(alpha = 0.42f)
                                        },
                                    )
                                }
                                sourceText?.let {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = controlsContentColor.copy(alpha = 0.62f),
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
    contentColor: Color,
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
            color = contentColor.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = room,
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = contentColor,
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
            color = contentColor,
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
    contentColor: Color,
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
                color = contentColor.copy(alpha = 0.60f),
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
                    tint = contentColor.copy(alpha = lineAlpha),
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
                        contentColor = contentColor,
                        modifier = shape,
                        glowAlpha = glow,
                        glowRoom = GLOW_ROOM,
                    )
                } else {
                    Text(
                        text = line.text,
                        style = style,
                        color = contentColor,
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
    contentColor: Color,
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

    val introLines = stringArrayResource(R.array.now_playing_intro_lines)
    val introLinesKey = introLines.contentHashCode()
    val introLine = remember(trackKey, introLinesKey) { introLines.random() }

    val text = when {
        intro -> introLine
        instrumental -> stringResource(R.string.now_playing_instrumental)
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
                tint = contentColor,
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
                contentColor = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.50f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun LyricsUnavailableLine(
    trackKey: Any,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
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
        text = stringResource(R.string.now_playing_lyrics_not_available),
        style = MaterialTheme.typography.titleMedium,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun LyricsLoadingLine(
    trackKey: Any,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val loadingLines = stringArrayResource(R.array.now_playing_lyrics_loading_lines)
    val loadingLinesKey = loadingLines.contentHashCode()
    val text = remember(trackKey, loadingLinesKey) { loadingLines.random() }

    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = contentColor.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun FocusTransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tintColor: Color,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "mobile-focus-transport-scale",
    )
    val touchSize = if (prominent) 44.dp else 38.dp
    val iconSize = if (prominent) 28.dp else 22.dp

    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.30f
            }
            .clip(if (prominent) RoundedCornerShape(14.dp) else CircleShape)
            .background(
                if (prominent) tintColor.copy(alpha = 0.13f) else Color.Transparent,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tintColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    tintColor: Color = Color.White,
    touchSize: Dp = if (OrbFlavorUi.expressive) 42.dp else 36.dp,
    iconSize: Dp = if (OrbFlavorUi.expressive) 23.dp else 20.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && OrbFlavorUi.expressive) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "headerGlyphScale",
    )

    // Like and More are intentionally bare glyphs. Their touch target remains
    // generous, but no disc/container is painted behind them in either flavor.
    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tintColor.copy(alpha = if (active) 1f else 0.88f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun ExpressiveTransportCluster(
    isPlaying: Boolean,
    isLoading: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    canPlayPause: Boolean,
    tintColor: Color,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    var activeSlot by remember { mutableIntStateOf(1) }
    val slotSize = 72.dp
    val slotGap = 18.dp
    val step = slotSize + slotGap

    // A side click cancels/restarts this effect naturally; no competing delayed
    // jobs can pull the highlight back at the wrong time.
    LaunchedEffect(activeSlot) {
        if (activeSlot != 1) {
            delay(320)
            activeSlot = 1
        }
    }

    val indicatorOffset by animateDpAsState(
        targetValue = when (activeSlot) {
            0 -> 0.dp
            2 -> step * 2
            else -> step
        },
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "expressiveTransportIndicatorOffset",
    )
    val indicatorSize by animateDpAsState(
        targetValue = if (activeSlot == 1) 72.dp else 62.dp,
        animationSpec = spring(
            dampingRatio = 0.70f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveTransportIndicatorSize",
    )
    val indicatorCorner by animateDpAsState(
        targetValue = if (activeSlot == 1) 24.dp else 31.dp,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveTransportIndicatorCorner",
    )

    Box(
        modifier = Modifier.size(width = slotSize * 3 + slotGap * 2, height = 80.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset + (slotSize - indicatorSize) / 2)
                .size(indicatorSize)
                .clip(RoundedCornerShape(indicatorCorner))
                .background(tintColor.copy(alpha = 0.17f)),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(slotGap)) {
            ExpressiveTransportSlot(
                modifier = Modifier.size(slotSize),
                selected = activeSlot == 0,
                enabled = canGoPrevious,
                baseTint = tintColor,
                onClick = {
                    activeSlot = 0
                    onPrevious()
                },
            ) { iconTint ->
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous",
                    tint = iconTint,
                    modifier = Modifier.size(31.dp),
                )
            }
            ExpressiveTransportSlot(
                modifier = Modifier.size(slotSize),
                selected = activeSlot == 1,
                enabled = canPlayPause,
                baseTint = tintColor,
                onClick = {
                    activeSlot = 1
                    onPlayPause()
                },
            ) { iconTint ->
                if (isLoading) {
                    CircularProgressIndicator(
                        color = iconTint,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = iconTint,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            ExpressiveTransportSlot(
                modifier = Modifier.size(slotSize),
                selected = activeSlot == 2,
                enabled = canGoNext,
                baseTint = tintColor,
                onClick = {
                    activeSlot = 2
                    onNext()
                },
            ) { iconTint ->
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = iconTint,
                    modifier = Modifier.size(31.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpressiveTransportSlot(
    modifier: Modifier = Modifier,
    selected: Boolean,
    enabled: Boolean,
    baseTint: Color,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveTransportSlotScale",
    )
    val tint = when {
        !enabled -> baseTint.copy(alpha = 0.34f)
        selected -> baseTint
        else -> baseTint.copy(alpha = 0.92f)
    }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

@Composable
private fun TransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tintColor: Color = Color.White,
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )

    if (!OrbFlavorUi.expressive) {
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
                tint = tintColor.copy(alpha = alpha),
                modifier = Modifier.size(size),
            )
        }
        return
    }

    val prominent = size >= 60.dp
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveTransportScale",
    )
    val containerAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.055f
            prominent -> 0.22f
            else -> 0.105f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "expressiveTransportContainer",
    )
    val buttonSize = if (prominent) 80.dp else 64.dp
    val shape = RoundedCornerShape(if (prominent) 28.dp else 22.dp)
    val iconSize = if (prominent) 36.dp else 30.dp

    Box(
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(tintColor.copy(alpha = containerAlpha))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tintColor.copy(alpha = alpha),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun BottomGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    tintColor: Color = Color.White,
) {
    if (!OrbFlavorUi.expressive) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (highlighted) tintColor.copy(alpha = 0.20f) else Color.Transparent,
                )
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
                tint = tintColor.copy(
                    alpha = when {
                        !enabled -> 0.30f
                        highlighted -> 1f
                        else -> 0.75f
                    },
                ),
                modifier = Modifier.size(26.dp),
            )
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveBottomGlyphScale",
    )
    val corner by animateDpAsState(
        targetValue = if (highlighted) 21.dp else 10.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expressiveBottomGlyphCorner",
    )
    val container by animateColorAsState(
        targetValue = tintColor.copy(
            alpha = when {
                !enabled -> 0.035f
                highlighted -> 0.19f
                else -> 0.065f
            },
        ),
        label = "expressiveBottomGlyphContainer",
    )

    // State changes only the geometry of the corners, never the footprint.
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tintColor.copy(
                alpha = when {
                    !enabled -> 0.30f
                    highlighted -> 1f
                    else -> 0.80f
                },
            ),
            modifier = Modifier.size(23.dp),
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

private data class ArtistTapRange(
    val start: Int,
    val endExclusive: Int,
    val artistId: String?,
    val name: String,
)

@Composable
private fun ArtistCreditsText(
    text: String,
    artists: List<ArtistLink>,
    fallbackArtistId: String?,
    style: TextStyle,
    color: Color,
    onOpenArtist: (String?, String) -> Unit,
) {
    val ranges = remember(text, artists, fallbackArtistId) {
        artistTapRanges(text, artists, fallbackArtistId)
    }

    if (ranges.isEmpty()) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val annotated = remember(text, ranges) {
        buildAnnotatedString {
            append(text)
            ranges.forEachIndexed { index, range ->
                addStringAnnotation(
                    tag = "orb_artist",
                    annotation = index.toString(),
                    start = range.start,
                    end = range.endExclusive,
                )
            }
        }
    }

    // ClickableText owns the character hit-testing itself. This is deliberately
    // used instead of pointerInput + TextLayoutResult#getOffsetForPosition:
    // artist links are enriched asynchronously while Now Playing is visible,
    // and keeping a separate layout object and tap-range snapshot can make a
    // perfectly visible name temporarily untappable after recomposition.
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            val annotation = annotated
                .getStringAnnotations(tag = "orb_artist", start = offset, end = offset)
                .firstOrNull()
                ?: return@ClickableText
            val target = annotation.item.toIntOrNull()
                ?.let(ranges::getOrNull)
                ?: return@ClickableText
            onOpenArtist(target.artistId, target.name)
        },
    )
}

private fun String.looksLikeCompositeArtistCredit(): Boolean =
    contains(",") ||
        contains(" & ") ||
        contains(" feat", ignoreCase = true) ||
        contains(" featuring", ignoreCase = true) ||
        contains(" ft.", ignoreCase = true) ||
        contains(" ft ", ignoreCase = true) ||
        contains(" with ", ignoreCase = true) ||
        contains(" x ", ignoreCase = true) ||
        contains(";")

private val artistTapSeparator = Regex(
    """\s*,\s*&\s*|\s*&\s*|\s*,\s*|\s*;\s*|\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|x)\s+""",
    setOf(RegexOption.IGNORE_CASE),
)

private fun artistTapRanges(
    text: String,
    artists: List<ArtistLink>,
    fallbackArtistId: String?,
): List<ArtistTapRange> {
    if (text.isBlank()) return emptyList()

    val validLinks = artists
        .filter { it.name.isNotBlank() && it.artistId.isNotBlank() }
        .distinctBy { it.artistId to it.name.lowercase() }

    // An authoritative link whose display name is the complete visible credit
    // means this is one actual artist/group (e.g. "Mumford & Sons"), not a
    // collaboration that should be split at punctuation.
    val whole = validLinks.firstOrNull {
        it.name.artistTapKey() == text.artistTapKey()
    }
    if (whole != null) {
        return listOf(ArtistTapRange(0, text.length, whole.artistId, text))
    }

    val linkedRanges = mutableListOf<ArtistTapRange>()
    val occupied = BooleanArray(text.length)
    validLinks
        .sortedByDescending { it.name.length }
        .forEach { artist ->
            var from = 0
            while (from < text.length) {
                val start = text.indexOf(artist.name, from, ignoreCase = true)
                if (start < 0) break
                val end = start + artist.name.length
                val before = start == 0 || !text[start - 1].isLetterOrDigit()
                val after = end == text.length || !text[end].isLetterOrDigit()
                if (before && after && (start until end).none { occupied[it] }) {
                    (start until end).forEach { occupied[it] = true }
                    linkedRanges += ArtistTapRange(start, end, artist.artistId, text.substring(start, end))
                    break
                }
                from = start + 1
            }
        }

    if (!text.looksLikeCompositeArtistCredit()) {
        if (linkedRanges.isNotEmpty()) return linkedRanges.sortedBy { it.start }
        return fallbackArtistId
            ?.takeIf(String::isNotBlank)
            ?.let { listOf(ArtistTapRange(0, text.length, it, text)) }
            .orEmpty()
    }

    val candidates = artistTapSeparator.split(text)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    if (candidates.size < 2) return linkedRanges.sortedBy { it.start }

    // Every visible part gets its own hit target, even before all browseIds
    // have arrived. For an ambiguous real group name such as "Mumford & Sons",
    // the lazy click resolver checks the complete credit first and will open
    // that canonical group page rather than inventing two artists.
    val out = mutableListOf<ArtistTapRange>()
    var searchFrom = 0
    candidates.forEach { name ->
        val start = text.indexOf(name, startIndex = searchFrom, ignoreCase = true)
        if (start < 0) return@forEach
        val end = start + name.length
        val link = validLinks.firstOrNull { it.name.artistTapKey() == name.artistTapKey() }
        out += ArtistTapRange(start, end, link?.artistId, text.substring(start, end))
        searchFrom = end
    }

    return (out + linkedRanges)
        .distinctBy { Triple(it.start, it.endExclusive, it.name.artistTapKey()) }
        .sortedBy { it.start }
}

private fun String.artistTapKey(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace('’', '\'')
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("""\s+"""), " ")
        .trim()

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

/**
 * Masks the blurred hero echo so it is fully present around the cover edge and
 * then vanishes very gradually into the generated player surface.
 *
 * Kept as a draw-only mask: it changes no measurement or positioning in the
 * Now Playing layout.
 */
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
    shuffleEnabled: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    contentColor: Color,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.now_playing_queue_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor,
                )
            }
            Text(
                text = stringResource(R.string.now_playing_queue_clear),
                style = MaterialTheme.typography.titleMedium,
                color = contentColor.copy(alpha = 0.75f),
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
                    draggable = !shuffleEnabled && index >= firstMovable,
                    dragging = dragging,
                    onDragStart = { manualDrag.onDragStart(key) },
                    onDrag = manualDrag::onDrag,
                    onDragEnd = manualDrag::onDragEnd,
                    contentColor = contentColor,
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
                            tint = contentColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.now_playing_autoplay_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = contentColor,
                            )
                            Text(
                                text = if (autoplayStart < queue.size) {
                                    stringResource(R.string.now_playing_autoplay_queued_subtitle)
                                } else {
                                    stringResource(R.string.now_playing_autoplay_empty_subtitle)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor.copy(alpha = 0.60f),
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
                    draggable = !shuffleEnabled,
                    dragging = dragging,
                    onDragStart = { autoplayDrag.onDragStart(key) },
                    onDrag = autoplayDrag::onDrag,
                    onDragEnd = autoplayDrag::onDragEnd,
                    contentColor = contentColor,
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
    contentColor: Color,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (dragging) contentColor.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draggable) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = contentColor.copy(alpha = 0.40f),
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
                .background(contentColor.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ExplicitTitle(
                text = song.title,
                isExplicit = song.isExplicit,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) contentColor else contentColor.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val durationTint = contentColor.copy(alpha = if (isCurrent) 0.86f else 0.58f)
        Spacer(Modifier.width(8.dp))
        DownloadStatusGlyph(
            videoId = song.videoId,
            tint = durationTint,
        )
        song.durationText?.let { duration ->
            Spacer(Modifier.width(5.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelMedium,
                color = durationTint,
            )
        }
        if (isCurrent) {
            Spacer(Modifier.width(10.dp))
            AnimatedNowPlayingIndicator(
                color = contentColor,
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
                tint = contentColor.copy(alpha = 0.60f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AnimatedNowPlayingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val motion = rememberInfiniteTransition(label = "queue-now-playing")
    val a by motion.animateFloat(
        initialValue = 0.30f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq-a",
    )
    val b by motion.animateFloat(
        initialValue = 0.88f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(430, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq-b",
    )
    val c by motion.animateFloat(
        initialValue = 0.48f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(
            animation = tween(610, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq-c",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(a, b, c).forEach { level ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((4f + 11f * level).dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color),
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
    albumSequential: Boolean,
    showQualitySearch: Boolean,
    nerdStats: NerdStats.Snapshot?,
    automixStats: NerdStats.AutomixSnapshot?,
    badgeQuality: LosslessBadgeState,
    currentMediaId: String,
    showNerdStats: Boolean,
    smartAnalysis: SmartAnalysis,
    transitionWindow: TransitionWindow?,
    onWifi: Boolean?,
    activeQuality: AudioQuality,
    activeMaximum: Boolean,
    trackDurationMs: Long,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    // Nerd stats are diagnostics; quality badges must not depend on that toggle.
    // Prefer the renderer's measured codec when it exists, then fall back to
    // the resolver's session cache while Media3 is still opening the stream.
    val trackStats = nerdStats?.takeIf { it.mediaId == null || it.mediaId == currentMediaId }
    val rendererHasCodec = !trackStats?.mimeType.isNullOrBlank()
    val hasVerifiedNativeLossless = trackStats?.nativeLosslessVerified == true
    // Renderer MIME is final ground truth. When Media3 has not exposed it yet,
    // independently verified FLAC/container evidence is strong enough to keep
    // a real low-bitrate FLAC classified as Lossless. Bare source claims still
    // do not light the badge.
    val isLossless = when {
        rendererHasCodec -> trackStats?.isLossless == true
        hasVerifiedNativeLossless -> trackStats?.isLossless == true
        else -> badgeQuality.isLossless
    }
    val isHiRes = when {
        rendererHasCodec -> trackStats?.isHiRes == true
        hasVerifiedNativeLossless -> trackStats?.isHiRes == true
        else -> badgeQuality.isHiResLossless
    }
    val isHiQuality = if (rendererHasCodec) trackStats?.isHiQuality == true else badgeQuality.isHiQuality
    val automixEnabled by AppSettings.automixEnabled.collectAsStateWithLifecycle()
    val automixMixing = automixStats?.stage == NerdStats.AutomixStage.MIXING

    when {
        !showNerdStats && automixEnabled && automixMixing -> LosslessLabel(
            text = stringResource(R.string.now_playing_mixing),
            animated = false,
            showMark = false,
            contentColor = contentColor,
            modifier = modifier,
        )
        showNerdStats && (albumSequential || trackStats != null || automixStats != null || showQualitySearch) -> NerdStatsLine(
            albumSequential = albumSequential,
            nerdStats = trackStats,
            automixStats = automixStats,
            qualityStatus = if (showQualitySearch) stringResource(R.string.now_playing_upgrading_quality) else null,
            smartAnalysis = smartAnalysis,
            transitionWindow = transitionWindow,
            onWifi = onWifi,
            activeQuality = activeQuality,
            activeMaximum = activeMaximum,
            trackDurationMs = trackDurationMs,
            contentColor = contentColor,
            modifier = modifier,
        )
        showQualitySearch && !isLossless -> LosslessLabel(
            text = stringResource(R.string.now_playing_upgrading_quality),
            animated = false,
            showMark = false,
            contentColor = contentColor,
            modifier = modifier,
        )
        isLossless -> LosslessLabel(
            text = stringResource(
                if (isHiRes) R.string.hi_res_lossless_badge else R.string.lossless_badge,
            ),
            animated = true,
            contentColor = contentColor,
            modifier = modifier,
        )
        isHiQuality -> HiQualityWordmark(
            color = contentColor.copy(alpha = 0.62f),
            iconWidth = 21.dp,
            iconHeight = 13.dp,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
            ),
            modifier = modifier,
        )
        else -> {}
    }
}

@Composable
private fun NerdStatsLine(
    albumSequential: Boolean,
    nerdStats: NerdStats.Snapshot?,
    automixStats: NerdStats.AutomixSnapshot?,
    qualityStatus: String?,
    smartAnalysis: SmartAnalysis,
    transitionWindow: TransitionWindow?,
    onWifi: Boolean?,
    activeQuality: AudioQuality,
    activeMaximum: Boolean,
    trackDurationMs: Long,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val audioDetails = buildString {
        append(
            nerdStats?.describe()?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.nerd_audio_waiting),
        )
        qualityStatus?.takeIf(String::isNotBlank)?.let { status ->
            append(" · ")
            append(status)
        }
    }
    val audioLine = stringResource(R.string.nerd_stats_audio_quality_line, audioDetails)

    val aState = localizedAnalysisState(smartAnalysis.current)
    val bState = localizedAnalysisState(smartAnalysis.next)
    val pairStatusLine = stringResource(R.string.nerd_stats_pair_status_line, aState, bState)

    val transitionStartMs = automixStats?.outgoingStartMs
        ?: transitionWindow?.takeIf { trackDurationMs > 0L }?.let { window ->
            (window.start.coerceIn(0f, 1f) * trackDurationMs).toLong()
        }
    val pairAnalysisComplete =
        smartAnalysis.current in setOf(TrackAnalysisState.ANALYSED, TrackAnalysisState.REFINING) &&
            smartAnalysis.next in setOf(
                TrackAnalysisState.READY_FOR_PLAN,
                TrackAnalysisState.ANALYSED,
                TrackAnalysisState.REFINING,
            )
    val transitionStyle = automixStats?.style?.let { localizedAutomixStyle(it) }
    val transitionLine = when {
        // Never expose a provisional/stale verdict while either side is still
        // being measured, or while mix-v9 is still choosing/executability-checking
        // a recipe for an already analysed pair.
        !pairAnalysisComplete -> stringResource(R.string.nerd_analysis_analysing)
        automixStats?.stage == NerdStats.AutomixStage.PLANNING ->
            stringResource(R.string.nerd_stats_transition_pending_line)
        transitionStyle != null && transitionStartMs != null -> stringResource(
            R.string.nerd_stats_transition_line,
            transitionStyle,
            formatAutomixTime(transitionStartMs),
        )
        else -> stringResource(R.string.nerd_stats_transition_pending_line)
    }

    // Keep Stats for Nerds intentionally compact: exactly three user-facing lines.
    Column(
        modifier = modifier.widthIn(max = 280.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NerdMarqueeLine(text = audioLine, color = contentColor.copy(alpha = 0.76f))
        if (albumSequential) {
            NerdMarqueeLine(text = stringResource(R.string.nerd_original_album_order), color = contentColor.copy(alpha = 0.82f))
        } else {
            NerdMarqueeLine(text = pairStatusLine, color = contentColor.copy(alpha = 0.82f))
            NerdMarqueeLine(text = transitionLine, color = contentColor.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun nerdPlaybackContextLine(
    stats: NerdStats.Snapshot?,
    onWifi: Boolean?,
    activeQuality: AudioQuality,
    activeMaximum: Boolean,
): String? {
    val source = stats?.sourceName?.let {
        if (it == "Local") stringResource(R.string.library_local_music) else it
    }
    val network = when (onWifi) {
        true -> stringResource(R.string.audio_wifi)
        false -> stringResource(R.string.audio_mobile_data)
        null -> null
    }
    val quality = when (activeQuality) {
        AudioQuality.LOW -> stringResource(R.string.audio_quality_low_name)
        AudioQuality.MEDIUM -> stringResource(R.string.audio_quality_medium_name)
        AudioQuality.HIGH -> stringResource(R.string.audio_quality_high_name)
        AudioQuality.AAC -> "AAC 320"
    }
    val parts = buildList {
        source?.let { add(it) }
        network?.let { add(it) }
        add(quality + if (activeMaximum) " + Lossless" else "")
        stats?.claimed?.summary?.takeIf { it.isNotBlank() }?.let { add("src $it") }
    }
    return parts.joinToString(" · ").takeIf(String::isNotBlank)
}

@Composable
private fun nerdAnalysisLine(
    analysis: SmartAnalysis,
    window: TransitionWindow?,
    durationMs: Long,
): String? {
    val a = localizedAnalysisState(analysis.current)
    val b = localizedAnalysisState(analysis.next)
    val parts = mutableListOf("A $a", "B $b")
    if (window != null && durationMs > 0L) {
        val start = (window.start.coerceIn(0f, 1f) * durationMs).toLong()
        val end = (window.end.coerceIn(0f, 1f) * durationMs).toLong()
        parts += "window ${formatAutomixTime(start)}–${formatAutomixTime(end)}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun localizedAnalysisState(state: TrackAnalysisState): String = when (state) {
    TrackAnalysisState.WAITING -> stringResource(R.string.nerd_analysis_waiting)
    TrackAnalysisState.ANALYSING -> stringResource(R.string.nerd_analysis_analysing)
    TrackAnalysisState.READY_FOR_PLAN -> stringResource(R.string.nerd_analysis_ready_for_plan)
    TrackAnalysisState.ANALYSED -> stringResource(R.string.nerd_analysis_analysed)
    TrackAnalysisState.REFINING -> stringResource(R.string.nerd_analysis_refining)
    TrackAnalysisState.FAILED -> stringResource(R.string.nerd_analysis_failed)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NerdMarqueeLine(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 11.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .basicMarquee(),
    )
}

@Composable
private fun NerdStats.AutomixSnapshot.simpleAutomixStatus(): String = when (stage) {
    NerdStats.AutomixStage.ANALYZING_A -> stringResource(R.string.automix_nerd_analyzing_a)
    NerdStats.AutomixStage.A_ANALYZED -> stringResource(R.string.automix_nerd_a_analyzed)
    NerdStats.AutomixStage.ANALYZING_B -> if (outgoingAnalyzed) {
        stringResource(R.string.automix_nerd_a_done_analyzing_b)
    } else {
        stringResource(R.string.automix_nerd_analyzing_b)
    }
    NerdStats.AutomixStage.B_ANALYZED -> stringResource(R.string.automix_nerd_b_analyzed)
    NerdStats.AutomixStage.PLANNING -> stringResource(R.string.automix_nerd_planning)
    NerdStats.AutomixStage.READY -> stringResource(
        R.string.automix_nerd_transition_ready,
        localizedAutomixStyle(style),
    )
    NerdStats.AutomixStage.PREPARING_B -> stringResource(R.string.automix_nerd_preparing_b)
    NerdStats.AutomixStage.B_READY -> stringResource(R.string.automix_nerd_b_ready)
    NerdStats.AutomixStage.MIXING -> stringResource(
        R.string.automix_nerd_mixing,
        localizedAutomixStyle(style),
    )
    NerdStats.AutomixStage.COMPLETED -> stringResource(R.string.automix_nerd_completed)
}

@Composable
private fun localizedAutomixStyle(style: String?): String = when (style) {
    "BLEND", "DJ_BLEND" -> stringResource(R.string.automix_style_blend)
    "DJ_FILTER" -> stringResource(R.string.automix_style_filter)
    "RUNWAY_BLEND" -> stringResource(R.string.automix_style_runway_blend)
    "PHRASE_TAKEOVER" -> stringResource(R.string.automix_style_phrase_takeover)
    "EQ_SWAP" -> stringResource(R.string.automix_style_eq_swap)
    "INTRO_BED" -> stringResource(R.string.automix_style_intro_bed)
    "INTRO_BRIDGE_FILTER" -> stringResource(R.string.automix_style_intro_bridge_filter)
    "FOREGROUND_TAKEOVER" -> stringResource(R.string.automix_style_takeover)
    "PHRASE_CUT" -> stringResource(R.string.automix_style_phrase_cut)
    "RISE" -> stringResource(R.string.automix_style_rise)
    "CUT" -> stringResource(R.string.automix_style_cut)
    "EQUAL_POWER", "FADE" -> stringResource(R.string.automix_style_crossfade)
    "GAPLESS" -> stringResource(R.string.automix_style_gapless)
    "NO_TRANSITION" -> stringResource(R.string.automix_style_no_mix)
    else -> stringResource(R.string.automix_style_unknown)
}

private fun NerdStats.AutomixSnapshot.automixPlanLine(): String? {
    if (style == null && musicalScore == null && durationMs == null) return null
    val parts = buildList {
        style?.let { add(it) }
        musicalScore?.let { add("score ${formatAutomixScore(it)}") }
        if (transitionBeats > 0) add("$transitionBeats beats")
        if (candidateCount > 0) add("$candidateCount candidates")
        outgoingStartMs?.let { add("A out ${formatAutomixTime(it)}") }
        durationMs?.let { add("${"%.1f".format(it / 1000f)}s") }
        progress?.let { add("${(it * 100f).toInt().coerceIn(0, 100)}%") }
        planReason?.takeIf(String::isNotBlank)?.let { add(it) }
    }
    return parts.joinToString(" · ").takeIf(String::isNotBlank)
}

private fun NerdStats.AutomixSnapshot.automixTechnicalLine(): String? {
    val parts = buildList {
        outgoingBpm?.let { bpm ->
            val conf = outgoingBeatConfidence?.let { " c${formatAutomixScore(it)}" }.orEmpty()
            add("A ${"%.1f".format(bpm)} BPM${outgoingKey?.let { " $it" } ?: ""}$conf")
        }
        incomingBpm?.let { bpm ->
            val conf = incomingBeatConfidence?.let { " c${formatAutomixScore(it)}" }.orEmpty()
            add("B ${"%.1f".format(bpm)} BPM${incomingKey?.let { " $it" } ?: ""}$conf")
        }
        tempoScore?.let { add("tempo ${formatAutomixScore(it)}") }
        harmonicScore?.let { add("key ${formatAutomixScore(it)}") }
        structureScore?.let { add("structure ${formatAutomixScore(it)}") }
        energyScore?.let { add("energy ${formatAutomixScore(it)}") }
        vocalRisk?.let { add("vocal-risk ${formatAutomixScore(it)}") }
        vocalOverlap?.let { add("vocal-overlap ${formatAutomixScore(it)}") }
        incomingCueMs?.let { add("B cue ${formatAutomixTime(it)}") }
        incomingRate?.takeIf { kotlin.math.abs(it - 1f) > 0.0005f }?.let { add("rate ${"%.3f".format(it)}×") }
        if (outgoingAnalysisSource != NerdStats.AutomixAnalysisSource.NONE) {
            add("A ${outgoingAnalysisSource.nerdLabel()}")
        }
        if (incomingAnalysisSource != NerdStats.AutomixAnalysisSource.NONE) {
            add("B ${incomingAnalysisSource.nerdLabel()}")
        }
        if (quality != NerdStats.AutomixQuality.WAITING) add("B ${quality.nerdLabel()}")
        policyReasons.take(2).filter(String::isNotBlank).forEach { add(it) }
    }
    return parts.joinToString(" · ").takeIf(String::isNotBlank)
}

private fun NerdStats.AutomixAnalysisSource.nerdLabel(): String = when (this) {
    NerdStats.AutomixAnalysisSource.NONE -> "none"
    NerdStats.AutomixAnalysisSource.STORED -> "stored"
    NerdStats.AutomixAnalysisSource.CACHE_WARMING -> "cache-warming"
    NerdStats.AutomixAnalysisSource.CACHE_HEAD -> "cache-head"
    NerdStats.AutomixAnalysisSource.CACHE_FULL -> "cache-full"
    NerdStats.AutomixAnalysisSource.RELIABLE_DOWNLOAD -> "download"
    NerdStats.AutomixAnalysisSource.REMOTE -> "remote"
    NerdStats.AutomixAnalysisSource.RELIABLE_FILE -> "file"
    NerdStats.AutomixAnalysisSource.FAILED -> "analysis-failed"
}

private fun NerdStats.AutomixQuality.nerdLabel(): String = when (this) {
    NerdStats.AutomixQuality.WAITING -> "waiting"
    NerdStats.AutomixQuality.SEARCHING -> "quality-search"
    NerdStats.AutomixQuality.LOSSLESS_READY -> "lossless-ready"
    NerdStats.AutomixQuality.HI_QUALITY_READY -> "hi-q-ready"
    NerdStats.AutomixQuality.OPUS_FALLBACK -> "opus-fallback"
    NerdStats.AutomixQuality.LOCAL -> "local"
}

private fun formatAutomixScore(value: Float): String = "%.2f".format(value.coerceIn(0f, 1f))

private fun formatAutomixTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun LosslessLabel(
    text: String,
    animated: Boolean,
    showMark: Boolean = true,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (animated && showMark) {
        ShimmerLosslessWordmark(
            text = text,
            contentColor = contentColor,
            modifier = modifier,
        )
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMark) {
            LosslessWordmark(
                text = text,
                color = contentColor.copy(alpha = if (animated) 0.70f else 0.45f),
                iconWidth = 21.dp,
                iconHeight = 13.dp,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
                ),
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
                ),
                color = contentColor.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The recognition glow is a travelling highlight across the *entire* Lossless
 * seal — ribbon and text together. The base keeps the artwork-derived dynamic
 * content colour and the passing highlight increases only its intensity.
 */
@Composable
private fun ShimmerLosslessWordmark(
    text: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "lossless-seal-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lossless-seal-shimmer-progress",
    )

    // Keep the exact dynamic Now Playing content colour, matching the E and
    // the rest of the artwork-derived UI. The travelling shine changes only
    // intensity/opacity; it never forces the badge to black or white.
    val badgeColor = contentColor
    val baseColor = badgeColor.copy(alpha = 0.62f)
    val highlightColor = badgeColor.copy(alpha = 1f)

    val shimmerModifier = modifier
        .onSizeChanged { widthPx = it.width }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (widthPx > 0) {
                val band = widthPx * 0.28f
                val center = -band + progress * (widthPx + 2f * band)
                val brush = Brush.linearGradient(
                    colors = listOf(baseColor, highlightColor, baseColor),
                    start = Offset(center - band, 0f),
                    end = Offset(center + band, 0f),
                )
                // Replace only the pixels occupied by the wordmark. Using the
                // mark as the alpha mask makes the same travelling band cross
                // the vector ribbon and every glyph without a fixed glow.
                drawRect(brush = brush, blendMode = BlendMode.SrcIn)
            } else {
                // Avoid a one-frame full-bright flash before measurement.
                drawRect(color = baseColor, blendMode = BlendMode.SrcIn)
            }
        }

    LosslessWordmark(
        text = text,
        color = badgeColor,
        iconWidth = 21.dp,
        iconHeight = 13.dp,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
        ),
        modifier = shimmerModifier,
    )
}

private fun NerdStats.Snapshot.describe(): String {
    val displayDepth = bitDepth ?: nativeFormat?.bitDepth
    val displayRate = sampleRateHz ?: nativeFormat?.sampleRateHz
    val parts = buildList {
        (codecLabel(mimeType) ?: nativeFormat?.codec?.uppercase())?.let(::add)
        displayDepth?.let { add("$it-bit") }
        if (!isLossless) bitrateKbps?.let { add("$it kbps") }
        displayRate?.let { add("%.1f kHz".format(it / 1000f)) }
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
