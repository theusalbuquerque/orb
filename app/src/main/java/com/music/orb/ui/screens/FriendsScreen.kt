package com.music.orb.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ArrowBack
import com.music.orb.ui.components.OrbGlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.music.orb.ui.components.OrbModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.music.orb.ui.components.OrbGlassOutlinedButton as OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import com.music.orb.ui.components.ExplicitBadgeSeal
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.RegisterSheetBackdrop
import com.music.orb.R
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.social.FriendNowPlaying
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.NowPlayingReaction
import com.music.orb.data.social.NowPlayingReactionType
import com.music.orb.data.social.OrbProfile
import com.music.orb.data.social.CommunityRankEntry
import com.music.orb.data.social.CommunityStatsRepository
import com.music.orb.data.social.reactionSessionKey
import com.music.orb.ui.FriendsViewModel
import com.music.orb.ui.ListeningStats
import com.music.orb.ui.FollowNotification
import com.music.orb.ui.components.OrbBouncyPillSelector
import com.music.orb.ui.ReactionNotification
import com.music.orb.ui.StatsNotification
import com.music.orb.ui.StatsLeader
import com.music.orb.ui.StatsPeriod
import com.music.orb.ui.communityRankKey
import com.music.orb.ui.PersonalMediaRanking
import com.music.orb.ui.StatsSharePalette
import com.music.orb.ui.StatsShareStoryRenderer
import com.music.orb.ui.ArtistKind
import com.music.orb.ui.ArtistRankEntry
import com.music.orb.ui.ArtistRankMovement
import com.music.orb.ui.UsernameSetupError
import com.music.orb.ui.components.ExpressiveSearchField
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.PullToRefresh
import com.music.orb.ui.components.animateScrollToCenteredItem
import com.music.orb.ui.flavor.OrbFlavorUi

private enum class StatsDestination { GENERAL, MY_NETWORK }

/**
 * The former ranking's progressive top-edge disappearance, applied to Stats cards.
 * The first and middle portions remain fully legible. Only a card travelling out
 * through the header shrinks/fades, so scrolling back restores it naturally.
 */
private fun Modifier.statsTopExit(): Modifier = composed {
    val density = LocalDensity.current
    val view = LocalView.current
    val headerEdgePx = with(density) { 124.dp.toPx() }
    val navEdgePx = with(density) { 38.dp.toPx() }
    val fadeDistancePx = with(density) { 76.dp.toPx() }
    var fraction by remember { mutableFloatStateOf(1f) }
    this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val viewportBottom = view.height.toFloat() - navEdgePx
        val top = ((bounds.bottom - headerEdgePx) / fadeDistancePx).coerceIn(0f, 1f)
        // Reverse scrolling also has a progressive entrance at the bottom edge.
        // Never dim a card that is comfortably within the viewport.
        val bottom = if (viewportBottom > headerEdgePx + fadeDistancePx) {
            ((viewportBottom - bounds.top) / fadeDistancePx).coerceIn(0f, 1f)
        } else 1f
        fraction = minOf(top, bottom)
    }.graphicsLayer {
        alpha = fraction
        scaleX = 0.94f + 0.06f * fraction
        scaleY = 0.94f + 0.06f * fraction
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }
}

/**
 * Stats' real progressive entrance, including oversized art and typography.
 * Each element enters only when its own bounds reach the viewport. An external
 * registry keeps an element revealed after the containing LazyColumn item is
 * disposed, avoiding replay/jank when the user scrolls backwards.
 *
 * Place this OUTSIDE the existing edge-exit modifier: entrance and scroll-edge
 * effects are independent, and neither blocks clicks or causes remeasurement.
 */
private fun Modifier.statsCascadeEnter(
    key: String,
    index: Int,
    revealed: MutableMap<String, Boolean>,
): Modifier = composed {
    val wasRevealed = revealed[key] == true
    val progress = remember(key) { Animatable(if (wasRevealed) 1f else 0f) }
    var enteredViewport by remember(key) { mutableStateOf(wasRevealed) }
    val view = LocalView.current
    val density = LocalDensity.current
    val travelPx = with(density) { 12.dp.toPx() }

    LaunchedEffect(key, enteredViewport) {
        if (!enteredViewport || progress.value >= 1f) return@LaunchedEffect
        // Nearby entries follow one another; offscreen entries do not wait for
        // a large global index when first revealed by scrolling.
        delay(if (index <= 6) index * 42L else 0L)
        progress.animateTo(
            1f,
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = 510f,
                visibilityThreshold = 0.001f,
            ),
        )
        revealed[key] = true
    }

    this.onGloballyPositioned { coordinates ->
        if (!enteredViewport) {
            val bounds = coordinates.boundsInWindow()
            val viewportHeight = view.height.toFloat()
            if (viewportHeight > 0f && bounds.bottom > 0f && bounds.top < viewportHeight) {
                enteredViewport = true
            }
        }
    }.graphicsLayer {
        val fraction = progress.value.coerceIn(0f, 1f)
        alpha = fraction
        translationY = travelPx * (1f - fraction)
        scaleX = 0.985f + 0.015f * fraction
        scaleY = 0.985f + 0.015f * fraction
    }
}

/** Each ranking entry exits and re-enters with a scale/fade at either scroll edge. */
private fun Modifier.statsSheetRowExit(state: LazyListState, index: Int): Modifier = composed {
    val density = LocalDensity.current
    val fadeDistance = with(density) { 82.dp.toPx() }
    this.graphicsLayer {
        val moving = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val fraction = if (!moving || item == null) 1f else {
            val start = state.layoutInfo.viewportStartOffset.toFloat()
            val end = state.layoutInfo.viewportEndOffset.toFloat()
            val topFactor = ((item.offset + item.size - start) / fadeDistance).coerceIn(0f, 1f)
            val bottomFactor = ((end - item.offset) / fadeDistance).coerceIn(0f, 1f)
            minOf(topFactor, bottomFactor)
        }
        alpha = fraction
        scaleX = 0.93f + 0.07f * fraction
        scaleY = 0.93f + 0.07f * fraction
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }
}

/** Solid, high-contrast numerals over transparent Stats cards and sheets. */
private enum class StatsMetricTone { POSITIVE, NEGATIVE, NEUTRAL }

@Composable
private fun StatsMetricPill(
    tone: StatsMetricTone = StatsMetricTone.NEUTRAL,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val ink = when (tone) {
        StatsMetricTone.POSITIVE -> if (dark) Color(0xFFA8F0BF) else Color(0xFF176333)
        StatsMetricTone.NEGATIVE -> if (dark) Color(0xFFFFB4AE) else Color(0xFF9B2636)
        StatsMetricTone.NEUTRAL -> colors.onSurface
    }
    val background = when (tone) {
        StatsMetricTone.NEUTRAL -> colors.surfaceContainerHighest
        else -> lerp(colors.surfaceContainerHighest, ink, if (dark) 0.15f else 0.10f)
    }
    val strokeColor = when (tone) {
        StatsMetricTone.NEUTRAL -> colors.outlineVariant
        else -> lerp(colors.outlineVariant, ink, 0.38f)
    }
    // Trend indicators must remain compact, outlined and high-contrast, even
    // over translucent Expressive Glass cards and bottom sheets.
    val shape = RoundedCornerShape(if (compact) 10.dp else 11.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                width = 1.dp,
                color = if (compact && tone != StatsMetricTone.NEUTRAL) {
                    lerp(colors.outlineVariant, ink, 0.58f)
                } else strokeColor,
                shape = shape,
            )
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 3.dp else 5.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(ink)
    }
}

@Composable
private fun StatsRankNumber(
    position: Int,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(shape)
            .background(if (onArtwork) colors.inverseSurface else colors.surfaceContainerHighest)
            .border(
                1.dp,
                if (onArtwork) colors.inverseOnSurface.copy(alpha = 0.55f) else colors.outlineVariant,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (onArtwork) colors.inverseOnSurface else colors.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FriendsScreen(
    listState: LazyListState,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    friendsViewModel: FriendsViewModel = viewModel(),
    onOpenProfile: (OrbProfile) -> Unit = {},
    previewOverview: Boolean = false,
    isBackPreview: Boolean = false,
) {
    val query by friendsViewModel.query.collectAsStateWithLifecycle()
    val searchResults by friendsViewModel.searchResults.collectAsStateWithLifecycle()
    val recentSearchProfiles by friendsViewModel.recentSearchProfiles.collectAsStateWithLifecycle()
    val recommendedProfiles by friendsViewModel.recommendedProfiles.collectAsStateWithLifecycle()
    val recommendationsLoading by friendsViewModel.recommendationsLoading.collectAsStateWithLifecycle()
    val actualUserSearchOpen by friendsViewModel.userSearchOpen.collectAsStateWithLifecycle()
    val userSearchOpen = !previewOverview && actualUserSearchOpen
    val searching by friendsViewModel.searching.collectAsStateWithLifecycle()
    val myProfile by friendsViewModel.myProfile.collectAsStateWithLifecycle()
    val profileChecked by friendsViewModel.profileChecked.collectAsStateWithLifecycle()
    val usernameDraft by friendsViewModel.usernameDraft.collectAsStateWithLifecycle()
    val usernameSaving by friendsViewModel.usernameSaving.collectAsStateWithLifecycle()
    val usernameError by friendsViewModel.usernameError.collectAsStateWithLifecycle()
    val following by friendsViewModel.following.collectAsStateWithLifecycle()
    val followingIds by friendsViewModel.followingIds.collectAsStateWithLifecycle()
    val nowPlaying by friendsViewModel.nowPlaying.collectAsStateWithLifecycle()
    val nowPlayingReactions by friendsViewModel.nowPlayingReactions.collectAsStateWithLifecycle()
    val socialFeed by friendsViewModel.socialFeed.collectAsStateWithLifecycle()
    val newSocialFeedSessions by friendsViewModel.newSocialFeedSessions.collectAsStateWithLifecycle()
    val actualNotificationsOpen by friendsViewModel.notificationsOpen.collectAsStateWithLifecycle()
    val notificationsOpen = !previewOverview && actualNotificationsOpen
    val statsNotifications by friendsViewModel.statsNotifications.collectAsStateWithLifecycle()
    val reactionBusySessions by friendsViewModel.reactionBusySessions.collectAsStateWithLifecycle()
    val loading by friendsViewModel.loading.collectAsStateWithLifecycle()
    val refreshing by friendsViewModel.refreshing.collectAsStateWithLifecycle()
    val busyUsers by friendsViewModel.busyUsers.collectAsStateWithLifecycle()
    val loadFailed by friendsViewModel.loadFailed.collectAsStateWithLifecycle()
    val followErrorToken by friendsViewModel.followErrorToken.collectAsStateWithLifecycle()
    val reactionErrorToken by friendsViewModel.reactionErrorToken.collectAsStateWithLifecycle()
    val statsPeriod by friendsViewModel.statsPeriod.collectAsStateWithLifecycle()
    val listeningStats by friendsViewModel.listeningStats.collectAsStateWithLifecycle()
    val personalGenres by friendsViewModel.personalGenreBreakdown.collectAsStateWithLifecycle()
    val personalLanguages by friendsViewModel.personalLanguageBreakdown.collectAsStateWithLifecycle()
    var personalMediaRanking by rememberSaveable { mutableStateOf<PersonalMediaRanking?>(null) }
    var communityRankingSheetCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var artistRankingSheetOpen by rememberSaveable { mutableStateOf(false) }
    val statsLoading by friendsViewModel.statsLoading.collectAsStateWithLifecycle()
    val statsLoadFailed by friendsViewModel.statsLoadFailed.collectAsStateWithLifecycle()
    val actualArtistRankingOpen by friendsViewModel.artistRankingOpen.collectAsStateWithLifecycle()
    // Artist rankings now open a sheet, not a replacement page.
    val artistRankingOpen = !previewOverview && actualArtistRankingOpen && !artistRankingSheetOpen
    val artistRanking by friendsViewModel.artistRanking.collectAsStateWithLifecycle()
    val artistRankingLoading by friendsViewModel.artistRankingLoading.collectAsStateWithLifecycle()
    val communityRankings by friendsViewModel.communityRankings.collectAsStateWithLifecycle()
    val communityLoading by friendsViewModel.communityLoading.collectAsStateWithLifecycle()
    val communityGenres by friendsViewModel.communityGenres.collectAsStateWithLifecycle()
    val communityLanguages by friendsViewModel.communityLanguages.collectAsStateWithLifecycle()
    val communityLoadFailed by friendsViewModel.communityLoadFailed.collectAsStateWithLifecycle()
    val communityPrevious by friendsViewModel.communityPreviousPlays.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(StatsDestination.GENERAL) }
    var liveListeningOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = liveListeningOpen && !previewOverview) {
        liveListeningOpen = false
    }

    // Catalogue revisions are incorporated on the next page entry. Switching
    // tabs never restarts the community fetch or blanks the current snapshot.
    LaunchedEffect(destination, statsPeriod, userSearchOpen, notificationsOpen, artistRankingOpen) {
        if (destination == StatsDestination.GENERAL && !userSearchOpen && !notificationsOpen && !artistRankingOpen) {
            friendsViewModel.loadCommunityRankings(statsPeriod)
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val searchListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val activeListState = if (userSearchOpen) searchListState else listState
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var shareStatsPreparing by remember { mutableStateOf(false) }
    var shareStatsPreviewOpen by remember { mutableStateOf(false) }
    var shareStatsBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showTopArtistRankingHint by remember { mutableStateOf(false) }
    // Keep the entrance cascade one-shot for each ranking open/period. Rows that
    // leave the LazyColumn viewport are often disposed; without an external
    // reveal registry they would replay their spring while scrolling back up,
    // causing the jank reported on the ranking page.
    val revealedArtistRankingRows = remember { mutableStateMapOf<String, Boolean>() }
    // Shared between the two Stats destinations and the nested dashboard cards.
    // Keep visible elements settled when a large dashboard LazyColumn item is recycled.
    val revealedStatsElements = remember { mutableStateMapOf<String, Boolean>() }
    val shareStatsPalette = StatsSharePalette(
        background = MaterialTheme.colorScheme.background.toArgb(),
        surface = MaterialTheme.colorScheme.surface.toArgb(),
        surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(),
        primary = MaterialTheme.colorScheme.primary.toArgb(),
        primaryContainer = MaterialTheme.colorScheme.primaryContainer.toArgb(),
        secondaryContainer = MaterialTheme.colorScheme.secondaryContainer.toArgb(),
        tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer.toArgb(),
        onBackground = MaterialTheme.colorScheme.onBackground.toArgb(),
        onSurface = MaterialTheme.colorScheme.onSurface.toArgb(),
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        outline = MaterialTheme.colorScheme.outlineVariant.toArgb(),
    )
    val followErrorMessage = stringResource(R.string.friends_follow_action_failed)
    val reactionErrorMessage = stringResource(R.string.friends_reaction_failed)
    val shareRenderFailedMessage = stringResource(R.string.stats_share_save_failed)
    val shareSavedMessage = stringResource(R.string.stats_share_saved)

    DisposableEffect(Unit) {
        friendsViewModel.onVisible()
        onDispose { friendsViewModel.onHidden() }
    }

    LaunchedEffect(followErrorToken) {
        if (followErrorToken > 0) snackbarHostState.showSnackbar(followErrorMessage)
    }

    LaunchedEffect(reactionErrorToken) {
        if (reactionErrorToken > 0) snackbarHostState.showSnackbar(reactionErrorMessage)
    }

    LaunchedEffect(userSearchOpen, myProfile?.username) {
        if (userSearchOpen && !myProfile?.username.isNullOrBlank()) {
            searchFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    LaunchedEffect(artistRankingOpen, notificationsOpen, statsPeriod) {
        if (isBackPreview) return@LaunchedEffect
        // Ranking/notifications replace the LazyColumn contents while reusing the
        // same state. Jump to the new list origin immediately so stale scroll
        // offsets cannot hide the first rows during the screen transition.
        if (artistRankingOpen || notificationsOpen) listState.scrollToItem(0)
        if (artistRankingOpen || notificationsOpen) showTopArtistRankingHint = false
        // A fresh open/period still gets the intended progressive cascade. Once
        // a row has appeared, scrolling it out and back in never restarts it.
        if (artistRankingOpen) revealedArtistRankingRows.clear()
    }

    // Discovery hint for the new clickable top-artist ranking card. FriendsScreen
    // is composed only while the Stats tab is actually open, so this is genuinely
    // the first Stats visit after the update rather than an app-start side effect.
    LaunchedEffect(listeningStats.topArtist != null, userSearchOpen, artistRankingOpen, notificationsOpen) {
        if (
            listeningStats.topArtist != null &&
            !userSearchOpen &&
            !artistRankingOpen &&
            !notificationsOpen &&
            AppSettings.shouldShowStatsArtistRankingHint()
        ) {
            showTopArtistRankingHint = true
            AppSettings.markStatsArtistRankingHintSeen()
            delay(6_500L)
            showTopArtistRankingHint = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefresh(
            refreshing = refreshing,
            onRefresh = friendsViewModel::refreshAll,
            state = pullState,
            indicatorTopPadding = contentPadding.calculateTopPadding(),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = activeListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                when {
                    !profileChecked -> item(key = "stats-initial-loading") {
                        LoadingRow(stringResource(R.string.stats_loading))
                    }

                    myProfile == null -> item(key = "stats-profile-error") {
                        StateCard(
                            title = stringResource(R.string.friends_profile_load_error_title),
                            message = stringResource(R.string.friends_profile_load_error_message),
                        )
                    }

                    userSearchOpen -> {
                        if (myProfile?.username.isNullOrBlank()) {
                            item(key = "stats-search-username-setup") {
                                UsernameSetupCard(
                                    username = usernameDraft,
                                    saving = usernameSaving,
                                    error = usernameError,
                                    onUsernameChange = friendsViewModel::onUsernameDraftChange,
                                    onContinue = friendsViewModel::saveUsername,
                                )
                            }
                        } else {
                            item(key = "stats-search-field") {
                                ExpressiveSearchField(
                                    query = query,
                                    searchHint = stringResource(R.string.friends_search_hint),
                                    onQueryChange = friendsViewModel::onQueryChange,
                                    onSearchSubmit = friendsViewModel::submitSearch,
                                    onSearchActivated = {},
                                    focusRequester = searchFocusRequester,
                                    clearContentDescription = stringResource(R.string.friends_clear_search),
                                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                                )
                            }

                            if (query.trim().removePrefix("@").isBlank()) {
                                if (followingIds.isEmpty()) {
                                    item(key = "stats-recommended-users-heading") {
                                        SectionHeading(stringResource(R.string.friends_recommended_profiles))
                                    }
                                    when {
                                        recommendationsLoading -> item(key = "stats-recommended-users-loading") {
                                            LoadingRow(stringResource(R.string.friends_loading_recommendations))
                                        }

                                        recommendedProfiles.isNotEmpty() -> {
                                            items(
                                                items = recommendedProfiles.take(4).chunked(2),
                                                key = { row -> "recommended-row:${row.joinToString("|") { it.id }}" },
                                            ) { row ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    row.forEach { profile ->
                                                        RecommendedProfileCard(
                                                            profile = profile,
                                                            following = profile.id in followingIds,
                                                            busy = profile.id in busyUsers,
                                                            onToggleFollow = { friendsViewModel.toggleFollow(profile) },
                                                            onOpenProfile = { onOpenProfile(profile) },
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                    }
                                                    if (row.size == 1) {
                                                        Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }

                                        else -> item(key = "stats-search-start") {
                                            StateCard(
                                                title = stringResource(R.string.friends_find_friends_title),
                                                message = stringResource(R.string.friends_find_friends_message),
                                            )
                                        }
                                    }
                                } else if (recentSearchProfiles.isNotEmpty()) {
                                    item(key = "stats-recent-users-heading") {
                                        SectionHeading(stringResource(R.string.stats_recent_users))
                                    }
                                    items(
                                        items = recentSearchProfiles.take(6),
                                        key = { "recent-user:${it.id}" },
                                    ) { profile ->
                                        SearchProfileRow(
                                            profile = profile,
                                            following = profile.id in followingIds,
                                            busy = profile.id in busyUsers,
                                            onToggleFollow = { friendsViewModel.toggleFollow(profile) },
                                            onOpenProfile = { onOpenProfile(profile) },
                                        )
                                    }
                                } else {
                                    item(key = "stats-search-start") {
                                        StateCard(
                                            title = stringResource(R.string.friends_find_friends_title),
                                            message = stringResource(R.string.friends_find_friends_message),
                                        )
                                    }
                                }
                            } else {
                                item(key = "stats-search-heading") {
                                    SectionHeading(stringResource(R.string.friends_search_results))
                                }
                                when {
                                    searching -> item(key = "stats-search-loading") {
                                        LoadingRow(stringResource(R.string.friends_searching))
                                    }
                                    searchResults.isEmpty() -> item(key = "stats-search-empty") {
                                        SimpleMessage(stringResource(R.string.friends_no_results))
                                    }
                                    else -> items(
                                        items = searchResults,
                                        key = { "search:${it.id}" },
                                    ) { profile ->
                                        SearchProfileRow(
                                            profile = profile,
                                            following = profile.id in followingIds,
                                            busy = profile.id in busyUsers,
                                            onToggleFollow = { friendsViewModel.toggleFollow(profile) },
                                            onOpenProfile = { onOpenProfile(profile) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    notificationsOpen -> {
                        if (statsNotifications.isEmpty()) {
                            item(key = "stats-notifications-empty") {
                                StateCard(
                                    title = stringResource(R.string.stats_notifications_title),
                                    message = stringResource(R.string.stats_notifications_empty),
                                )
                            }
                        } else {
                            items(
                                items = statsNotifications,
                                key = { "notification:${it.stableKey}" },
                            ) { notification ->
                                when (notification) {
                                    is ReactionNotification -> ReactionNotificationRow(
                                        notification = notification,
                                        onOpenProfile = {
                                            notification.reactor?.let(onOpenProfile)
                                        },
                                        modifier = Modifier.animateItem(),
                                    )

                                    is FollowNotification -> FollowNotificationRow(
                                        notification = notification,
                                        onOpenProfile = {
                                            notification.follower?.let(onOpenProfile)
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }

                    artistRankingOpen -> {
                        if (artistRanking.isEmpty()) {
                            item(key = "artist-ranking-empty") {
                                if (artistRankingLoading) {
                                    LoadingRow(stringResource(R.string.stats_loading))
                                } else {
                                    StateCard(
                                        title = stringResource(R.string.stats_artist_ranking_title),
                                        message = stringResource(R.string.stats_artist_ranking_no_data),
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = artistRanking,
                                // Period is part of visual identity. Reusing the same
                                // artist key across Week/Month/etc can visually preserve
                                // the previous period row in the new slot.
                                key = { _, entry -> "artist-ranking:${statsPeriod.name}:${entry.stableKey}" },
                            ) { index, entry ->
                                val rowKey = "artist-ranking:${statsPeriod.name}:${entry.stableKey}"
                                ArtistRankingEntrance(
                                    index = index,
                                    animationKey = "${statsPeriod.name}:$index:${entry.stableKey}",
                                    alreadyRevealed = revealedArtistRankingRows[rowKey] == true,
                                    onRevealed = { revealedArtistRankingRows[rowKey] = true },
                                ) {
                                    ArtistRankingRow(
                                        entry = entry,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        if (!liveListeningOpen) {
                            item(key = "stats-destination-selector") {
                                StatsDestinationSelector(
                                    modifier = Modifier.statsCascadeEnter("stats:destination-selector", 0, revealedStatsElements),
                                    destination = destination,
                                    onSelect = { next ->
                                        if (destination != next) {
                                            destination = next
                                            scope.launch { listState.scrollToItem(0) }
                                        }
                                    },
                                )
                            }
                        }
                        if (!liveListeningOpen && destination == StatsDestination.GENERAL) {
                            item(key = "stats-community-dashboard") {
                                CommunityStatsDashboard(
                                    rankings = communityRankings,
                                    previous = communityPrevious,
                                    genres = communityGenres,
                                    languages = communityLanguages,
                                    loading = communityLoading,
                                    failed = communityLoadFailed,
                                    onRetry = { friendsViewModel.loadCommunityRankings(statsPeriod, force = true) },
                                    onMore = { category -> communityRankingSheetCategory = category },
                                    period = statsPeriod,
                                    revealed = revealedStatsElements,
                                )
                            }
                        } else {
                        if (!liveListeningOpen) {
                        item(key = "stats-dashboard") {
                            StatsDashboard(
                                stats = listeningStats,
                                period = statsPeriod,
                                loading = statsLoading,
                                loadFailed = statsLoadFailed,
                                onPeriodSelected = friendsViewModel::setStatsPeriod,
                                onTopArtistClick = {
                                    artistRankingSheetOpen = true
                                    friendsViewModel.openArtistRanking()
                                },
                                showTopArtistRankingHint = showTopArtistRankingHint,
                                artistPreview = remember(statsPeriod, listeningStats) {
                                    friendsViewModel.personalTopArtists(statsPeriod)
                                },
                                previousArtistPositions = remember(statsPeriod, listeningStats) {
                                    friendsViewModel.personalPreviousRankingPositions(null, statsPeriod)
                                },
                                genreShares = personalGenres,
                                languageShares = personalLanguages,
                                listeningHours = remember(statsPeriod, listeningStats) {
                                    friendsViewModel.personalListeningHours(statsPeriod)
                                },
                                onMediaRanking = { personalMediaRanking = it },
                                revealed = revealedStatsElements,
                            )
                        }

                        item(key = "stats-live-preview") {
                            LiveListeningOverviewCard(
                                modifier = Modifier.statsCascadeEnter("personal:${statsPeriod.name}:live", 9, revealedStatsElements),
                                people = following.filter { profile -> nowPlaying[profile.id]?.isFresh() == true },
                                onOpen = {
                                    liveListeningOpen = true
                                    scope.launch { listState.scrollToItem(0) }
                                },
                            )
                        }

                        item(key = "stats-share-button") {
                            ShareStatsButton(
                                modifier = Modifier.statsCascadeEnter("personal:${statsPeriod.name}:share", 10, revealedStatsElements),
                                onClick = {
                                    if (!shareStatsPreparing) {
                                        val period = statsPeriod
                                        shareStatsPreparing = true
                                        shareStatsPreviewOpen = false
                                        shareStatsBitmap = null
                                        scope.launch {
                                            runCatching {
                                                val snapshot = friendsViewModel.statsShareSnapshot(period)
                                                StatsShareStoryRenderer.render(
                                                    context = context.applicationContext,
                                                    snapshot = snapshot,
                                                    palette = shareStatsPalette,
                                                )
                                            }.onSuccess { bitmap ->
                                                shareStatsBitmap = bitmap
                                                shareStatsPreviewOpen = true
                                            }.onFailure {
                                                snackbarHostState.showSnackbar(shareRenderFailedMessage)
                                            }
                                            shareStatsPreparing = false
                                        }
                                    }
                                },
                            )
                        }

                        } // End of personal dashboard; social activity is on its own screen.
                        if (liveListeningOpen) {
                        if (myProfile?.username.isNullOrBlank()) {
                            item(key = "stats-username-setup") {
                                UsernameSetupCard(
                                    username = usernameDraft,
                                    saving = usernameSaving,
                                    error = usernameError,
                                    onUsernameChange = friendsViewModel::onUsernameDraftChange,
                                    onContinue = friendsViewModel::saveUsername,
                                )
                            }
                        } else if (loading && following.isEmpty()) {
                            item(key = "stats-social-loading") {
                                LoadingRow(stringResource(R.string.friends_loading_following))
                            }
                        } else if (loadFailed && following.isEmpty()) {
                            item(key = "stats-social-load-error") {
                                StateCard(
                                    title = stringResource(R.string.friends_load_error_title),
                                    message = stringResource(R.string.friends_load_error_message),
                                )
                            }
                        } else if (following.isEmpty()) {
                            item(key = "stats-empty-following") {
                                StateCard(
                                    title = stringResource(R.string.friends_find_friends_title),
                                    message = stringResource(R.string.friends_find_friends_message),
                                    actionLabel = stringResource(R.string.friends_find_friends_action),
                                    onAction = friendsViewModel::openUserSearch,
                                )
                            }
                        } else {
                            val profilesById = following.associateBy { it.id }
                            if (socialFeed.isEmpty()) {
                                item(key = "stats-nobody-listening") {
                                    StateCard(
                                        title = stringResource(R.string.friends_nobody_listening_title),
                                        message = stringResource(R.string.friends_recent_listening_empty_message),
                                    )
                                }
                            } else {
                                items(
                                    items = socialFeed,
                                    key = { activity -> "feed:${activity.reactionSessionKey()}" },
                                ) { activity ->
                                    val profile = profilesById[activity.userId]
                                    if (profile != null) {
                                        val live = nowPlaying[activity.userId]
                                        val isLive = live?.isFresh() == true &&
                                            live.matchesListeningActivity(activity)
                                        val session = activity.asFriendNowPlaying(live.takeIf { isLive })
                                        val sessionKey = activity.reactionSessionKey()
                                        FriendNowPlayingCard(
                                            profile = profile,
                                            nowPlaying = session,
                                            activity = activity,
                                            isLive = isLive,
                                            reactions = nowPlayingReactions[sessionKey].orEmpty(),
                                            currentUserId = myProfile?.id,
                                            reactionBusy = sessionKey in reactionBusySessions,
                                            animateEntrance = sessionKey in newSocialFeedSessions,
                                            onReact = { reaction ->
                                                friendsViewModel.reactToNowPlaying(session, reaction)
                                            },
                                            onEntranceConsumed = {
                                                friendsViewModel.consumeSocialFeedEntrance(sessionKey)
                                            },
                                            onOpenProfile = { onOpenProfile(profile) },
                                            // Recede at the pinned top edge; preserve genuine
                                            // insertion/placement animations independently.
                                            modifier = Modifier.statsTopExit().animateItem(
                                                placementSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessLow,
                                                ),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        } // Ao vivo listening page
                        } // Pessoal and Ao vivo
                    }
                }
            }
        }

        communityRankingSheetCategory?.let { category ->
            val currentEntries = communityRankings.filter { it.category == category }
            val ordered = if (category == "rated_artist") {
                currentEntries.sortedWith(
                    compareByDescending<CommunityRankEntry> { it.score ?: 0.0 }
                        .thenByDescending { it.ratingsCount },
                )
            } else currentEntries.sortedByDescending { it.plays }
            CommunityRankingSheet(
                entries = ordered.take(30),
                showRatings = category == "rated_artist",
                circularArtwork = category == "artist" || category == "rated_artist",
                previous = communityPrevious,
                category = category,
                period = statsPeriod,
                onDismiss = { communityRankingSheetCategory = null },
            )
        }

        if (artistRankingSheetOpen) {
            PersonalMediaRankingSheet(
                kind = null,
                period = statsPeriod,
                leaders = artistRanking.take(30).map { it.leader },
                previousPositions = remember(statsPeriod, listeningStats) {
                    friendsViewModel.personalPreviousRankingPositions(null, statsPeriod)
                },
                loading = artistRankingLoading,
                onDismiss = {
                    artistRankingSheetOpen = false
                    friendsViewModel.closeArtistRanking()
                },
            )
        }

        personalMediaRanking?.let { kind ->
            PersonalMediaRankingSheet(
                kind = kind,
                period = statsPeriod,
                leaders = remember(kind, statsPeriod, listeningStats) {
                    friendsViewModel.personalMediaRanking(kind, statsPeriod)
                },
                previousPositions = remember(kind, statsPeriod, listeningStats) {
                    friendsViewModel.personalPreviousRankingPositions(kind, statsPeriod)
                },
                onDismiss = { personalMediaRanking = null },
            )
        }

        if (shareStatsPreparing) {
            StatsSharePreparingDialog()
        }

        if (shareStatsPreviewOpen) {
            shareStatsBitmap?.let { bitmap ->
                StatsSharePreviewDialog(
                    bitmap = bitmap,
                    onDismiss = { shareStatsPreviewOpen = false },
                    onSave = {
                        scope.launch {
                            val saved = StatsShareStoryRenderer.saveToGallery(context, bitmap) != null
                            snackbarHostState.showSnackbar(
                                if (saved) shareSavedMessage else shareRenderFailedMessage,
                            )
                        }
                    },
                    onShare = {
                        scope.launch {
                            runCatching { StatsShareStoryRenderer.share(context, bitmap) }
                                .onFailure { snackbarHostState.showSnackbar(shareRenderFailedMessage) }
                        }
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 88.dp),
        )
    }
}


/** Comparison for a ranked song/album/artist against the preceding equal-length window. */
@Composable
private fun CommunityChange(
    entry: CommunityRankEntry,
    previous: Map<String, Long>?,
    lightText: Boolean = false,
) {
    // A metric always gets its own opaque container, including over artwork.
    @Suppress("UNUSED_VARIABLE") val keepCallCompatibility = lightText
    val old = previous?.get(communityRankKey(entry.category, entry.title, entry.subtitle))
    val up = old != null && entry.plays > old
    val down = old != null && entry.plays < old
    val label = when {
        old == null -> "—"
        old == 0L && entry.plays > 0 -> stringResource(R.string.stats_change_new)
        old == 0L -> "0%"
        else -> String.format(Locale.getDefault(), "%+.0f%%", (entry.plays - old).toDouble() / old * 100.0)
    }
    StatsMetricPill(
        compact = true,
        tone = when {
            up -> StatsMetricTone.POSITIVE
            down -> StatsMetricTone.NEGATIVE
            else -> StatsMetricTone.NEUTRAL
        },
    ) { ink ->
        if (up) Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        if (down) Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        Text(label, color = ink, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PersonalRankChange(
    category: String,
    title: String,
    subtitle: String?,
    position: Int,
    previous: Map<String, Int>,
) {
    val old = previous[communityRankKey(category, title, subtitle)]
    val delta = old?.minus(position) ?: 0
    val label = when {
        old == null -> stringResource(R.string.stats_change_new)
        delta == 0 -> "—"
        else -> "${kotlin.math.abs(delta)}"
    }
    StatsMetricPill(
        compact = true,
        tone = when {
            delta > 0 -> StatsMetricTone.POSITIVE
            delta < 0 -> StatsMetricTone.NEGATIVE
            else -> StatsMetricTone.NEUTRAL
        },
    ) { ink ->
        if (delta > 0) Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        if (delta < 0) Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StatsRankingPeriodLabel(period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> stringResource(R.string.stats_period_week)
    StatsPeriod.MONTH -> stringResource(R.string.stats_period_month)
    StatsPeriod.QUARTER -> stringResource(R.string.stats_period_quarter)
    StatsPeriod.SEMESTER -> stringResource(R.string.stats_period_semester)
    StatsPeriod.YEAR -> stringResource(R.string.stats_period_year)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalMediaRankingSheet(
    kind: PersonalMediaRanking?,
    period: StatsPeriod,
    leaders: List<StatsLeader>,
    previousPositions: Map<String, Int>,
    onDismiss: () -> Unit,
    loading: Boolean = false,
) {
    RegisterSheetBackdrop()
    val listHeightLimit = (LocalConfiguration.current.screenHeightDp.dp * 0.67f).coerceIn(180.dp, 620.dp)
    val rankingTitle = when (kind) {
        PersonalMediaRanking.SONGS -> stringResource(R.string.stats_general_top_songs)
        PersonalMediaRanking.ALBUMS -> stringResource(R.string.orb_sheet_stats_albums)
        null -> stringResource(R.string.stats_general_top_artists)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        floatingTitle = rankingTitle,
        floatingSubtitle = StatsRankingPeriodLabel(period),
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        // Paint the Android navigation inset as part of this very same glass
        // surface, not as a separate tonal footer below it.
        navigationBarInsetInContent = true,
        // The handle belongs inside our glass sheet, never above it.
        dragHandle = null,
    ) {
        val scheme = MaterialTheme.colorScheme
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .orbGlassSurface(
                    orbOverlayGlassBaseColor(),
                    cornerRadius = 30.dp,
                    overlayOpacity = 0.52f,
                    lightweightSheetMaterial = true,
                    sheetTintMode = com.music.orb.ui.components.OrbSheetTintMode.BACKDROP,
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 34.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.60f)),
                )
            }
            if (loading && leaders.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (leaders.isEmpty()) {
                Text(
                    stringResource(R.string.stats_no_data_message),
                    modifier = Modifier.padding(vertical = 32.dp),
                    color = scheme.onSurfaceVariant,
                )
            } else {
                val sheetListState = rememberLazyListState()
                LazyColumn(
                    state = sheetListState,
                    modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth().heightIn(max = listHeightLimit),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 34.dp),
                ) {
                    itemsIndexed(leaders, key = { index, item -> "${kind?.name ?: "ARTISTS"}:$index:${item.title}" }) { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().statsSheetRowExit(sheetListState, index).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatsRankNumber(index + 1)
                            StatsCardArtwork(
                                url = item.artworkUrl,
                                title = item.title,
                                subtitle = item.subtitle,
                                kind = when (kind) {
                                    PersonalMediaRanking.SONGS -> StatsArtworkKind.SONG
                                    PersonalMediaRanking.ALBUMS -> StatsArtworkKind.ALBUM
                                    null -> StatsArtworkKind.ARTIST
                                },
                                contentColor = scheme.onSurface,
                                modifier = Modifier.size(50.dp)
                                    .then(if (kind == null) Modifier.clip(CircleShape) else Modifier),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                                item.subtitle?.let {
                                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                            PersonalRankChange(
                                category = when (kind) {
                                    PersonalMediaRanking.SONGS -> "song"
                                    PersonalMediaRanking.ALBUMS -> "album"
                                    null -> "artist"
                                },
                                title = item.title,
                                subtitle = if (kind == null) null else item.subtitle,
                                position = index + 1,
                                previous = previousPositions,
                            )
                        }
                    }
                }
            }
            // Extend the SAME glass paint to the bottom of the dialog window.
            // The sheet no longer ends above Android's gesture/navigation bar.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars),
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityRankingSheet(
    entries: List<CommunityRankEntry>,
    category: String,
    period: StatsPeriod,
    showRatings: Boolean,
    circularArtwork: Boolean,
    previous: Map<String, Long>?,
    onDismiss: () -> Unit,
) {
    RegisterSheetBackdrop()
    val listHeightLimit = (LocalConfiguration.current.screenHeightDp.dp * 0.67f).coerceIn(180.dp, 620.dp)
    val rankingTitle = when (category) {
        "song" -> stringResource(R.string.stats_general_top_songs)
        "album" -> stringResource(R.string.orb_sheet_stats_albums)
        "artist" -> stringResource(R.string.stats_general_top_artists)
        else -> stringResource(R.string.stats_general_top_rated)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        floatingTitle = rankingTitle,
        // Community artist ratings are lifetime aggregates, not restricted to the
        // listening window selected for song/album/artist play rankings.
        floatingSubtitle = if (category == "rated_artist") null else StatsRankingPeriodLabel(period),
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        navigationBarInsetInContent = true,
        dragHandle = null,
    ) {
        val scheme = MaterialTheme.colorScheme
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .orbGlassSurface(
                    orbOverlayGlassBaseColor(),
                    cornerRadius = 30.dp,
                    overlayOpacity = 0.52f,
                    lightweightSheetMaterial = true,
                    sheetTintMode = com.music.orb.ui.components.OrbSheetTintMode.BACKDROP,
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 34.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.60f)),
                )
            }
            if (entries.isEmpty()) {
                Text(stringResource(R.string.stats_general_no_data),
                    modifier = Modifier.padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
            } else {
                val state = rememberLazyListState()
                LazyColumn(
                    state = state,
                    modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth().heightIn(max = listHeightLimit),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 34.dp),
                ) {
                    itemsIndexed(entries.take(30), key = { index, item ->
                        "community:${item.category}:$index:${item.title}"
                    }) { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .statsSheetRowExit(state, index)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatsRankNumber(index + 1)
                            if (!entry.artworkUrl.isNullOrBlank()) {
                                StatsRecoveringArtwork(
                                    url = entry.artworkUrl,
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    kind = if (entry.category.contains("artist")) StatsArtworkKind.ARTIST
                                        else if (entry.category.contains("album")) StatsArtworkKind.ALBUM
                                        else StatsArtworkKind.SONG,
                                    requestedPx = ROW_ART_PX,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(50.dp)
                                        .clip(if (circularArtwork) CircleShape else RoundedCornerShape(12.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(50.dp)
                                        .clip(if (circularArtwork) CircleShape else RoundedCornerShape(12.dp))
                                        .background(scheme.tertiaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (circularArtwork) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                                        contentDescription = null, tint = scheme.onTertiaryContainer,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                                entry.subtitle?.takeIf(String::isNotBlank)?.let {
                                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (showRatings) {
                                    StatsMetricPill { ink ->
                                        Icon(Icons.Rounded.Star, contentDescription = null,
                                            tint = ink, modifier = Modifier.size(16.dp))
                                        Text(String.format(Locale.getDefault(), "%.1f", entry.score ?: 0.0),
                                            color = ink, fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                } else {
                                    CommunityChange(entry, previous)
                                }
                            }
                        }
                    }
                }
            }
            // Extend the SAME glass paint to the bottom of the dialog window.
            // The sheet no longer ends above Android's gesture/navigation bar.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars),
            )
        }
    }
}


@Composable
private fun StatsSharePreparingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f)),
            contentAlignment = Alignment.Center,
        ) {
            val glassColor = orbOverlayGlassBaseColor()
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.Transparent,
                modifier = Modifier.orbGlassSurface(glassColor, cornerRadius = 28.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.stats_share_preparing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsSharePreviewDialog(
    bitmap: android.graphics.Bitmap,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    RegisterSheetBackdrop()
    val previewGlassColor = orbOverlayGlassBaseColor()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        floatingTitle = stringResource(R.string.stats_share_preview_title),
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.10f),
        navigationBarInsetInContent = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .orbGlassSurface(
                    previewGlassColor,
                    cornerRadius = 30.dp,
                    backdropBlur = 56.dp,
                    overlayStrength = true,
                    overlayOpacity = 0.48f,
                    lightweightSheetMaterial = true,
                    sheetTintMode = com.music.orb.ui.components.OrbSheetTintMode.BACKDROP,
                )
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .size(width = 34.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)),
                )
            }
            Text(
                text = stringResource(R.string.stats_share_preview_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.stats_share_preview_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(stringResource(R.string.stats_share_save), maxLines = 1)
                }
                Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.stats_share_share), maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}


@Composable
private fun ShareStatsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.stats_share_button),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}


/** A single travelling material pill — spring motion shared with the bottom navigation. */
@Composable
private fun StatsDestinationSelector(
    destination: StatsDestination,
    onSelect: (StatsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val gap = 5.dp
    val selectedIndex = if (destination == StatsDestination.GENERAL) 0 else 1
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
        modifier = Modifier
            .widthIn(max = 780.dp)
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .clip(RoundedCornerShape(32.dp))
            .orbGlassSurface(pageColor = orbOverlayGlassBaseColor(), cornerRadius = 32.dp)
            .padding(5.dp),
    ) {
        val slotWidth = (maxWidth - gap) / 2f
        val indicatorOffset by animateDpAsState(
            targetValue = (slotWidth + gap) * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "Stats travelling spring indicator",
        )
        val indicatorShape = RoundedCornerShape(27.dp)
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(slotWidth)
                .height(46.dp)
                .clip(indicatorShape)
                .background(scheme.primaryContainer),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(
                Triple(StatsDestination.GENERAL, R.string.stats_destination_general, Icons.Rounded.Public),
                Triple(StatsDestination.MY_NETWORK, R.string.stats_destination_network, Icons.Rounded.Person),
            ).forEach { (item, label, icon) ->
                val selected = destination == item
                val tint by animateColorAsState(
                    targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    animationSpec = tween(180), label = "Stats tab content color",
                )
                Row(
                    modifier = Modifier
                        .width(slotWidth)
                        .height(46.dp)
                        .clip(indicatorShape)
                        .clickable {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(item)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(label),
                        color = tint,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
}

@Composable
private fun CommunityStatsDashboard(
    period: StatsPeriod,
    revealed: MutableMap<String, Boolean>,
    rankings: List<CommunityRankEntry>,
    previous: Map<String, Long>?,
    genres: List<Pair<String, Float>>,
    languages: List<Pair<String, Float>>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onMore: (String) -> Unit,
) {
    val songs = remember(rankings) { rankings.filter { it.category == "song" }.sortedByDescending { it.plays } }
    val artists = remember(rankings) { rankings.filter { it.category == "artist" }.sortedByDescending { it.plays } }
    val albums = remember(rankings) { rankings.filter { it.category == "album" }.sortedByDescending { it.plays } }
    val ratedArtists = remember(rankings) {
        rankings.filter { it.category == "rated_artist" }
            .sortedWith(compareByDescending<CommunityRankEntry> { it.score ?: 0.0 }.thenByDescending { it.ratingsCount })
    }
    val entrance = "general:${period.name}:"
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tablet = maxWidth >= 840.dp
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .widthIn(max = 1360.dp)
                .fillMaxWidth()
                .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loading && rankings.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp))
                }
            } else if (failed && rankings.isEmpty()) {
                StateCard(
                    title = stringResource(R.string.stats_general_unavailable),
                    message = stringResource(R.string.stats_general_unavailable_message),
                    actionLabel = stringResource(R.string.stats_general_retry),
                    onAction = onRetry,
                )
            } else {
                if (tablet) {
                    // Tablet landscape: two genuine content columns rather than
                    // stretching a phone-sized square across the whole window.
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CommunityTopSongsCard(
                            title = stringResource(R.string.stats_general_top_songs),
                            detail = stringResource(R.string.stats_general_top_songs_subtitle),
                            entries = songs,
                            previous = previous,
                            onMore = { onMore("song") },
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "songs", 0, revealed),
                            cardHeight = 268.dp,
                            revealed = revealed,
                            entrancePrefix = entrance + "songs:",
                        )
                        CommunityFeaturedRankingCard(
                            title = stringResource(R.string.stats_general_top_artists),
                            entries = artists,
                            previous = previous,
                            onMore = { onMore("artist") },
                            isArtistRanking = true,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "artists", 3, revealed),
                            cardHeight = 268.dp,
                            revealed = revealed,
                            entrancePrefix = entrance + "artists:",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CommunityBreakdownSquareCard(
                            title = stringResource(R.string.stats_general_genres),
                            items = genres,
                            aspect = 1.7f,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "genres", 1, revealed),
                        )
                        CommunityBreakdownSquareCard(
                            title = stringResource(R.string.stats_general_languages),
                            items = languages,
                            aspect = 1.7f,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "languages", 2, revealed),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CommunityRankingSection(
                            title = stringResource(R.string.stats_general_top_albums),
                            detail = stringResource(R.string.stats_general_top_albums_subtitle),
                            entries = albums,
                            previous = previous,
                            onMore = { onMore("album") },
                            showRatings = false,
                            showArtwork = true,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "albums", 4, revealed),
                        )
                        CommunityRankingSection(
                            title = stringResource(R.string.stats_general_top_rated),
                            detail = stringResource(R.string.stats_general_top_rated_subtitle),
                            entries = ratedArtists,
                            previous = previous,
                            onMore = { onMore("rated_artist") },
                            showRatings = true,
                            showArtwork = true,
                            preferCircularArtwork = true,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "rated", 5, revealed),
                        )
                    }
                } else {
                    CommunityTopSongsCard(
                        modifier = Modifier.statsCascadeEnter(entrance + "songs", 0, revealed),
                        title = stringResource(R.string.stats_general_top_songs),
                        detail = stringResource(R.string.stats_general_top_songs_subtitle),
                        entries = songs,
                        previous = previous,
                        onMore = { onMore("song") },
                        revealed = revealed,
                        entrancePrefix = entrance + "songs:",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CommunityBreakdownSquareCard(
                            title = stringResource(R.string.stats_general_genres),
                            items = genres,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "genres", 1, revealed),
                        )
                        CommunityBreakdownSquareCard(
                            title = stringResource(R.string.stats_general_languages),
                            items = languages,
                            modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "languages", 2, revealed),
                        )
                    }
                    CommunityFeaturedRankingCard(
                        modifier = Modifier.statsCascadeEnter(entrance + "artists", 3, revealed),
                        title = stringResource(R.string.stats_general_top_artists),
                        entries = artists,
                        previous = previous,
                        onMore = { onMore("artist") },
                        isArtistRanking = true,
                        revealed = revealed,
                        entrancePrefix = entrance + "artists:",
                    )
                    CommunityRankingSection(
                        modifier = Modifier.statsCascadeEnter(entrance + "albums", 4, revealed),
                        title = stringResource(R.string.stats_general_top_albums),
                        detail = stringResource(R.string.stats_general_top_albums_subtitle),
                        entries = albums,
                        previous = previous,
                        onMore = { onMore("album") },
                        showRatings = false,
                        showArtwork = true,
                    )
                    CommunityRankingSection(
                        modifier = Modifier.statsCascadeEnter(entrance + "rated", 5, revealed),
                        title = stringResource(R.string.stats_general_top_rated),
                        detail = stringResource(R.string.stats_general_top_rated_subtitle),
                        entries = ratedArtists,
                        previous = previous,
                        onMore = { onMore("rated_artist") },
                        showRatings = true,
                        showArtwork = true,
                        preferCircularArtwork = true,
                    )
                }
                Text(
                    stringResource(R.string.stats_general_privacy_note),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        .statsCascadeEnter(entrance + "privacy", 6, revealed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommunityBreakdownSquareCard(
    title: String,
    items: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    aspect: Float = 1f,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        scheme.primary,
        scheme.tertiary,
        scheme.secondary,
        scheme.outline,
    )
    // Display three clearly readable categories plus the actual remainder;
    // never confuse the five leading entries with 100% of all plays.
    val total = items.sumOf { it.second.toDouble() }.coerceAtMost(100.0).toFloat()
    val head = items.take(3)
    val remainder = (100f - head.sumOf { it.second.toDouble() }.toFloat()).coerceIn(0f, 100f)
    val portions = head + if (remainder > 0.5f) {
        listOf(stringResource(R.string.stats_general_other) to remainder)
    } else emptyList()
    val glassColor = orbOverlayGlassBaseColor()
    val chartReveal = remember(items) { Animatable(0f) }
    LaunchedEffect(items) {
        chartReveal.animateTo(1f, animationSpec = tween(durationMillis = 650))
    }
    Column(
        modifier = modifier
            .aspectRatio(aspect)
            .statsTopExit()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 18.dp, bottomEnd = 28.dp, bottomStart = 18.dp))
            .orbGlassSurface(glassColor, cornerRadius = 26.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            color = scheme.onSurface,
        )
        if (items.isEmpty() || total <= 0f) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.stats_general_breakdown_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.weight(0.9f).aspectRatio(1f)) {
                    val strokeWidth = size.minDimension * .24f
                    val diameter = size.minDimension - strokeWidth
                    var startAngle = -90f
                    portions.forEachIndexed { index, (_, percent) ->
                        val sweep = 360f * (percent.coerceIn(0f, 100f) / 100f) * chartReveal.value
                        drawArc(
                            color = colors[index % colors.size],
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                            size = androidx.compose.ui.geometry.Size(diameter, diameter),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                        )
                        startAngle += sweep
                    }
                }
                Column(
                    modifier = Modifier.weight(1.35f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    portions.forEachIndexed { index, (name, percent) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors[index % colors.size]),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurface,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                String.format(Locale.getDefault(), "%.0f%%", percent),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val COMMUNITY_EXPLICIT_SUFFIX = Regex(
    """(?i)\s*(?:[\(\[\-–—]\s*)?explicit(?:\s+version)?(?:\s*[\)\]])?\s*$""",
)

private fun String.communityDisplayTitle(): String {
    val cleaned = COMMUNITY_EXPLICIT_SUFFIX.replace(trim(), "").trim()
    return cleaned.ifBlank { trim() }
}

private fun String.hasCommunityExplicitMarker(): Boolean = COMMUNITY_EXPLICIT_SUFFIX.containsMatchIn(this)

@Composable
private fun CommunitySongTitle(
    rawTitle: String,
    color: Color,
    pageColor: Color,
    style: androidx.compose.ui.text.TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val explicit = rawTitle.hasCommunityExplicitMarker()
    val displayTitle = rawTitle.communityDisplayTitle()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayTitle,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = style,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        if (explicit) {
            ExplicitBadgeSeal(
                pageColor = pageColor,
                size = 14.5.dp,
                contentColorOverride = color,
            )
        }
    }
}

/**
 * Shared full-bleed layout for the community's most-played songs and artists.
 * It intentionally follows PersonalArtistRankingCard's geometry: the same
 * rounded glass frame, 252/268 dp hero and identical row/footer paddings.
 */
@Composable
private fun CommunityFeaturedRankingCard(
    title: String,
    entries: List<CommunityRankEntry>,
    previous: Map<String, Long>?,
    onMore: () -> Unit,
    isArtistRanking: Boolean,
    modifier: Modifier = Modifier,
    cardHeight: Dp = 252.dp,
    revealed: MutableMap<String, Boolean>,
    entrancePrefix: String,
) {
    val scheme = MaterialTheme.colorScheme
    val glassColor = orbOverlayGlassBaseColor()
    val visibleEntries = entries.take(5)
    val hero = visibleEntries.firstOrNull()
    // Geometry is supplied by the same breakpoint as PersonalArtistRankingCard.
    val heroHeight = cardHeight
    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.88f),
        offset = Offset(0f, 2f),
        blurRadius = 7f,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statsTopExit()
            .clip(RoundedCornerShape(32.dp))
            .orbGlassSurface(glassColor, cornerRadius = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(RoundedCornerShape(34.dp))
                .background(scheme.surfaceContainerHighest)
                .clickable(enabled = hero != null, onClick = onMore),
        ) {
            if (!hero?.artworkUrl.isNullOrBlank()) {
                StatsRecoveringArtwork(
                    url = hero?.artworkUrl,
                    title = hero?.title,
                    subtitle = hero?.subtitle,
                    kind = if (isArtistRanking) StatsArtworkKind.ARTIST else StatsArtworkKind.SONG,
                    requestedPx = TOP_ARTIST_ART_PX,
                    contentDescription = hero?.title,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.matchParentSize()
                        .statsCascadeEnter(entrancePrefix + "image", 1, revealed),
                )
            } else {
                Box(
                    modifier = Modifier.matchParentSize()
                        .statsCascadeEnter(entrancePrefix + "image", 1, revealed)
                        .background(scheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isArtistRanking) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = scheme.onTertiaryContainer,
                        modifier = Modifier.size(54.dp),
                    )
                }
            }
            // Exactly the same bottom-shadow geometry as TopArtistFeatureCard.
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.46f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.14f),
                                Color.Black.copy(alpha = 0.70f),
                            ),
                        ),
                    ),
            )
            Row(
                modifier = Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .statsCascadeEnter(entrancePrefix + "text", 2, revealed)
                    .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge.copy(shadow = textShadow),
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 2,
                    )
                    if (isArtistRanking) {
                        Text(
                            text = hero?.title ?: stringResource(R.string.stats_general_no_data),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold, shadow = textShadow,
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        hero?.let { song ->
                            CommunitySongTitle(
                                rawTitle = song.title,
                                color = Color.White,
                                pageColor = Color.Black.copy(alpha = 0.28f),
                                style = MaterialTheme.typography.headlineSmall.copy(shadow = textShadow),
                                maxLines = 2,
                            )
                        }
                    }
                    if (!isArtistRanking) {
                        hero?.subtitle?.takeIf(String::isNotBlank)?.let { artist ->
                            Text(
                                artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow),
                                color = Color.White.copy(alpha = 0.90f),
                            )
                        }
                    }
                    hero?.let { featured ->
                        Text(
                            stringResource(
                                R.string.stats_general_leader_summary,
                                featured.plays,
                                featured.listeners,
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow),
                            color = Color.White.copy(alpha = 0.88f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                hero?.let { CommunityChange(it, previous, lightText = true) }
            }
        }
        visibleEntries.drop(1).forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .height(62.dp)
                    .statsCascadeEnter(entrancePrefix + "row:$index", 3 + index, revealed)
                    .statsTopExit()
                    .clickable(onClick = onMore)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsRankNumber(index + 2)
                if (!entry.artworkUrl.isNullOrBlank()) {
                    StatsRecoveringArtwork(
                        url = entry.artworkUrl,
                        title = entry.title,
                        subtitle = entry.subtitle,
                        kind = if (isArtistRanking) StatsArtworkKind.ARTIST else StatsArtworkKind.SONG,
                        requestedPx = ROW_ART_PX,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp)
                            .clip(if (isArtistRanking) CircleShape else RoundedCornerShape(12.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .clip(if (isArtistRanking) CircleShape else RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isArtistRanking) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (isArtistRanking) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        CommunitySongTitle(
                            rawTitle = entry.title,
                            color = scheme.onSurface,
                            pageColor = glassColor,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        entry.subtitle?.takeIf(String::isNotBlank)?.let { artist ->
                            Text(
                                artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                CommunityChange(entry, previous)
            }
        }
        if (entries.size > 5) {
            Row(
                modifier = Modifier.align(Alignment.End)
                    .clickable(onClick = onMore)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.stats_general_show_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CommunityTopSongsCard(
    title: String,
    detail: String,
    entries: List<CommunityRankEntry>,
    previous: Map<String, Long>?,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    cardHeight: Dp = 252.dp,
    revealed: MutableMap<String, Boolean>,
    entrancePrefix: String,
) {
    @Suppress("UNUSED_VARIABLE") val retainSourceCompatibility = detail
    CommunityFeaturedRankingCard(
        title = title,
        entries = entries,
        previous = previous,
        onMore = onMore,
        isArtistRanking = false,
        modifier = modifier,
        cardHeight = cardHeight,
        revealed = revealed,
        entrancePrefix = entrancePrefix,
    )
}

@Composable
private fun CommunityRankingSection(
    title: String,
    detail: String,
    entries: List<CommunityRankEntry>,
    previous: Map<String, Long>?,
    showRatings: Boolean,
    showArtwork: Boolean,
    preferCircularArtwork: Boolean = false,
    featureFirstTrack: Boolean = false,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val glassColor = orbOverlayGlassBaseColor()
    val previewLimit = 5
    val visibleEntries = entries.take(previewLimit)
    Column(
        modifier
            .fillMaxWidth()
            .statsTopExit()
            .clip(RoundedCornerShape(30.dp))
            .orbGlassSurface(glassColor, cornerRadius = 30.dp)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        if (visibleEntries.isEmpty()) {
            Text(
                stringResource(R.string.stats_general_no_data),
                modifier = Modifier.padding(vertical = 20.dp),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val max = visibleEntries.maxOfOrNull { if (showRatings) it.ratingsCount else it.plays }
                ?.coerceAtLeast(1L) ?: 1L
            // The #1 track appears once, as the hero of this same ranking.
            // The remaining rows retain their actual positions (#2 through #30).
            if (featureFirstTrack && !showRatings) {
                val leader = visibleEntries.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 16.dp, bottomEnd = 28.dp, bottomStart = 16.dp))
                        .background(scheme.primaryContainer.copy(alpha = 0.42f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!leader.artworkUrl.isNullOrBlank()) {
                        StatsRecoveringArtwork(
                            url = leader.artworkUrl,
                            title = leader.title,
                            subtitle = leader.subtitle,
                            kind = StatsArtworkKind.SONG,
                            requestedPx = ROW_ART_PX,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(22.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(scheme.tertiaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = scheme.onTertiaryContainer,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(scheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "1",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.stats_general_top_track),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            leader.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                        )
                        leader.subtitle?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(
                                R.string.stats_general_leader_summary,
                                leader.plays,
                                leader.listeners,
                            ),
                            maxLines = 2,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            visibleEntries.drop(if (featureFirstTrack && !showRatings) 1 else 0)
                .forEachIndexed { index, entry ->
                val position = index + (if (featureFirstTrack && !showRatings) 2 else 1)
                Row(
                    modifier = Modifier.fillMaxWidth().statsTopExit().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatsRankNumber(position)
                    if (showArtwork && !entry.artworkUrl.isNullOrBlank()) {
                        StatsRecoveringArtwork(
                            url = entry.artworkUrl,
                            title = entry.title,
                            subtitle = entry.subtitle,
                            kind = if (preferCircularArtwork) StatsArtworkKind.ARTIST else StatsArtworkKind.SONG,
                            requestedPx = ROW_ART_PX,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(if (preferCircularArtwork) CircleShape else RoundedCornerShape(13.dp)),
                        )
                    } else if (showArtwork) {
                        Box(
                            Modifier.size(44.dp)
                                .clip(if (preferCircularArtwork) CircleShape else RoundedCornerShape(13.dp))
                                .background(scheme.tertiaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (preferCircularArtwork) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = scheme.onTertiaryContainer,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            maxLines = if (LocalConfiguration.current.screenWidthDp >= 840) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        entry.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                            Text(
                                subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        if (!showRatings) {
                            val progress = (entry.plays.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .padding(top = 5.dp)
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(scheme.surfaceContainerHighest),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(progress)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(scheme.primary),
                                )
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (showRatings) {
                            StatsMetricPill { ink ->
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = ink,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    String.format(Locale.getDefault(), "%.1f", entry.score ?: 0.0),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = ink,
                                )
                            }
                        } else {
                            CommunityChange(entry, previous)
                        }
                    }
                }
            }
            if (entries.size > previewLimit) {
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onMore)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.stats_general_show_more),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsDashboard(
    revealed: MutableMap<String, Boolean>,
    stats: ListeningStats,
    period: StatsPeriod,
    loading: Boolean,
    loadFailed: Boolean,
    onPeriodSelected: (StatsPeriod) -> Unit,
    onTopArtistClick: () -> Unit,
    showTopArtistRankingHint: Boolean,
    artistPreview: List<StatsLeader>,
    previousArtistPositions: Map<String, Int>,
    genreShares: List<Pair<String, Float>>,
    languageShares: List<Pair<String, Float>>,
    listeningHours: List<Int>,
    onMediaRanking: (PersonalMediaRanking) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val entrance = "personal:${period.name}:"
    @Suppress("UNUSED_VARIABLE") val preserveSourceApi = onPeriodSelected to showTopArtistRankingHint
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tablet = maxWidth >= 840.dp
        val contentWidth = minOf(maxWidth, 1360.dp) - PAGE_GUTTER * 2
        val gap = 14.dp
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .widthIn(max = 1360.dp)
                .fillMaxWidth()
                .padding(start = PAGE_GUTTER, top = 8.dp, end = PAGE_GUTTER, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (tablet) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    PersonalArtistRankingCard(
                        stats.topArtist, artistPreview, previousArtistPositions, period, onTopArtistClick,
                        cardHeight = 268.dp,
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "artists", 0, revealed),
                        revealed = revealed,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        StatsHeroCard(stats, period, loading, cardHeight = 316.dp,
                            modifier = Modifier.fillMaxWidth().statsCascadeEnter(entrance + "rhythm", 1, revealed),
                            revealed = revealed)
                        PersonalExplorerCard(stats,
                            modifier = Modifier.fillMaxWidth().statsCascadeEnter(entrance + "explorer", 4, revealed))
                    }
                }
            } else {
                PersonalArtistRankingCard(
                    stats.topArtist, artistPreview, previousArtistPositions, period, onTopArtistClick,
                    cardHeight = 252.dp,
                    modifier = Modifier.fillMaxWidth().statsCascadeEnter(entrance + "artists", 0, revealed),
                    revealed = revealed,
                )
                StatsHeroCard(stats, period, loading, cardHeight = 306.dp,
                    modifier = Modifier.fillMaxWidth().statsCascadeEnter(entrance + "rhythm", 1, revealed),
                    revealed = revealed)
            }

            if (tablet) {
                // Two wide horizontal media cards avoid oversized square covers
                // on a landscape tablet; all titles can take the remaining width.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Top,
                ) {
                    LeaderStatCard(
                        label = stringResource(R.string.stats_top_song),
                        leader = stats.topSong,
                        onClick = { onMediaRanking(PersonalMediaRanking.SONGS) },
                        emptyText = stringResource(R.string.stats_no_data_short),
                        cardHeight = 198.dp,
                        horizontalLayout = true,
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "song", 2, revealed),
                        revealed = revealed,
                        entrancePrefix = entrance + "song:",
                    )
                    LeaderStatCard(
                        label = stringResource(R.string.stats_top_album),
                        leader = stats.topAlbum,
                        onClick = { onMediaRanking(PersonalMediaRanking.ALBUMS) },
                        emptyText = stringResource(R.string.stats_no_data_short),
                        cardHeight = 198.dp,
                        horizontalLayout = true,
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "album", 3, revealed),
                        revealed = revealed,
                        entrancePrefix = entrance + "album:",
                    )
                }
            } else {
                val tileWidth = (contentWidth - gap) / 2f
                val compactMediaCardHeight = tileWidth + 108.dp
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    LeaderStatCard(
                        label = stringResource(R.string.stats_top_song),
                        leader = stats.topSong,
                        onClick = { onMediaRanking(PersonalMediaRanking.SONGS) },
                        emptyText = stringResource(R.string.stats_no_data_short),
                        cardHeight = compactMediaCardHeight,
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "song", 2, revealed),
                        revealed = revealed,
                        entrancePrefix = entrance + "song:",
                    )
                    LeaderStatCard(
                        label = stringResource(R.string.stats_top_album),
                        leader = stats.topAlbum,
                        onClick = { onMediaRanking(PersonalMediaRanking.ALBUMS) },
                        emptyText = stringResource(R.string.stats_no_data_short),
                        cardHeight = compactMediaCardHeight,
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "album", 3, revealed),
                        revealed = revealed,
                        entrancePrefix = entrance + "album:",
                    )
                }
                PersonalExplorerCard(stats,
                            modifier = Modifier.fillMaxWidth().statsCascadeEnter(entrance + "explorer", 4, revealed))
            }
            // Charts get their own wide row, preventing clipped legends and
            // 1:1 chart cards floating alongside much taller media tiles.
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                CommunityBreakdownSquareCard(
                    title = stringResource(R.string.stats_top_genres),
                    items = genreShares,
                    modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "genres", 5, revealed),
                    aspect = if (tablet) 1.9f else 1f,
                )
                CommunityBreakdownSquareCard(
                    title = stringResource(R.string.stats_top_languages),
                    items = languageShares,
                    modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "languages", 6, revealed),
                    aspect = if (tablet) 1.9f else 1f,
                )
            }

            if (tablet) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                    WidePlaylistStatCard(
                        label = stringResource(R.string.stats_top_playlist),
                        leader = stats.topPlaylist,
                        emptyText = stringResource(R.string.stats_playlist_no_data),
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "playlist", 7, revealed),
                    )
                    PersonalListeningHoursCard(listeningHours,
                        modifier = Modifier.weight(1f).statsCascadeEnter(entrance + "hours", 8, revealed))
                }
            } else {
                WidePlaylistStatCard(
                    label = stringResource(R.string.stats_top_playlist),
                    leader = stats.topPlaylist,
                    emptyText = stringResource(R.string.stats_playlist_no_data),
                    modifier = Modifier.statsCascadeEnter(entrance + "playlist", 7, revealed),
                )
                PersonalListeningHoursCard(listeningHours,
                    modifier = Modifier.statsCascadeEnter(entrance + "hours", 8, revealed))
            }

            if (loadFailed || (!loading && stats.trackCount == 0)) {
                Text(
                    text = stringResource(if (loadFailed) R.string.stats_load_error else R.string.stats_no_data_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loadFailed) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                        .statsCascadeEnter(entrance + "status", 9, revealed),
                )
            }
        }
    }
}

@Composable
private fun PersonalExplorerCard(stats: ListeningStats, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .statsTopExit()
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 30.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.stats_explorer_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                stats.uniqueArtists to R.string.stats_explorer_artists,
                stats.uniqueTracks to R.string.stats_explorer_tracks,
                stats.uniqueAlbums to R.string.stats_explorer_albums,
            ).forEach { (count, labelId) ->
                Column(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(scheme.primaryContainer)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
                        .padding(horizontal = 9.dp, vertical = 15.dp),
                ) {
                    Text(
                        "$count", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer,
                    )
                    Text(
                        stringResource(labelId),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalListeningHoursCard(hours: List<Int>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val max = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = hours.indices.maxByOrNull { hours.getOrElse(it) { 0 } } ?: 0
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .statsTopExit()
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 30.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.stats_listening_hours_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            if (hours.any { it > 0 }) {
                Text(
                    stringResource(R.string.stats_listening_hours_peak, peak),
                    color = scheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            (0 until 24).forEach { hour ->
                val count = hours.getOrElse(hour) { 0 }
                val level = (count.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.weight(1f)
                        .fillMaxHeight(if (count == 0) .045f else level.coerceAtLeast(.09f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (hour == peak && count > 0) scheme.primary else scheme.tertiary.copy(alpha = .68f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00h", "06h", "12h", "18h", "23h").forEach { label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.stats_listening_hours_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveListeningOverviewCard(
    people: List<OrbProfile>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .widthIn(max = 1360.dp)
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .clip(RoundedCornerShape(30.dp))
            .statsTopExit()
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 30.dp)
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(scheme.primary))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.stats_live_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onSurface)
        }
        if (people.isEmpty()) {
            Text(stringResource(R.string.stats_live_empty), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                people.take(7).forEach { friend ->
                    if (!friend.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = friend.avatarUrl,
                            contentDescription = friend.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(CircleShape),
                        )
                    } else {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(scheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Person, contentDescription = null, tint = scheme.onSecondaryContainer) }
                    }
                }
            }
            Text(
                stringResource(R.string.stats_live_count, people.size),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalArtistRankingCard(
    leader: StatsLeader?,
    artistPreview: List<StatsLeader>,
    previousArtistPositions: Map<String, Int>,
    period: StatsPeriod,
    onClick: () -> Unit,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    revealed: MutableMap<String, Boolean>,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .statsTopExit()
            .clip(RoundedCornerShape(32.dp))
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Highlight and positions 2–5 share one card, matching the Geral music ranking.
        TopArtistFeatureCard(
            leader = leader,
            period = period,
            onClick = onClick,
            cardHeight = cardHeight,
            modifier = Modifier.fillMaxWidth(),
            revealed = revealed,
        )
        artistPreview.drop(1).take(4).forEachIndexed { index, artist ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .height(62.dp)
                    .statsCascadeEnter("personal:${period.name}:artist-row:$index", 3 + index, revealed)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsRankNumber(index + 2)
                if (!artist.artworkUrl.isNullOrBlank()) {
                    StatsRecoveringArtwork(
                        url = artist.artworkUrl,
                        title = artist.title,
                        kind = StatsArtworkKind.ARTIST,
                        requestedPx = ROW_ART_PX,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Person, contentDescription = null, tint = scheme.onSurfaceVariant) }
                }
                Text(
                    artist.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PersonalRankChange(
                    category = "artist", title = artist.title, subtitle = null,
                    position = index + 2, previous = previousArtistPositions,
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.End)
                .statsCascadeEnter("personal:${period.name}:artist-more", 7, revealed)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.stats_general_show_more),
                style = MaterialTheme.typography.labelLarge, color = scheme.primary,
                fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TopArtistFeatureCard(
    leader: StatsLeader?,
    period: StatsPeriod,
    onClick: () -> Unit,
    cardHeight: Dp = 302.dp,
    modifier: Modifier = Modifier,
    revealed: MutableMap<String, Boolean>,
) {
    val periodDays = when (period) {
        StatsPeriod.WEEK -> 7
        StatsPeriod.MONTH -> 30
        StatsPeriod.QUARTER -> 90
        StatsPeriod.SEMESTER -> 182
        StatsPeriod.YEAR -> 365
    }
    val periodPhrase = when (period) {
        StatsPeriod.WEEK -> stringResource(R.string.stats_period_story_week)
        StatsPeriod.MONTH -> stringResource(R.string.stats_period_story_month)
        StatsPeriod.QUARTER -> stringResource(R.string.stats_period_story_quarter)
        StatsPeriod.SEMESTER -> stringResource(R.string.stats_period_story_semester)
        StatsPeriod.YEAR -> stringResource(R.string.stats_period_story_year)
    }
    val storyResource = when (leader?.kind) {
        ArtistKind.BAND -> R.string.stats_top_artist_story_band
        ArtistKind.PERSON -> R.string.stats_top_artist_story_artist
        else -> R.string.stats_top_artist_story_unknown
    }
    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.88f),
        offset = Offset(0f, 2f),
        blurRadius = 7f,
    )

    Box(
        modifier = modifier
            .height(cardHeight)
            .clip(RoundedCornerShape(34.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = leader != null, onClick = onClick),
    ) {
        if (!leader?.artworkUrl.isNullOrBlank()) {
            StatsRecoveringArtwork(
                url = leader?.artworkUrl,
                title = leader?.title,
                kind = StatsArtworkKind.ARTIST,
                requestedPx = TOP_ARTIST_ART_PX,
                contentDescription = leader?.title,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.matchParentSize()
                    .statsCascadeEnter("personal:${period.name}:hero-image", 1, revealed),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .statsCascadeEnter("personal:${period.name}:hero-image", 1, revealed)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.size(54.dp),
                )
            }
        }

        // Neutral bottom shadow only: it protects the typography without
        // tinting or covering the artist artwork with a colored gradient.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.14f),
                            Color.Black.copy(alpha = 0.70f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .statsCascadeEnter("personal:${period.name}:hero-text", 2, revealed)
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_featured_artist),
                style = MaterialTheme.typography.labelLarge.copy(shadow = textShadow),
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
            )
            Text(
                text = leader?.title ?: stringResource(R.string.stats_no_data_short),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = textShadow,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (leader != null) {
                Text(
                    text = stringResource(
                        storyResource,
                        leader.title,
                        periodPhrase,
                        leader.plays,
                        periodDays,
                        formatListeningTimeWords(leader.listenedMs),
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow),
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatsHeroCard(
    stats: ListeningStats,
    period: StatsPeriod,
    loading: Boolean,
    cardHeight: Dp = 302.dp,
    modifier: Modifier = Modifier,
    revealed: MutableMap<String, Boolean>,
) {
    val comparison = stats.comparisonPercent
    Column(
        modifier = modifier
            .heightIn(min = cardHeight)
            .clip(RoundedCornerShape(34.dp))
            .statsTopExit()
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 34.dp)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .statsCascadeEnter("personal:${period.name}:rhythm-heading", 2, revealed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_summary_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stats_summary_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth()
                .statsCascadeEnter("personal:${period.name}:rhythm-metrics", 3, revealed),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroMetric(
                label = stringResource(R.string.stats_time),
                value = formatListeningTimeCompact(stats.totalListenedMs),
                modifier = Modifier.weight(1.18f),
            )
            HeroMetric(
                label = stringResource(R.string.stats_plays),
                value = stats.trackCount.toString(),
                modifier = Modifier.weight(1.05f),
            )
            HeroMetric(
                label = stringResource(R.string.stats_tracks),
                value = stats.uniqueTracks.toString(),
                modifier = Modifier.weight(0.88f),
            )
            HeroMetric(
                label = stringResource(R.string.stats_artists),
                value = stats.uniqueArtists.toString(),
                modifier = Modifier.weight(0.88f),
            )
        }

        Spacer(Modifier.height(16.dp))
        RhythmMiniChart(
            currentValues = stats.rhythmSeries,
            previousValues = stats.previousRhythmSeries,
            period = period,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .statsCascadeEnter("personal:${period.name}:rhythm-chart", 4, revealed),
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth()
                .statsCascadeEnter("personal:${period.name}:rhythm-comparison", 5, revealed),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_comparison_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StatsMetricPill(
                compact = true,
                tone = when {
                    comparison == null || comparison == 0 -> StatsMetricTone.NEUTRAL
                    comparison > 0 -> StatsMetricTone.POSITIVE
                    else -> StatsMetricTone.NEGATIVE
                },
            ) { ink ->
                Text(
                    text = comparison?.let { if (it > 0) "+$it%" else "$it%" } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RhythmMiniChart(
    currentValues: List<Int>,
    previousValues: List<Int>,
    period: StatsPeriod,
    modifier: Modifier = Modifier,
) {
    val current = if (currentValues.isNotEmpty()) currentValues else List(12) { 0 }
    val previous = if (previousValues.isNotEmpty()) previousValues else List(current.size.coerceAtLeast(2)) { 0 }
    val reveal = remember(current, previous) { Animatable(0f) }
    val contentColor = MaterialTheme.colorScheme.onSurface
    // Material You: do not pin neon colors that break dynamic wallpaper palettes.
    val gradientStart = MaterialTheme.colorScheme.primary
    val gradientEnd = MaterialTheme.colorScheme.tertiary
    val pointCount = maxOf(current.size, previous.size, 2)
    var selectedIndex by remember(current, previous) { mutableStateOf<Int?>(null) }

    fun padded(source: List<Int>): List<Int> = when {
        source.size == pointCount -> source
        source.isEmpty() -> List(pointCount) { 0 }
        source.size == 1 -> List(pointCount) { source.first() }
        else -> List(pointCount) { index ->
            source[((index.toFloat() / (pointCount - 1).toFloat()) * (source.size - 1))
                .roundToInt()
                .coerceIn(source.indices)]
        }
    }
    val currentPadded = remember(current, pointCount) { padded(current) }
    val previousPadded = remember(previous, pointCount) { padded(previous) }

    LaunchedEffect(current, previous) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(durationMillis = 680))
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(currentPadded, previousPadded, selectedIndex) {
                    detectTapGestures { offset ->
                        val width = size.width.coerceAtLeast(1)
                        val tappedIndex = ((offset.x / width.toFloat()) * (pointCount - 1).toFloat())
                            .roundToInt()
                            .coerceIn(0, pointCount - 1)
                        // Tapping the already-selected point is the natural
                        // close gesture for the tiny detail bubble.
                        selectedIndex = if (selectedIndex == tappedIndex) null else tappedIndex
                    }
                },
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas

            val top = 5.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            val chartHeight = (bottom - top).coerceAtLeast(1f)
            val gridColor = contentColor.copy(alpha = 0.075f)

            repeat(5) { row ->
                val y = top + chartHeight * (row / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            repeat(9) { col ->
                val x = size.width * (col / 8f)
                drawLine(
                    color = gridColor,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val maxValue = (currentPadded + previousPadded).maxOrNull()?.coerceAtLeast(1) ?: 1

            fun points(values: List<Int>): List<Offset> = values.mapIndexed { index, value ->
                val x = if (values.size <= 1) 0f else size.width * index.toFloat() / (values.size - 1).toFloat()
                val fraction = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                val y = bottom - chartHeight * (0.08f + fraction * 0.84f)
                Offset(x, y)
            }

            val previousPoints = points(previousPadded)
            val currentPoints = points(currentPadded)
            val previousPath = smoothChartPath(previousPoints)
            val currentPath = smoothChartPath(currentPoints)
            val fillPath = Path().apply {
                addPath(currentPath)
                lineTo(currentPoints.last().x, bottom)
                lineTo(currentPoints.first().x, bottom)
                close()
            }

            drawPath(
                path = previousPath,
                color = contentColor.copy(alpha = 0.24f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            clipRect(left = 0f, top = 0f, right = size.width * reveal.value.coerceIn(0f, 1f), bottom = size.height) {
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            gradientStart.copy(alpha = 0.20f),
                            gradientEnd.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        startY = top,
                        endY = bottom,
                    ),
                )
                drawPath(
                    path = currentPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(gradientStart, gradientEnd),
                        startX = 0f,
                        endX = size.width,
                    ),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )

                // Every dot is one real calendar interval. Keep the markers
                // subtle until selected, but visible enough to make the graph
                // structure understandable without guessing.
                currentPoints.forEach { point ->
                    drawCircle(
                        color = contentColor.copy(alpha = 0.58f),
                        radius = 1.65.dp.toPx(),
                        center = point,
                    )
                }
            }

            selectedIndex?.let { index ->
                val selected = currentPoints[index.coerceIn(currentPoints.indices)]
                drawLine(
                    color = contentColor.copy(alpha = 0.24f),
                    start = Offset(selected.x, top),
                    end = Offset(selected.x, bottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(
                    color = contentColor,
                    radius = 4.dp.toPx(),
                    center = selected,
                )
                drawCircle(
                    color = gradientEnd,
                    radius = 2.5.dp.toPx(),
                    center = selected,
                )
            }
        }

        selectedIndex?.let { rawIndex ->
            val index = rawIndex.coerceIn(0, pointCount - 1)
            val currentSeconds = currentPadded[index]
            val previousSeconds = previousPadded[index]
            val deltaSeconds = currentSeconds - previousSeconds
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = formatStatsChartInterval(period, index, pointCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.stats_chart_you_listened,
                        formatListeningSecondsCompact(currentSeconds),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = statsPreviousIntervalText(
                        period = period,
                        value = formatListeningSecondsCompact(previousSeconds),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    text = stringResource(
                        R.string.stats_chart_difference,
                        formatListeningSecondsSigned(deltaSeconds),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deltaSeconds >= 0) gradientEnd else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun statsPreviousIntervalText(
    period: StatsPeriod,
    value: String,
): String = when (period) {
    StatsPeriod.WEEK -> stringResource(R.string.stats_chart_previous_week_day, value)
    StatsPeriod.MONTH -> stringResource(R.string.stats_chart_previous_month_block, value)
    StatsPeriod.QUARTER -> stringResource(R.string.stats_chart_previous_quarter_week, value)
    StatsPeriod.SEMESTER -> stringResource(R.string.stats_chart_previous_semester_month, value)
    StatsPeriod.YEAR -> stringResource(R.string.stats_chart_previous_year_month, value)
}

private fun formatStatsChartInterval(
    period: StatsPeriod,
    index: Int,
    bucketCount: Int,
): String {
    val now = ZonedDateTime.now()
    val locale = Locale.getDefault()
    val safeIndex = index.coerceIn(0, (bucketCount - 1).coerceAtLeast(0))

    val periodStart = when (period) {
        StatsPeriod.WEEK -> now
            .toLocalDate()
            .minusDays(6)
            .atStartOfDay(now.zone)

        StatsPeriod.MONTH -> now
            .toLocalDate()
            .minusDays((now.toLocalDate().lengthOfMonth() - 1).toLong())
            .atStartOfDay(now.zone)

        StatsPeriod.QUARTER -> {
            val firstMonth = ((now.monthValue - 1) / 3) * 3 + 1
            LocalDate.of(now.year, firstMonth, 1).atStartOfDay(now.zone)
        }

        StatsPeriod.SEMESTER -> {
            val firstMonth = if (now.monthValue <= 6) 1 else 7
            LocalDate.of(now.year, firstMonth, 1).atStartOfDay(now.zone)
        }

        StatsPeriod.YEAR -> LocalDate.of(now.year, 1, 1).atStartOfDay(now.zone)
    }

    return when (period) {
        StatsPeriod.WEEK -> {
            val date = periodStart.toLocalDate().plusDays(safeIndex.toLong())
            val dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }
            val dateText = DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(date)
            "$dayName · $dateText"
        }

        StatsPeriod.MONTH -> {
            val firstDay = periodStart.toLocalDate().plusDays((safeIndex * 7L))
            val rollingEnd = now.toLocalDate()
            val lastDay = firstDay.plusDays(6).let { if (it > rollingEnd) rollingEnd else it }
            formatStatsDateRange(firstDay, lastDay, locale)
        }

        StatsPeriod.QUARTER -> {
            val firstDay = periodStart.toLocalDate().plusDays((safeIndex * 7L))
            val quarterEnd = periodStart.plusMonths(3).toLocalDate().minusDays(1)
            val lastDay = firstDay.plusDays(6).let { if (it > quarterEnd) quarterEnd else it }
            formatStatsDateRange(firstDay, lastDay, locale)
        }

        StatsPeriod.SEMESTER -> {
            val month = periodStart.plusMonths(safeIndex.toLong())
            DateTimeFormatter
                .ofPattern("MMMM yyyy", locale)
                .format(month)
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }
        }

        StatsPeriod.YEAR -> {
            val month = periodStart.plusMonths(safeIndex.toLong())
            DateTimeFormatter
                .ofPattern("MMMM yyyy", locale)
                .format(month)
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }
        }
    }
}

private fun formatStatsDateRange(
    first: LocalDate,
    last: LocalDate,
    locale: Locale,
): String {
    val short = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    return if (first == last) {
        short.format(first)
    } else {
        "${short.format(first)} – ${short.format(last)}"
    }
}

private fun smoothChartPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path

    for (index in 0 until points.lastIndex) {
        val p0 = points[(index - 1).coerceAtLeast(0)]
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = points[(index + 2).coerceAtMost(points.lastIndex)]
        val control1 = Offset(
            x = p1.x + (p2.x - p0.x) / 6f,
            y = p1.y + (p2.y - p0.y) / 6f,
        )
        val control2 = Offset(
            x = p2.x - (p3.x - p1.x) / 6f,
            y = p2.y - (p3.y - p1.y) / 6f,
        )
        path.cubicTo(
            control1.x,
            control1.y,
            control2.x,
            control2.y,
            p2.x,
            p2.y,
        )
    }
    return path
}

@Composable
private fun ArtistRankingEntrance(
    index: Int,
    animationKey: String,
    alreadyRevealed: Boolean,
    onRevealed: () -> Unit,
    content: @Composable () -> Unit,
) {
    com.music.orb.ui.components.ProgressiveActionSheetItem(
        index = index,
        itemKey = animationKey,
        initiallyRevealed = alreadyRevealed,
        onRevealed = onRevealed,
    ) { content() }
}

@Composable
private fun ArtistRankingRow(
    entry: ArtistRankEntry,
    modifier: Modifier = Modifier,
) {
    val leader = entry.leader

    // NEW and PEAK use fixed semantic highlight colors. Positional movement,
    // unchanged state and re-entry are intentionally drawn from the active
    // Material 3 color scheme so they adapt to Orb's expressive theme.
    val movementColor = when (entry.movement) {
        ArtistRankMovement.NEW -> Color(0xFFFFC107)
        ArtistRankMovement.PEAK -> Color(0xFF2E9B67)
        ArtistRankMovement.UP -> MaterialTheme.colorScheme.primary
        ArtistRankMovement.DOWN -> MaterialTheme.colorScheme.tertiary
        ArtistRankMovement.SAME -> MaterialTheme.colorScheme.secondary
        ArtistRankMovement.RETURNED -> MaterialTheme.colorScheme.primary
    }

    val movementLabel = when (entry.movement) {
        ArtistRankMovement.NEW -> stringResource(R.string.stats_artist_ranking_new)
        ArtistRankMovement.PEAK -> stringResource(R.string.stats_artist_ranking_peak)
        ArtistRankMovement.UP,
        ArtistRankMovement.DOWN -> entry.movementAmount.toString()
        ArtistRankMovement.SAME -> stringResource(R.string.stats_artist_ranking_same)
        ArtistRankMovement.RETURNED -> ""
    }

    val movementDescription = when (entry.movement) {
        ArtistRankMovement.NEW -> stringResource(R.string.stats_artist_ranking_new)
        ArtistRankMovement.PEAK -> stringResource(R.string.stats_artist_ranking_peak)
        ArtistRankMovement.UP -> stringResource(
            R.string.stats_artist_ranking_up_accessibility,
            entry.movementAmount,
        )
        ArtistRankMovement.DOWN -> stringResource(
            R.string.stats_artist_ranking_down_accessibility,
            entry.movementAmount,
        )
        ArtistRankMovement.SAME -> stringResource(R.string.stats_artist_ranking_same_accessibility)
        ArtistRankMovement.RETURNED -> stringResource(R.string.stats_artist_ranking_returned_accessibility)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(horizontal = PAGE_GUTTER, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clip(RoundedCornerShape(35.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(start = 78.dp, end = 14.dp)
                .align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = leader.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.stats_artist_ranking_plays, leader.plays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))

                when (entry.movement) {
                    ArtistRankMovement.NEW,
                    ArtistRankMovement.PEAK -> {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = movementColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }

                    ArtistRankMovement.UP -> {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = movementDescription,
                            tint = movementColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(1.dp))
                    }

                    ArtistRankMovement.DOWN -> {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = movementDescription,
                            tint = movementColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(1.dp))
                    }

                    ArtistRankMovement.RETURNED -> {
                        // Custom vector equivalent of ↩. It is a real vector
                        // drawable (not an emoji/glyph) and has no container or border.
                        Icon(
                            painter = painterResource(R.drawable.ic_rank_returned),
                            contentDescription = movementDescription,
                            tint = movementColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    ArtistRankMovement.SAME -> Unit
                }

                if (entry.movement != ArtistRankMovement.RETURNED) {
                    Text(
                        text = movementLabel,
                        style = if (entry.movement == ArtistRankMovement.SAME) {
                            MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        },
                        color = movementColor,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(66.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!leader.artworkUrl.isNullOrBlank()) {
                StatsRecoveringArtwork(
                    url = leader.artworkUrl,
                    title = leader.title,
                    subtitle = leader.subtitle,
                    kind = StatsArtworkKind.ARTIST,
                    requestedPx = ROW_ART_PX,
                    contentDescription = leader.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = leader.title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = "#${entry.position}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(11.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceContainerHighest)
                .border(1.dp, colors.outlineVariant, shape)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatsPeriodSelector(
    selected: StatsPeriod,
    onSelected: (StatsPeriod) -> Unit,
) {
    val periods = StatsPeriod.values().toList()
    val selectedIndex = periods.indexOf(selected).coerceAtLeast(0)
    val labels = periods.map { period ->
        when (period) {
            StatsPeriod.WEEK -> stringResource(R.string.stats_period_week)
            StatsPeriod.MONTH -> stringResource(R.string.stats_period_month)
            StatsPeriod.QUARTER -> stringResource(R.string.stats_period_quarter)
            StatsPeriod.SEMESTER -> stringResource(R.string.stats_period_semester)
            StatsPeriod.YEAR -> stringResource(R.string.stats_period_year)
        }
    }

    // Use the exact same travelling/bouncy capsule as Home so Week, Month,
    // Quarter, Semester and Year share Orb's bottom-navigation motion language.
    OrbBouncyPillSelector(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelected = { index ->
            periods.getOrNull(index)?.let(onSelected)
        },
    )
}

/** Small square-cover tile used by the personal music Bento. */
@Composable
private fun LeaderStatCard(
    label: String,
    leader: StatsLeader?,
    emptyText: String,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    horizontalLayout: Boolean = false,
    revealed: MutableMap<String, Boolean>,
    entrancePrefix: String,
) {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(topStart = 30.dp, topEnd = 22.dp, bottomEnd = 30.dp, bottomStart = 22.dp)
    if (horizontalLayout) {
        Row(
            modifier = modifier.height(cardHeight)
                .statsTopExit()
                .clip(cardShape)
                .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatsCardArtwork(
                url = leader?.artworkUrl,
                title = leader?.title,
                subtitle = leader?.subtitle,
                kind = if (entrancePrefix.contains("album")) StatsArtworkKind.ALBUM else StatsArtworkKind.SONG,
                contentColor = scheme.onSurface,
                modifier = Modifier.size(150.dp)
                    .statsCascadeEnter(entrancePrefix + "art", 1, revealed),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant,
                    modifier = Modifier.statsCascadeEnter(entrancePrefix + "label", 2, revealed),
                    maxLines = 2)
                Text(leader?.title ?: emptyText,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.statsCascadeEnter(entrancePrefix + "title", 3, revealed))
                leader?.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (onClick != null) {
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                            tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    } else {
        Column(
            modifier = modifier.height(cardHeight)
                .statsTopExit()
                .clip(cardShape)
                .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                modifier = Modifier.statsCascadeEnter(entrancePrefix + "label", 2, revealed),
                maxLines = 2)
            StatsCardArtwork(
                url = leader?.artworkUrl, title = leader?.title, subtitle = leader?.subtitle,
                kind = if (entrancePrefix.contains("album")) StatsArtworkKind.ALBUM else StatsArtworkKind.SONG,
                contentColor = scheme.onSurface,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .statsCascadeEnter(entrancePrefix + "art", 1, revealed),
            )
            Text(leader?.title ?: emptyText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.statsCascadeEnter(entrancePrefix + "title", 3, revealed))
            leader?.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) {
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                        tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Playlist occupies a landscape tile on phones rather than a third long portrait. */
@Composable
private fun WidePlaylistStatCard(
    label: String,
    leader: StatsLeader?,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(138.dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 30.dp, bottomEnd = 22.dp, bottomStart = 30.dp))
            .statsTopExit()
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 26.dp)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsCardArtwork(
            url = leader?.artworkUrl,
            title = leader?.title,
            kind = StatsArtworkKind.PLAYLIST,
            contentColor = scheme.onSurface,
            modifier = Modifier.size(114.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.secondary))
                Spacer(Modifier.width(6.dp))
                Text(label, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                leader?.title ?: emptyText,
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            leader?.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                Text(
                    subtitle,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatsCardArtwork(
    url: String?,
    title: String?,
    contentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    kind: StatsArtworkKind = StatsArtworkKind.SONG,
) {
    StatsRecoveringArtwork(
        url = url,
        title = title,
        subtitle = subtitle,
        kind = kind,
        requestedPx = STATS_TILE_ART_PX,
        fallbackColor = contentColor.copy(alpha = 0.72f),
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .background(contentColor.copy(alpha = 0.10f)),
    )
}

@Composable
private fun NewFollowersStatCard(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.stats_new_followers),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.88f),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun TopGenreStatCard(
    genres: List<String>,
    cardHeight: Dp = 134.dp,
    modifier: Modifier = Modifier,
) {
    val ranked = genres
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.getDefault()) }
        .take(5)

    RankedTasteStatCard(
        ranked = ranked,
        singularLabel = R.string.stats_top_genre,
        pluralLabel = R.string.stats_top_genres,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        cardHeight = cardHeight,
        modifier = modifier,
    )
}

@Composable
private fun TopLanguageStatCard(
    languages: List<String>,
    cardHeight: Dp = 134.dp,
    modifier: Modifier = Modifier,
) {
    val ranked = languages
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.getDefault()) }
        .take(5)

    RankedTasteStatCard(
        ranked = ranked,
        singularLabel = R.string.stats_top_language,
        pluralLabel = R.string.stats_top_languages,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        cardHeight = cardHeight,
        modifier = modifier,
    )
}

@Composable
private fun RankedTasteStatCard(
    ranked: List<String>,
    singularLabel: Int,
    pluralLabel: Int,
    containerColor: Color,
    contentColor: Color,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val primary = ranked.firstOrNull()
    val runnersUp = ranked.drop(1).take(3)
    @Suppress("UNUSED_VARIABLE") val originalColors = containerColor to contentColor
    Column(
        modifier = modifier
            .height(cardHeight)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 24.dp))
            .orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp)
            .padding(horizontal = 13.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(containerColor),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary))
            }
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(if (ranked.size > 1) pluralLabel else singularLabel),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = primary ?: stringResource(R.string.stats_no_data_short),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        runnersUp.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(5.dp).clip(CircleShape)
                        .background(if (index == 0) scheme.tertiary else scheme.secondary.copy(alpha = 0.72f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatStatsDate(date: LocalDate): String =
    String.format(Locale.getDefault(), "%02d/%02d", date.dayOfMonth, date.monthValue)


private fun formatStatsWeekday(dayOfWeek: Int): String = runCatching {
    DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.FULL, Locale.getDefault())
}.getOrDefault("")

private fun formatStatsHour(hour: Int): String = runCatching {
    LocalTime.of(hour.coerceIn(0, 23), 0).format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()),
    )
}.getOrDefault("")

private fun formatListeningTime(ms: Long): String {
    val totalMinutes = (ms.coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}min"
        hours > 0L -> "${hours}h"
        else -> "${minutes}min"
    }
}

@Composable
private fun formatListeningTimeWords(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val hoursText = if (hours > 0L) {
        androidx.compose.ui.res.pluralStringResource(
            R.plurals.stats_duration_hours,
            hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            hours,
        )
    } else null
    val minutesText = androidx.compose.ui.res.pluralStringResource(
        R.plurals.stats_duration_minutes,
        minutes.toInt(),
        minutes,
    )
    return if (hoursText != null) {
        stringResource(R.string.stats_duration_join, hoursText, minutesText)
    } else {
        minutesText
    }
}

private fun formatListeningTimeCompact(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return String.format(Locale.getDefault(), "%02dh%02d", hours, minutes)
}

private fun formatListeningSecondsCompact(seconds: Int): String =
    formatListeningTimeCompact(seconds.coerceAtLeast(0).toLong() * 1_000L)

private fun formatListeningSecondsSigned(seconds: Int): String {
    val prefix = when {
        seconds > 0 -> "+"
        seconds < 0 -> "−"
        else -> ""
    }
    return prefix + formatListeningSecondsCompact(kotlin.math.abs(seconds))
}


@Composable
private fun UsernameSetupCard(
    username: String,
    saving: Boolean,
    error: UsernameSetupError?,
    onUsernameChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 18.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
    ) {
        Text(
            text = stringResource(R.string.friends_create_profile_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.friends_create_profile_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "@",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = username,
                onValueChange = onUsernameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onContinue() }),
                modifier = Modifier.weight(1f),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (error) {
                    UsernameSetupError.INVALID -> stringResource(R.string.friends_username_invalid)
                    UsernameSetupError.TAKEN -> stringResource(
                        R.string.friends_username_taken,
                        username,
                    )
                    UsernameSetupError.SAVE_FAILED -> stringResource(R.string.friends_username_save_failed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onContinue,
            enabled = !saving,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(stringResource(R.string.friends_continue))
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = 6.dp,
            bottom = 10.dp,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendNowPlayingCard(
    profile: OrbProfile,
    nowPlaying: FriendNowPlaying,
    activity: ListeningActivity,
    isLive: Boolean,
    reactions: List<NowPlayingReaction>,
    currentUserId: String?,
    reactionBusy: Boolean,
    animateEntrance: Boolean,
    onReact: (NowPlayingReactionType) -> Unit,
    onEntranceConsumed: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionKey = nowPlaying.reactionSessionKey()
    var visible by remember(sessionKey) { mutableStateOf(!animateEntrance) }
    var reactionPickerOpen by remember(sessionKey) { mutableStateOf(false) }
    var clockMs by remember(sessionKey) { mutableStateOf(System.currentTimeMillis()) }
    val tactileContext = LocalContext.current
    var flightArrived by remember(sessionKey) { mutableStateOf(false) }
    var reactionCenterInWindow by remember(sessionKey) { mutableStateOf(Offset.Zero) }
    var removingReaction by remember(sessionKey) { mutableStateOf<NowPlayingReactionType?>(null) }
    val smoke = remember(sessionKey) { Animatable(0f) }
    LaunchedEffect(removingReaction) {
        val type = removingReaction ?: return@LaunchedEffect
        smoke.snapTo(0f)
        com.music.orb.ui.components.OrbHaptics.reactionWhoosh(tactileContext)
        onReact(type)
        smoke.animateTo(1f, tween(850))
        removingReaction = null
    }
    var flyingReaction by remember(sessionKey) { mutableStateOf<Pair<NowPlayingReactionType, Offset>?>(null) }
    var heartBurstNonce by remember(sessionKey) { mutableIntStateOf(0) }
    val flight = remember(sessionKey) { Animatable(0f) }
    val landing = remember(sessionKey) { Animatable(1f) }
    LaunchedEffect(flyingReaction) {
        val flying = flyingReaction ?: return@LaunchedEffect
        try {
            flightArrived = false
            flight.snapTo(0f)
            flight.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            com.music.orb.ui.components.OrbHaptics.reactionPop(tactileContext)
            if (flying.first == NowPlayingReactionType.HEART) heartBurstNonce += 1
            onReact(flying.first)
            flightArrived = true
            landing.snapTo(1.24f)
            landing.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 380f))
        } finally {
            flyingReaction = null
            flightArrived = false
        }
    }
    val reactionCardScale by animateFloatAsState(
        targetValue = if (reactionPickerOpen) 1.012f else 1f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "friendReactionCardScale",
    )

    LaunchedEffect(sessionKey, isLive) {
        if (!isLive) {
            while (true) {
                clockMs = System.currentTimeMillis()
                delay(60_000L)
            }
        }
    }

    LaunchedEffect(sessionKey, animateEntrance) {
        if (animateEntrance) {
            visible = false
            delay(20)
            visible = true
            delay(LIVE_CARD_ENTRANCE_MS)
            onEntranceConsumed()
        } else {
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            initialOffsetX = { width -> -width },
        ) + expandVertically(
            animationSpec = tween(360),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = tween(190)),
        exit = slideOutHorizontally(
            animationSpec = tween(220),
            targetOffsetX = { width -> -width / 2 },
        ) + shrinkVertically(
            animationSpec = tween(260),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = tween(150)),
    ) {
        val colorIndex = (sessionKey.hashCode() and Int.MAX_VALUE) % 3
        val accentColor = when (colorIndex) {
            0 -> MaterialTheme.colorScheme.primary
            1 -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.tertiary
        }
        val containerColor = when (colorIndex) {
            0 -> MaterialTheme.colorScheme.primaryContainer
            1 -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer
        }
        val contentColor = when (colorIndex) {
            0 -> MaterialTheme.colorScheme.onPrimaryContainer
            1 -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onTertiaryContainer
        }
        val secondaryTextColor = contentColor.copy(alpha = 0.86f)
        val tertiaryTextColor = contentColor.copy(alpha = 0.74f)
        val textShadow = Shadow(color = Color.Transparent)
        val myReaction = reactions
            .firstOrNull { it.reactorId == currentUserId }
            ?.type()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.dp, bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 44.dp)
                        .graphicsLayer {
                            scaleX = reactionCardScale
                            scaleY = reactionCardScale
                        }
                        .clip(RoundedCornerShape(32.dp))
                        .background(containerColor)
                        .orbGlassSurface(pageColor = containerColor, cornerRadius = 32.dp)
                        .border(1.dp, accentColor.copy(alpha = 0.24f), RoundedCornerShape(32.dp))
                        .combinedClickable(
                            onClick = { },
                            onLongClick = {
                                com.music.orb.ui.components.OrbHaptics.perform(tactileContext, com.music.orb.ui.components.OrbHaptics.Kind.ACTION)
                                reactionPickerOpen = true
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 18.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            containerColor.copy(alpha = 0.12f),
                                            containerColor.copy(alpha = 0.06f),
                                            Color.Transparent,
                                        ),
                                    ),
                                ),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp),
                        ) {
                        Text(
                            text = if (isLive) {
                                stringResource(R.string.friends_is_listening_now)
                            } else {
                                pastListeningLabel(activity.finishedAt, clockMs)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(shadow = textShadow),
                            color = secondaryTextColor,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = nowPlaying.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, shadow = textShadow),
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = nowPlaying.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow),
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        nowPlaying.album?.takeIf { it.isNotBlank() }?.let { album ->
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = album,
                                style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow),
                                color = tertiaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        }
                    }
                    Box(Modifier.padding(end = 12.dp)) { Artwork(nowPlaying = nowPlaying, size = 84) }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(2f)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenProfile),
                ) {
                    ProfileAvatar(profile = profile, size = 52)
                }

                Text(
                    text = profile.username
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "@$it" }
                        ?: profile.displayLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 60.dp, top = 15.dp, end = 12.dp)
                        .clickable(onClick = onOpenProfile),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-13).dp, y = 12.dp)
                        .onGloballyPositioned { reactionCenterInWindow = it.boundsInWindow().center }
                        .graphicsLayer { scaleX = landing.value; scaleY = landing.value }
                        .zIndex(3f),
                ) {
                    LiveReactionSummary(
                        reactions = reactions,
                        busy = reactionBusy,
                        onOpenPicker = {
                            com.music.orb.ui.components.OrbHaptics.perform(tactileContext, com.music.orb.ui.components.OrbHaptics.Kind.ACTION)
                            reactionPickerOpen = true
                        },
                    )
                    HeartBurstOverlay(
                        trigger = heartBurstNonce,
                        tint = Color(0xFFFF3B30),
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.55f
                                scaleY = 1.55f
                            },
                    )
                flyingReaction?.let { (type, origin) ->
                    val progress = flight.value.coerceIn(0f, 1f)
                    if (!flightArrived) {
                        Box(Modifier.align(Alignment.Center).size(0.dp)
                            .wrapContentSize(Alignment.Center, unbounded = true)) {
                            Text(type.emoji, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.graphicsLayer {
                                    val delta = origin - reactionCenterInWindow
                                    translationX = delta.x * (1f - progress)
                                    translationY = delta.y * (1f - progress) -
                                        40.dp.toPx() * kotlin.math.sin(Math.PI * progress).toFloat()
                                    scaleX = 1f + .35f * (1f - progress)
                                    scaleY = scaleX
                                })
                        }
                    }
                }
                removingReaction?.let { type ->
                    val progress = smoke.value
                    val smokeColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Box(Modifier.align(Alignment.Center).size(0.dp)
                        .wrapContentSize(Alignment.Center, unbounded = true).size(112.dp),
                        contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().blur(5.dp)) {
                            repeat(8) { index ->
                                val angle = index * Math.PI * 2 / 8
                                val spread = 32.dp.toPx() * progress
                                drawCircle(smokeColor.copy(alpha = (1f - progress) * progress * .65f),
                                    radius = (4f + 9f * progress).dp.toPx(),
                                    center = center + Offset(kotlin.math.cos(angle).toFloat() * spread,
                                        kotlin.math.sin(angle).toFloat() * spread - 26.dp.toPx() * progress))
                            }
                        }
                        Text(type.emoji, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.blur((progress * 9f).dp).graphicsLayer {
                                alpha = (1f - progress).coerceIn(0f, 1f)
                                translationY = -26.dp.toPx() * progress
                                scaleX = 1f + progress * .4f
                                scaleY = scaleX
                            })
                    }
                }
                }

                if (reactionPickerOpen) {
                    FriendReactionPickerBubble(
                        selected = myReaction,
                        busy = reactionBusy || flyingReaction != null || removingReaction != null,
                        onDismiss = { reactionPickerOpen = false },
                        onReact = { type, origin ->
                            reactionPickerOpen = false
                            if (type == myReaction) removingReaction = type
                            else flyingReaction = type to origin
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-36).dp)
                            .zIndex(12f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartBurstOverlay(
    trigger: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val burst = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        burst.snapTo(0f)
        burst.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val progress = burst.value
    if (progress <= 0f || progress >= 1f) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ringRadius = size.minDimension * (0.16f + 0.34f * progress)
            drawCircle(
                color = tint.copy(alpha = (1f - progress) * 0.18f),
                radius = ringRadius,
                center = center,
            )
            val particleRadius = size.minDimension * 0.055f
            repeat(6) { index ->
                val angle = (Math.PI * 2.0 * index / 6.0) - Math.PI / 2.0
                val travel = size.minDimension * (0.10f + 0.22f * progress)
                val offset = Offset(
                    x = kotlin.math.cos(angle).toFloat() * travel,
                    y = kotlin.math.sin(angle).toFloat() * travel,
                )
                drawCircle(
                    color = tint.copy(alpha = (1f - progress) * 0.95f),
                    radius = particleRadius * (1f - 0.32f * progress),
                    center = center + offset,
                )
            }
        }
    }
}

@Composable
private fun ReactionNotificationRow(
    notification: ReactionNotification,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reactor = notification.reactor
    val type = notification.reaction.type()
    val track = notification.activity?.title
    val artist = notification.activity?.artist
    val reactedAt = runCatching { Instant.parse(notification.reaction.reactedAt).toEpochMilli() }
        .getOrDefault(System.currentTimeMillis())
    val ageMinutes = ((System.currentTimeMillis() - reactedAt).coerceAtLeast(0L) / 60_000L).toInt()
    val actor = reactor?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
        ?: reactor?.displayLabel()
        ?: stringResource(R.string.stats_notification_someone)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = reactor != null, onClick = onOpenProfile)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reactor != null) {
            ProfileAvatar(profile = reactor, size = 46)
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(type?.emoji ?: "•", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.stats_notification_reacted,
                    actor,
                    type?.emoji ?: "•",
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!track.isNullOrBlank()) {
                Text(
                    text = listOfNotNull(track, artist?.takeIf { it.isNotBlank() }).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = when {
                    ageMinutes <= 0 -> stringResource(R.string.stats_notification_now)
                    ageMinutes == 1 -> stringResource(R.string.stats_notification_minute_ago)
                    else -> stringResource(R.string.stats_notification_minutes_ago, ageMinutes)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FollowNotificationRow(
    notification: FollowNotification,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val follower = notification.follower
    val followedAt = runCatching { Instant.parse(notification.follow.createdAt).toEpochMilli() }
        .getOrDefault(System.currentTimeMillis())
    val ageMinutes = ((System.currentTimeMillis() - followedAt).coerceAtLeast(0L) / 60_000L).toInt()
    val actor = follower?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
        ?: follower?.displayLabel()
        ?: stringResource(R.string.stats_notification_someone)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = follower != null, onClick = onOpenProfile)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (follower != null) {
            ProfileAvatar(profile = follower, size = 46)
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.stats_notification_followed_you, actor),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    ageMinutes <= 0 -> stringResource(R.string.stats_notification_now)
                    ageMinutes == 1 -> stringResource(R.string.stats_notification_minute_ago)
                    else -> stringResource(R.string.stats_notification_minutes_ago, ageMinutes)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun FriendNowPlaying.matchesListeningActivity(activity: ListeningActivity): Boolean {
    if (userId != activity.userId || videoId != activity.videoId) return false
    val liveStart = runCatching { Instant.parse(startedAt).toEpochMilli() }.getOrNull() ?: return false
    val activityStart = runCatching { Instant.parse(activity.startedAt).toEpochMilli() }.getOrNull() ?: return false
    // The two rows are written by separate Supabase calls and can differ by a
    // small timestamp precision/commit delay. Treat them as the same playback
    // session when the identity matches and their starts are effectively equal.
    return kotlin.math.abs(liveStart - activityStart) <= 15_000L
}

private fun ListeningActivity.asFriendNowPlaying(live: FriendNowPlaying?): FriendNowPlaying =
    live?.copy(
        // Reactions are keyed to the durable listening_activity session. Even
        // when Supabase rounds the live row timestamp slightly differently,
        // keep the feed row's exact start so reactions stay attached to the
        // same session while the live heartbeat still drives the status.
        startedAt = startedAt,
    ) ?: FriendNowPlaying(
        userId = userId,
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        startedAt = startedAt,
        heartbeatAt = finishedAt,
    )

@Composable
private fun pastListeningLabel(finishedAt: String, nowMs: Long): String {
    val finishedMs = runCatching { Instant.parse(finishedAt).toEpochMilli() }.getOrNull() ?: nowMs
    val minutes = ((nowMs - finishedMs).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes <= 0 -> stringResource(R.string.friends_was_listening_just_now)
        minutes == 1 -> stringResource(R.string.friends_was_listening_minute_ago)
        minutes > 60 -> {
            val hours = minutes / 60
            if (hours == 1) {
                stringResource(R.string.friends_was_listening_hour_ago)
            } else {
                stringResource(R.string.friends_was_listening_hours_ago, hours)
            }
        }
        else -> stringResource(R.string.friends_was_listening_minutes_ago, minutes)
    }
}

@Composable
private fun FriendReactionPickerBubble(
    selected: NowPlayingReactionType?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReact: (NowPlayingReactionType, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    RegisterSheetBackdrop()
    val reveal = remember { Animatable(0f) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val types = remember { NowPlayingReactionType.values().toList() }
    val centers = remember { mutableStateMapOf<NowPlayingReactionType, Offset>() }
    var draggedEmoji by remember { mutableStateOf<NowPlayingReactionType?>(null) }
    LaunchedEffect(Unit) {
        reveal.snapTo(0f)
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.58f,
                stiffness = 380f,
                visibilityThreshold = 0.001f,
            ),
        )
    }
    fun dismissAnimated() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            reveal.animateTo(0f, tween(150))
            onDismiss()
        }
    }
    fun reactAnimated(type: NowPlayingReactionType, origin: Offset) {
        if (dismissing) return
        dismissing = true
        scope.launch {
            reveal.animateTo(0f, tween(115))
            onReact(type, origin)
        }
    }
    var popupOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier) {
    Popup(
        popupPositionProvider = remember { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                popupOrigin = Offset(x.toFloat(), y.toFloat())
                return IntOffset(x, y)
            }
        } },
        onDismissRequest = { dismissAnimated() },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
    val popupGlassColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        modifier = Modifier
            .orbGlassSurface(
                popupGlassColor,
                cornerRadius = 30.dp,
                backdropBlur = 56.dp,
                overlayStrength = true,
            )
            .graphicsLayer {
                val p = reveal.value.coerceIn(0f, 1.2f)
                alpha = p.coerceIn(0f, 1f)
                val scale = 0.82f + 0.18f * p
                scaleX = scale
                scaleY = scale
                translationY = (1f - p.coerceIn(0f, 1f)) * 10.dp.toPx()
            }.shadow(
            elevation = 8.dp,
            shape = RoundedCornerShape(30.dp),
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.65f),
            spotColor = Color.Black.copy(alpha = 0.90f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                .pointerInput(busy) {
                    if (busy) return@pointerInput
                    var position = Offset.Zero
                    fun selectAt(point: Offset) {
                        val step = with(density) { 48.dp.toPx() }
                        val index = kotlin.math.floor(point.x / step).toInt()
                        val next = if (point.y in 0f..size.height.toFloat()) types.getOrNull(index) else null
                        if (next != draggedEmoji) {
                            draggedEmoji = next
                            if (next != null) com.music.orb.ui.components.OrbHaptics.perform(
                                context, com.music.orb.ui.components.OrbHaptics.Kind.NAVIGATION)
                        }
                    }
                    detectDragGestures(
                        onDragStart = { position = it; selectAt(position) },
                        onDragCancel = { draggedEmoji = null },
                        onDragEnd = {
                            draggedEmoji?.let { type -> centers[type]?.let { reactAnimated(type, popupOrigin + it) } }
                            draggedEmoji = null
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            position += delta
                            selectAt(position)
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NowPlayingReactionType.values().forEach { type ->
                var emojiCenter by remember(type) { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .onGloballyPositioned { emojiCenter = it.boundsInRoot().center; centers[type] = emojiCenter }
                        .clip(CircleShape)
                        .background(
                            if (type == (draggedEmoji ?: selected)) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable(enabled = !busy) { reactAnimated(type, popupOrigin + emojiCenter) },
                    contentAlignment = Alignment.Center,
                ) {
                    val scale by animateFloatAsState(if (type == draggedEmoji) 1.22f else 1f,
                        spring(dampingRatio = 0.62f, stiffness = 420f), label = "reactionDragScale")
                    Text(text = type.emoji, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                }
            }
        }
    }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveReactionSummary(
    reactions: List<NowPlayingReaction>,
    busy: Boolean,
    onOpenPicker: () -> Unit,
) {
    val summary = NowPlayingReactionType.values().mapNotNull { type ->
        reactions.count { it.type() == type }
            .takeIf { it > 0 }
            ?.let { count -> type to count }
    }
    Row(
        modifier = Modifier
            .alpha(if (busy) 0.62f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                RoundedCornerShape(18.dp),
            )
            .combinedClickable(
                enabled = !busy,
                onClick = onOpenPicker,
                onLongClick = onOpenPicker,
            )
            .padding(horizontal = if (summary.isEmpty()) 11.dp else 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (summary.isEmpty()) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            summary.forEach { (type, count) ->
                Text(
                    text = buildString {
                        append(type.emoji)
                        if (count > 1) append(' ').append(count)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecommendedProfileCard(
    profile: OrbProfile,
    following: Boolean,
    busy: Boolean,
    onToggleFollow: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onOpenProfile),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar(profile = profile, size = 72)
            Spacer(Modifier.height(9.dp))
            Text(
                text = profile.displayLabel(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            profile.username?.takeIf { it.isNotBlank() }?.let { username ->
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onToggleFollow,
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (following) R.string.friends_following_button else R.string.friends_follow,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchProfileRow(
    profile: OrbProfile,
    following: Boolean,
    busy: Boolean,
    onToggleFollow: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 5.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpenProfile)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile = profile, size = 50)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = profile.displayLabel(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            profile.username?.takeIf { it.isNotBlank() }?.let { username ->
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        FollowButton(
            following = following,
            busy = busy,
            onClick = onToggleFollow,
        )
    }
}

@Composable
private fun FollowingProfileRow(
    profile: OrbProfile,
    nowPlaying: FriendNowPlaying?,
    busy: Boolean,
    onToggleFollow: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 5.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpenProfile)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile = profile, size = 50)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = profile.displayLabel(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            profile.username?.takeIf { it.isNotBlank() }?.let { username ->
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nowPlaying?.isFresh() == true) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${nowPlaying.title} · ${nowPlaying.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        FollowButton(
            following = true,
            busy = busy,
            onClick = onToggleFollow,
        )
    }
}

@Composable
private fun ProfileAvatar(profile: OrbProfile, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
    val iconImage = profile.avatarIconUrl?.takeIf { it.isNotBlank() } ?: profile.avatarUrl
    if (!iconImage.isNullOrBlank()) {
        AsyncImage(
            model = iconImage,
            contentDescription = profile.displayName.takeIf { it.isNotBlank() },
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size((size * 0.5f).dp),
            )
        }
    }
}

@Composable
private fun Artwork(nowPlaying: FriendNowPlaying, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(RoundedCornerShape(16.dp))
    if (!nowPlaying.artworkUrl.isNullOrBlank()) {
        AsyncImage(
            model = nowPlaying.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size((size * 0.36f).dp),
            )
        }
    }
}

@Composable
private fun FollowButton(
    following: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = stringResource(
                    if (following) R.string.friends_following_button else R.string.friends_follow,
                ),
                maxLines = 1,
            )
        }
    }
    if (following) {
        OutlinedButton(
            onClick = onClick,
            enabled = !busy,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            content = { content() },
        )
    } else {
        Button(
            onClick = onClick,
            enabled = !busy,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            content = { content() },
        )
    }
}

@Composable
private fun StateCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(18.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SimpleMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 22.dp),
    )
}

private fun OrbProfile.displayLabel(): String =
    displayName.trim().takeIf { it.isNotBlank() }
        ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
        ?: "Orb"
private const val TOP_ARTIST_ART_PX = 1280
private const val STATS_TILE_ART_PX = 720
private const val LIVE_CARD_ENTRANCE_MS = 720L
