package com.music.orb.ui.screens

import android.content.res.Configuration
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.music.orb.R
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.model.artworkAt
import com.music.orb.data.social.FriendNowPlaying
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.NowPlayingReaction
import com.music.orb.data.social.NowPlayingReactionType
import com.music.orb.data.social.OrbProfile
import com.music.orb.data.social.reactionSessionKey
import com.music.orb.ui.FriendsViewModel
import com.music.orb.ui.ListeningStats
import com.music.orb.ui.FollowNotification
import com.music.orb.ui.ReactionNotification
import com.music.orb.ui.StatsNotification
import com.music.orb.ui.StatsLeader
import com.music.orb.ui.StatsPeriod
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FriendsScreen(
    listState: LazyListState,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    friendsViewModel: FriendsViewModel = viewModel(),
    onOpenProfile: (OrbProfile) -> Unit = {},
) {
    val query by friendsViewModel.query.collectAsStateWithLifecycle()
    val searchResults by friendsViewModel.searchResults.collectAsStateWithLifecycle()
    val recentSearchProfiles by friendsViewModel.recentSearchProfiles.collectAsStateWithLifecycle()
    val recommendedProfiles by friendsViewModel.recommendedProfiles.collectAsStateWithLifecycle()
    val recommendationsLoading by friendsViewModel.recommendationsLoading.collectAsStateWithLifecycle()
    val userSearchOpen by friendsViewModel.userSearchOpen.collectAsStateWithLifecycle()
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
    val notificationsOpen by friendsViewModel.notificationsOpen.collectAsStateWithLifecycle()
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
    val statsLoading by friendsViewModel.statsLoading.collectAsStateWithLifecycle()
    val statsLoadFailed by friendsViewModel.statsLoadFailed.collectAsStateWithLifecycle()
    val artistRankingOpen by friendsViewModel.artistRankingOpen.collectAsStateWithLifecycle()
    val artistRanking by friendsViewModel.artistRanking.collectAsStateWithLifecycle()
    val artistRankingLoading by friendsViewModel.artistRankingLoading.collectAsStateWithLifecycle()

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

    LaunchedEffect(artistRankingOpen, notificationsOpen) {
        // Ranking/notifications replace the LazyColumn contents while reusing the
        // same state. Jump to the new list origin immediately so stale scroll
        // offsets cannot hide the first rows during the screen transition.
        if (artistRankingOpen || notificationsOpen) listState.scrollToItem(0)
        if (artistRankingOpen || notificationsOpen) showTopArtistRankingHint = false
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
                                key = { _, entry -> "artist-ranking:${entry.stableKey}" },
                            ) { index, entry ->
                                ArtistRankingEntrance(
                                    index = index,
                                    listState = listState,
                                    itemKey = "artist-ranking:${entry.stableKey}",
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
                        item(key = "stats-dashboard") {
                            Box(
                                modifier = statsVerticalEdgeModifier(
                                    listState = listState,
                                    itemKey = "stats-dashboard",
                                ),
                            ) {
                                StatsDashboard(
                                    stats = listeningStats,
                                    period = statsPeriod,
                                    loading = statsLoading,
                                    loadFailed = statsLoadFailed,
                                    onPeriodSelected = friendsViewModel::setStatsPeriod,
                                    onTopArtistClick = friendsViewModel::openArtistRanking,
                                    showTopArtistRankingHint = showTopArtistRankingHint,
                                )
                            }
                        }

                        item(key = "stats-share-button") {
                            ShareStatsButton(
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
                                            modifier = statsVerticalEdgeModifier(
                                                listState = listState,
                                                itemKey = "feed:${activity.reactionSessionKey()}",
                                            ).animateItem(
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
                    }
                }
            }
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
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
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

@Composable
private fun StatsSharePreviewDialog(
    bitmap: android.graphics.Bitmap,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .clickable(enabled = false) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.stats_share_preview_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
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
            }
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


@Composable
private fun StatsDashboard(
    stats: ListeningStats,
    period: StatsPeriod,
    loading: Boolean,
    loadFailed: Boolean,
    onPeriodSelected: (StatsPeriod) -> Unit,
    onTopArtistClick: () -> Unit,
    showTopArtistRankingHint: Boolean,
) {
    val configuration = LocalConfiguration.current
    val tabletLandscape = configuration.smallestScreenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        // All Stats cards live on the same horizontal rail. The taste block
        // intentionally keeps genres + languages stacked so it occupies one
        // position in the sequence without changing their compact treatment.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val railGap = 12.dp
            val availableWidth = maxWidth - PAGE_GUTTER * 2f
            // Tablet landscape should read like a rail, not a sequence of
            // nearly full-screen posters. Keeping fixed, compact card widths
            // leaves the artwork and the card label visible together and
            // exposes the next card as a strong scroll affordance.
            val featureWidth = if (tabletLandscape) 360.dp else availableWidth * 0.92f
            val mediaCardWidth = if (tabletLandscape) 190.dp else (availableWidth - railGap) / 2f
            val cardHeight = 302.dp
            val compactInsightHeight = (cardHeight - railGap) / 2f

            val railState = rememberLazyListState()
            // Track the direction of the gesture so only the card that is
            // leaving the viewport fades. Incoming/peek cards remain fully
            // present, and reversing the gesture mirrors the effect naturally.
            var railScrollDirection by remember { mutableStateOf(1) } // 1 = forward/left, -1 = back/right
            LaunchedEffect(railState) {
                var lastIndex = railState.firstVisibleItemIndex
                var lastOffset = railState.firstVisibleItemScrollOffset
                snapshotFlow {
                    railState.firstVisibleItemIndex to railState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    railScrollDirection = when {
                        index > lastIndex -> 1
                        index < lastIndex -> -1
                        offset > lastOffset -> 1
                        offset < lastOffset -> -1
                        else -> railScrollDirection
                    }
                    lastIndex = index
                    lastOffset = offset
                }
            }
            androidx.compose.foundation.lazy.LazyRow(
                state = railState,
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(railGap),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "stats-feature-top-artist") {
                    Box(modifier = Modifier.width(featureWidth)) {
                        StatsRailEdgeFade(railState, "stats-feature-top-artist", railScrollDirection) {
                            TopArtistFeatureCard(
                                leader = stats.topArtist,
                                period = period,
                                onClick = onTopArtistClick,
                                cardHeight = cardHeight,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (showTopArtistRankingHint) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 14.dp, start = 14.dp, end = 14.dp)
                                    .zIndex(2f),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                tonalElevation = 3.dp,
                                shadowElevation = 5.dp,
                            ) {
                                Text(
                                    text = stringResource(R.string.stats_artist_ranking_hint),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }

                item(key = "stats-top-song") {
                    StatsRailEdgeFade(railState, "stats-top-song", railScrollDirection) {
                        LeaderStatCard(
                            label = stringResource(R.string.stats_top_song),
                            leader = stats.topSong,
                            emptyText = stringResource(R.string.stats_no_data_short),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            cardHeight = cardHeight,
                            modifier = Modifier.width(mediaCardWidth),
                        )
                    }
                }

                item(key = "stats-top-album") {
                    StatsRailEdgeFade(railState, "stats-top-album", railScrollDirection) {
                        LeaderStatCard(
                            label = stringResource(R.string.stats_top_album),
                            leader = stats.topAlbum,
                            emptyText = stringResource(R.string.stats_no_data_short),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            cardHeight = cardHeight,
                            modifier = Modifier.width(mediaCardWidth),
                        )
                    }
                }

                item(key = "stats-taste") {
                    StatsRailEdgeFade(railState, "stats-taste", railScrollDirection) {
                        Column(
                            modifier = Modifier.width(mediaCardWidth),
                            verticalArrangement = Arrangement.spacedBy(railGap),
                        ) {
                            TopGenreStatCard(
                                genres = stats.topGenres.ifEmpty { listOfNotNull(stats.topGenre) },
                                cardHeight = compactInsightHeight,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TopLanguageStatCard(
                                languages = stats.topLanguages.ifEmpty { listOfNotNull(stats.topLanguage) },
                                cardHeight = compactInsightHeight,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                item(key = "stats-top-playlist") {
                    StatsRailEdgeFade(railState, "stats-top-playlist", railScrollDirection) {
                        LeaderStatCard(
                            label = stringResource(R.string.stats_top_playlist),
                            leader = stats.topPlaylist,
                            emptyText = stringResource(R.string.stats_playlist_no_data),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            cardHeight = cardHeight,
                            modifier = Modifier.width(mediaCardWidth),
                        )
                    }
                }

                item(key = "stats-feature-rhythm") {
                    StatsRailEdgeFade(railState, "stats-feature-rhythm", railScrollDirection) {
                        StatsHeroCard(
                            stats = stats,
                            period = period,
                            loading = loading,
                            cardHeight = cardHeight,
                            modifier = Modifier.width(featureWidth),
                        )
                    }
                }
            }
        }

        if (loadFailed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.stats_load_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 4.dp),
            )
        } else if (!loading && stats.trackCount == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.stats_no_data_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 4.dp),
            )
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
            AsyncImage(
                model = leader?.artworkUrl.artworkAt(TOP_ARTIST_ART_PX),
                contentDescription = leader?.title,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
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
) {
    val comparison = stats.comparisonPercent
    Column(
        modifier = modifier
            .height(cardHeight)
            .clip(RoundedCornerShape(34.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_summary_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.stats_summary_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                .height(104.dp),
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_comparison_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = comparison?.let { if (it > 0) "+$it%" else "$it%" } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val gradientStart = Color(0xFF9C4DFF)
    val gradientEnd = Color(0xFF27C7FF)
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
            .minusDays((now.dayOfWeek.value - 1).toLong())
            .atStartOfDay(now.zone)

        StatsPeriod.MONTH -> now
            .withDayOfMonth(1)
            .toLocalDate()
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
            val monthEnd = periodStart.toLocalDate().withDayOfMonth(
                periodStart.toLocalDate().lengthOfMonth(),
            )
            val lastDay = firstDay.plusDays(6).let { if (it > monthEnd) monthEnd else it }
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
private fun StatsRailEdgeFade(
    listState: LazyListState,
    itemKey: String,
    scrollDirection: Int,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val minFadeDistancePx = with(density) { 104.dp.toPx() }
    val edgePaddingPx = with(density) { PAGE_GUTTER.toPx() }
    val edgeState by remember(listState, itemKey, minFadeDistancePx, edgePaddingPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Triple(1f, 0f, 1f)
            val itemStart = info.offset.toFloat()
            val itemEnd = itemStart + info.size.toFloat()
            val leftEdge = edgePaddingPx
            val rightEdge = layout.viewportSize.width.toFloat() - edgePaddingPx

            // The animation begins while the card is entering the viewport, not
            // only after it has already crossed an edge. Wider cards keep a
            // proportionally longer runway, so the featured artist card takes
            // longer to disappear than compact cards.
            val fadeDistance = maxOf(minFadeDistancePx, info.size * 0.72f)
            val leftProgress = ((itemEnd - leftEdge) / fadeDistance).coerceIn(0f, 1f)
            val rightProgress = ((rightEdge - itemStart) / fadeDistance).coerceIn(0f, 1f)
            val raw = minOf(leftProgress, rightProgress)
            val smooth = raw * raw * (3f - 2f * raw)
            val translateDirection = when {
                itemEnd < leftEdge + fadeDistance -> -1f
                itemStart > rightEdge - fadeDistance -> 1f
                else -> 0f
            }
            Triple(smooth.coerceAtLeast(0.03f), translateDirection, fadeDistance)
        }
    }
    val edgeFade = edgeState.first
    val direction = edgeState.second
    Box(
        modifier = Modifier
            .alpha(edgeFade)
            .offset(x = (10.dp * direction) * (1f - edgeFade))
            .graphicsLayer {
                // Incoming cards now grow into place as they enter either edge,
                // while outgoing cards shrink visibly enough to read as depth.
                val scale = 0.82f + 0.18f * edgeFade
                scaleX = scale
                scaleY = scale
            },
    ) {
        content()
    }
}

@Composable
private fun statsVerticalEdgeModifier(
    listState: LazyListState,
    itemKey: String,
): Modifier {
    val density = LocalDensity.current
    val minDistancePx = with(density) { 76.dp.toPx() }
    // Stats content scrolls underneath a frosted top bar. Using the logical
    // viewport start (which includes negative content padding) makes rows stay
    // full-size until they are already hidden. Track the visual header edge.
    val topOcclusionPx = with(density) { 144.dp.toPx() }
    val edgeState by remember(listState, itemKey, minDistancePx, topOcclusionPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val start = info.offset.toFloat()
            val end = start + info.size.toFloat()
            // visibleItemsInfo offsets are expressed in the LazyColumn content
            // coordinate space. The fixed-header height is screen-relative, so
            // translate it by viewportStartOffset (=-top content padding). Using
            // topOcclusionPx as an absolute offset double-counted the padding and
            // made the first Stats rows shrink/fade before any scroll happened.
            val top = layout.viewportStartOffset.toFloat() + topOcclusionPx
            val bottom = layout.viewportEndOffset.toFloat()
            val distance = maxOf(minDistancePx, info.size * 0.92f)
            val topProgress = if (start >= top) {
                1f
            } else {
                ((end - top) / info.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
            val bottomProgress = ((bottom - start) / distance).coerceIn(0f, 1f)
            val raw = minOf(topProgress, bottomProgress)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = when {
                start < top -> -1f
                start > bottom - distance -> 1f
                else -> 0f
            }
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }
    val edge = edgeState.first
    val direction = edgeState.second
    return Modifier
        .alpha(edge)
        .offset(y = 10.dp * direction * (1f - edge))
        .graphicsLayer {
            val scale = 0.84f + 0.16f * edge
            scaleX = scale
            scaleY = scale
        }
}

@Composable
private fun ArtistRankingEntrance(
    index: Int,
    listState: LazyListState,
    itemKey: String,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val minFadeDistancePx = with(density) { 72.dp.toPx() }
    val topOcclusionPx = with(density) { 144.dp.toPx() }

    LaunchedEffect(Unit) {
        delay((index * 72L).coerceAtMost(720L))
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = 260f,
                visibilityThreshold = 0.001f,
            ),
        )
    }

    // Apply the same depth logic at both vertical edges. Rows entering from
    // below grow into place instead of popping in at full scale, and rows that
    // leave through the top shrink/fade progressively.
    val edgeState by remember(listState, itemKey, minFadeDistancePx, topOcclusionPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf Pair(1f, 0f)
            val itemStart = info.offset.toFloat()
            val itemEnd = itemStart + info.size.toFloat()
            // Same coordinate conversion as statsVerticalEdgeModifier: the
            // fixed header is already reserved by content padding, so its visual
            // lower edge is viewportStartOffset + header height, not header
            // height measured again from the LazyColumn content origin.
            val topEdge = layout.viewportStartOffset.toFloat() + topOcclusionPx
            val bottomEdge = layout.viewportEndOffset.toFloat()
            val fadeDistance = maxOf(minFadeDistancePx, info.size * 0.95f)
            // At the natural top of the ranking, the #1 card must be full-size.
            // Only activate the top-edge shrink once the user has actually
            // scrolled the list upward; otherwise the fixed Stats header would
            // make the first card look permanently compressed.
            val hasScrolledFromTop = listState.canScrollBackward
            val topProgress = if (!hasScrolledFromTop || itemStart >= topEdge) {
                1f
            } else {
                ((itemEnd - topEdge) / info.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
            val bottomProgress = ((bottomEdge - itemStart) / fadeDistance).coerceIn(0f, 1f)
            val raw = minOf(topProgress, bottomProgress)
            val smooth = raw * raw * (3f - 2f * raw)
            val direction = when {
                itemStart < topEdge && hasScrolledFromTop -> -1f
                itemStart > bottomEdge - fadeDistance -> 1f
                else -> 0f
            }
            Pair(smooth.coerceAtLeast(0.04f), direction)
        }
    }

    val p = progress.value.coerceIn(0f, 1.08f)
    val entrance = p.coerceIn(0f, 1f)
    val edgeFade = edgeState.first
    val edgeDirection = edgeState.second
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(
                y = 42.dp * (1f - p) + 10.dp * edgeDirection * (1f - edgeFade),
            )
            .alpha(entrance * edgeFade)
            .graphicsLayer {
                val entranceScale = 0.94f + (0.06f * entrance)
                val edgeScale = 0.82f + (0.18f * edgeFade)
                val scale = entranceScale * edgeScale
                scaleX = scale
                scaleY = scale
            },
    ) {
        content()
    }
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
                AsyncImage(
                    model = leader.artworkUrl.artworkAt(ROW_ART_PX),
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
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatsPeriodSelector(
    selected: StatsPeriod,
    onSelected: (StatsPeriod) -> Unit,
) {
    val periods = StatsPeriod.values().toList()
    val selectedIndex = periods.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState()

    // Match Home's filter behavior: when the active option changes, keep the
    // selected pill visible even on narrower screens.
    LaunchedEffect(selectedIndex, periods.size) {
        if (periods.isNotEmpty()) {
            listState.animateScrollToCenteredItem(selectedIndex.coerceIn(periods.indices))
        }
    }

    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(periods) { _, period ->
            val active = selected == period
            val label = when (period) {
                StatsPeriod.WEEK -> stringResource(R.string.stats_period_week)
                StatsPeriod.MONTH -> stringResource(R.string.stats_period_month)
                StatsPeriod.QUARTER -> stringResource(R.string.stats_period_quarter)
                StatsPeriod.SEMESTER -> stringResource(R.string.stats_period_semester)
                StatsPeriod.YEAR -> stringResource(R.string.stats_period_year)
            }
            val background = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val outline = if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            }
            val chipShapeRadius by animateDpAsState(
                targetValue = if (active) 22.dp else 15.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "statsPeriodShape",
            )
            val chipScale by animateFloatAsState(
                targetValue = if (active) 1.04f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "statsPeriodScale",
            )
            val animatedBackground by animateColorAsState(
                targetValue = background,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "statsPeriodBackground",
            )
            val animatedOutline by animateColorAsState(
                targetValue = outline,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "statsPeriodOutline",
            )
            val chipShape = RoundedCornerShape(chipShapeRadius)

            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = chipScale
                        scaleY = chipScale
                    }
                    .clip(chipShape)
                    .background(animatedBackground)
                    .border(1.dp, animatedOutline, chipShape)
                    .clickable { onSelected(period) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun LeaderStatCard(
    label: String,
    leader: StatsLeader?,
    emptyText: String,
    containerColor: Color,
    contentColor: Color,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(RoundedCornerShape(28.dp))
            .background(containerColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
        )
        Spacer(Modifier.height(6.dp))
        // Let the artwork become part of the card silhouette instead of
        // looking like a small thumbnail floating inside another card.
        StatsCardArtwork(
            url = leader?.artworkUrl,
            title = leader?.title,
            contentColor = contentColor,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = leader?.title ?: emptyText,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        leader?.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun StatsCardArtwork(
    url: String?,
    title: String?,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url.artworkAt(STATS_TILE_ART_PX),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(contentColor.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.72f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
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
    val primary = ranked.firstOrNull()
    val runnersUp = ranked.drop(1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(RoundedCornerShape(28.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = stringResource(if (ranked.size > 1) pluralLabel else singularLabel),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = primary ?: stringResource(R.string.stats_no_data_short),
            style = (if (ranked.size >= 5) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                .copy(fontWeight = FontWeight.Bold),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        runnersUp.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.74f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val haptic = LocalHapticFeedback.current
    val reactionCardScale by animateFloatAsState(
        targetValue = if (reactionPickerOpen) 1.018f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
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
        val myReaction = reactions
            .firstOrNull { it.reactorId == currentUserId }
            ?.type()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp, bottom = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(containerColor)
                        .border(1.dp, contentColor.copy(alpha = 0.28f), RoundedCornerShape(32.dp))
                        .graphicsLayer {
                            scaleX = reactionCardScale
                            scaleY = reactionCardScale
                        }
                        .combinedClickable(
                            onClick = { },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                reactionPickerOpen = true
                            },
                        )
                        .padding(start = 16.dp, top = 20.dp, end = 14.dp, bottom = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 37.dp, end = 10.dp),
                    ) {
                        Text(
                            text = if (isLive) {
                                stringResource(R.string.friends_is_listening_now)
                            } else {
                                pastListeningLabel(activity.finishedAt, clockMs)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.78f),
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = nowPlaying.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = nowPlaying.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        nowPlaying.album?.takeIf { it.isNotBlank() }?.let { album ->
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = album,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.76f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Artwork(nowPlaying = nowPlaying, size = 84)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 59.dp, top = 5.dp, end = 12.dp)
                        .clickable(onClick = onOpenProfile),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-13).dp, y = 12.dp)
                        .zIndex(3f),
                ) {
                    LiveReactionSummary(
                        reactions = reactions,
                        busy = reactionBusy,
                        onOpenPicker = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            reactionPickerOpen = true
                        },
                    )
                }

                if (reactionPickerOpen) {
                    FriendReactionPickerBubble(
                        selected = myReaction,
                        busy = reactionBusy,
                        onDismiss = { reactionPickerOpen = false },
                        onReact = { type ->
                            reactionPickerOpen = false
                            onReact(type)
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
    onReact: (NowPlayingReactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.58f,
                stiffness = 320f,
                visibilityThreshold = 0.001f,
            ),
        )
    }
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 22.dp,
        modifier = modifier.graphicsLayer {
            val p = reveal.value.coerceIn(0f, 1.08f)
            alpha = p.coerceIn(0f, 1f)
            val scale = 0.82f + 0.18f * p
            scaleX = scale
            scaleY = scale
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NowPlayingReactionType.values().forEach { type ->
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (type == selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable(enabled = !busy) { onReact(type) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = type.emoji, style = MaterialTheme.typography.titleLarge)
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
                onClick = { },
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
