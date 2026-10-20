@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.music.orb.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import com.music.orb.ui.components.OrbGlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.music.orb.ui.components.OrbGlassFilterChip as FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import com.music.orb.ui.components.orbExpressiveGlassTone
import com.music.orb.ui.components.OrbGlassContentTheme
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.orbBadgeGlassSurface
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.Text
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.orb.R
import com.music.orb.data.ArtistCreditResolver
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.data.canvas.normalizeOrbGenre
import com.music.orb.data.model.Account
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.CachedProfileSnapshot
import com.music.orb.data.settings.CachedProfileTaste
import com.music.orb.data.settings.ProfileAudience
import com.music.orb.data.settings.ProfilePrivacyStore
import com.music.orb.data.settings.ProfileSnapshotStore
import com.music.orb.data.social.FriendNowPlaying
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.ListeningActivityChange
import com.music.orb.data.social.OrbProfile
import com.music.orb.data.social.ProfileAlbumRatingRow
import com.music.orb.data.social.ProfileSocialCounts
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.social.reachedListenThreshold
import com.music.orb.ui.currentMonthListeningActivity
import com.music.orb.ui.currentMonthStartInstant
import com.music.orb.ui.rankListeningAlbums
import com.music.orb.ui.rankListeningArtists
import com.music.orb.ui.rankListeningTracks
import com.music.orb.ui.components.ArtworkBottomEdgeField
import com.music.orb.ui.components.createBottomEdgeField
import com.music.orb.ui.components.ArtworkMergeBand
import com.music.orb.ui.components.ARTWORK_LANDSCAPE_FLOW_WIDTH
import com.music.orb.ui.components.ARTWORK_LANDSCAPE_FLOW_OVERLAP
import com.music.orb.ui.components.artworkLandscapeFlowMask
import com.music.orb.ui.components.landscapeArtworkFade
import com.music.orb.ui.components.ARTWORK_MERGE_BAND
import com.music.orb.ui.components.ARTWORK_MERGE_OVERLAP
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.unit.IntOffset
import com.music.orb.ui.components.bestForegroundForArtworkSurface
import com.music.orb.ui.components.rememberArtworkBottomEdgeField
import com.music.orb.ui.components.rememberArtworkRightEdgeField
import com.music.orb.ui.components.PAGE_GUTTER
import com.music.orb.ui.components.OrbHaptics
import com.music.orb.ui.theme.ArtworkPalette
import com.music.orb.ui.theme.rememberArtworkPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val PROFILE_IMAGE_MAX_BYTES = 50 * 1024 * 1024
private val PROFILE_USERNAME_REGEX = Regex("^[a-z0-9._]{3,30}$")

private data class ImmersiveProfileTaste(
    val name: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val plays: Int,
    val videoId: String? = null,
)

private val ImmersiveProfileTaste.isTrack: Boolean
    get() = !videoId.isNullOrBlank()

private val ImmersiveProfileTaste.isArtist: Boolean
    get() = !isTrack && subtitle == null

private val ImmersiveProfileTaste.isAlbum: Boolean
    get() = !isTrack && subtitle != null

private fun CachedProfileTaste.toImmersiveTaste(): ImmersiveProfileTaste = ImmersiveProfileTaste(
    name = name,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    plays = plays,
    videoId = videoId,
)

private fun ImmersiveProfileTaste.toCachedTaste(): CachedProfileTaste = CachedProfileTaste(
    name = name,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    plays = plays,
    videoId = videoId,
)

private data class ImmersiveCompatibility(
    val percent: Int,
    val genres: List<String> = emptyList(),
)

private data class ProfileAlbumRatingCard(
    val albumId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val rating: Float,
    val updatedAt: String,
)

private enum class ProfilePeoplePage {
    FOLLOWING,
    FOLLOWERS,
}

private data class ProfileImageCrop(
    val uri: Uri,
    val zoom: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
)

private data class ProfileImageSelection(
    val portrait: ProfileImageCrop,
    val icon: ProfileImageCrop,
)

private enum class ProfilePhotoEditorStage {
    PROFILE,
    ICON,
}

data class ProfileTopBarInfo(
    val title: String,
    val subtitle: String?,
    val pageColor: Color,
    val contentColor: Color,
    val contentVariantColor: Color,
    val collapseOffsetPx: Float,
)

private const val PROFILE_CROP_ASPECT = 1f
private const val PROFILE_CROP_WIDTH_PX = 1080
private const val PROFILE_CROP_HEIGHT_PX = 1080
private const val PROFILE_ICON_SIZE_PX = 1024
private val PROFILE_TITLE_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.52f),
    offset = Offset(0f, 2.6f),
    blurRadius = 14f,
)
private val PROFILE_USERNAME_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.42f),
    offset = Offset(0f, 2.0f),
    blurRadius = 11f,
)

@Composable
fun OrbProfileScreen(
    account: Account?,
    initialProfile: OrbProfile? = null,
    contentPadding: PaddingValues,
    onBack: () -> Unit = {},
    onAccountDeleted: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    onTopBarInfoChange: (ProfileTopBarInfo) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listeningAudience by ProfilePrivacyStore.listeningAudience.collectAsStateWithLifecycle()
    val followingAudience by ProfilePrivacyStore.followingAudience.collectAsStateWithLifecycle()
    val followersAudience by ProfilePrivacyStore.followersAudience.collectAsStateWithLifecycle()
    val playCountAudience by ProfilePrivacyStore.playCountAudience.collectAsStateWithLifecycle()
    val favoriteArtistsAudience by ProfilePrivacyStore.favoriteArtistsAudience.collectAsStateWithLifecycle()
    val favoriteAlbumsAudience by ProfilePrivacyStore.favoriteAlbumsAudience.collectAsStateWithLifecycle()
    val favoriteSongsAudience by ProfilePrivacyStore.favoriteSongsAudience.collectAsStateWithLifecycle()
    val recentRatingsAudience by ProfilePrivacyStore.recentRatingsAudience.collectAsStateWithLifecycle()
    val ownUserId = SocialRepository.currentUserId()
    val cachedSnapshot = remember(ownUserId) { ownUserId?.let(ProfileSnapshotStore::load) }

    var profile by remember(initialProfile?.id, ownUserId) {
        mutableStateOf(initialProfile ?: cachedSnapshot?.profile)
    }
    var following by remember(initialProfile?.id) { mutableStateOf<List<OrbProfile>>(emptyList()) }
    var followers by remember(initialProfile?.id) { mutableStateOf<List<OrbProfile>>(emptyList()) }
    var counts by remember(initialProfile?.id, ownUserId) { mutableStateOf(cachedSnapshot?.counts) }
    var playCount by remember(initialProfile?.id, ownUserId) { mutableStateOf(cachedSnapshot?.playCount ?: 0L) }
    var tastes by remember(initialProfile?.id, ownUserId) {
        mutableStateOf(cachedSnapshot?.tastes.orEmpty().map { it.toImmersiveTaste() })
    }
    var recentAlbumRatings by remember(ownUserId) { mutableStateOf<List<ProfileAlbumRatingCard>>(emptyList()) }
    var profileRankingActivity by remember(ownUserId) { mutableStateOf<List<ListeningActivity>>(emptyList()) }
    var profileRankingActivityLoaded by remember(ownUserId) { mutableStateOf(false) }
    var loading by remember(initialProfile?.id, ownUserId) { mutableStateOf(cachedSnapshot == null) }
    var peoplePage by remember(initialProfile?.id) { mutableStateOf<ProfilePeoplePage?>(null) }
    var profileScrollRestore by remember(initialProfile?.id) { mutableStateOf(0) }
    val sessionProfile by SocialRepository.myProfileState.collectAsStateWithLifecycle()

    LaunchedEffect(
        sessionProfile?.id,
        sessionProfile?.displayName,
        sessionProfile?.username,
        sessionProfile?.avatarUrl,
        sessionProfile?.avatarIconUrl,
    ) {
        if (sessionProfile?.id == SocialRepository.currentUserId()) {
            profile = sessionProfile
        }
    }

    var editing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editUsername by remember { mutableStateOf("") }
    var editListeningAudience by remember { mutableStateOf(ProfileAudience.FOLLOWERS) }
    var editFollowingAudience by remember { mutableStateOf(ProfileAudience.EVERYONE) }
    var editFollowersAudience by remember { mutableStateOf(ProfileAudience.EVERYONE) }
    var editPlayCountAudience by remember { mutableStateOf(ProfileAudience.EVERYONE) }
    var editFavoriteArtistsAudience by remember { mutableStateOf(ProfileAudience.EVERYONE) }
    var editFavoriteAlbumsAudience by remember { mutableStateOf(ProfileAudience.EVERYONE) }
    var editFavoriteSongsAudience by remember { mutableStateOf(ProfileAudience.EVERYONE) }
    var editRecentRatingsAudience by remember { mutableStateOf(ProfileAudience.NOBODY) }
    var selectedAvatar by remember { mutableStateOf<ProfileImageSelection?>(null) }
    var cropEditorUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editErrorRes by remember { mutableStateOf<Int?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val validation = runCatching { isProfileSourceWithinLimit(context, uri) }
                when {
                    validation.isFailure -> editErrorRes = R.string.profile_photo_cannot_open
                    validation.getOrNull() == false -> editErrorRes = R.string.profile_photo_too_large
                    else -> {
                        cropEditorUri = uri
                        editErrorRes = null
                    }
                }
            }
        }
    }

    suspend fun loadProfile() {
        val userId = SocialRepository.currentUserId()
        if (userId == null) {
            loading = false
            return
        }
        loading = profile == null && tastes.isEmpty() && counts == null
        coroutineScope {
            val profileTask = async { runCatching { SocialRepository.profile(userId) }.getOrNull() }
            val followingTask = async { runCatching { SocialRepository.followingProfiles() }.getOrDefault(emptyList()) }
            val followersTask = async { runCatching { SocialRepository.followersProfiles() }.getOrDefault(emptyList()) }
            val countsTask = async { runCatching { SocialRepository.socialCounts(userId) }.getOrNull() }
            val activityTask = async {
                runCatching {
                    SocialRepository.myListeningActivitySince(
                        since = currentMonthStartInstant(),
                        maxRows = 20_000,
                    )
                }
            }
            val playsTask = async { runCatching { SocialRepository.profilePlayCount(userId) } }
            val ratingsTask = async {
                runCatching {
                    resolveProfileAlbumRatings(SocialRepository.albumRatingsForUser(userId, limit = 10))
                }.getOrDefault(emptyList())
            }

            profile = profileTask.await() ?: profile ?: initialProfile
            following = followingTask.await()
            followers = followersTask.await()
            counts = countsTask.await() ?: counts
            val activityResult = activityTask.await()
            val activities = activityResult.getOrDefault(emptyList())
            val qualifiedActivities = activities.filter { it.reachedListenThreshold() }
            val initialTastes = if (activityResult.isSuccess) {
                profileRankingActivity = activities
                profileRankingActivityLoaded = true
                ArtistCreditResolver.resolveAll(activities.map { it.artist })
                buildImmersiveTasteEntries(activities).also { tastes = it }
            } else {
                // Cold-start cache remains visible if Supabase is slow/offline.
                // A failed refresh must not replace useful persisted data with
                // an empty profile while the request is still recovering.
                tastes
            }
            playCount = maxOf(
                playCount,
                playsTask.await().getOrDefault(playCount),
                if (activityResult.isSuccess) qualifiedActivities.size.toLong() else 0L,
            )
            recentAlbumRatings = ratingsTask.await()
            ProfileSnapshotStore.save(
                userId,
                CachedProfileSnapshot(
                    profile = profile,
                    counts = counts,
                    playCount = playCount,
                    tastes = tastes.map { it.toCachedTaste() },
                ),
            )
            profile?.let {
                ProfilePrivacyStore.syncFromProfile(
                    nowPlayingVisibility = it.nowPlayingVisibility,
                    listeningVisibility = it.listeningVisibility,
                    followingVisibility = it.followingVisibility,
                    followersVisibility = it.followersVisibility,
                    playCountVisibility = it.playCountVisibility,
                    favoriteContentVisibility = it.favoriteContentVisibility,
                    favoriteArtistsVisibility = it.favoriteArtistsVisibility,
                    favoriteAlbumsVisibility = it.favoriteAlbumsVisibility,
                    favoriteSongsVisibility = it.favoriteSongsVisibility,
                    recentRatingsVisibility = it.recentRatingsVisibility,
                )
            }
            loading = false
            enrichImmersiveArtistTastes(initialTastes) { artist, artwork ->
                tastes = tastes.withImmersiveArtistArtwork(artist, artwork)
            }
            ProfileSnapshotStore.save(
                userId,
                CachedProfileSnapshot(
                    profile = profile,
                    counts = counts,
                    playCount = playCount,
                    tastes = tastes.map { it.toCachedTaste() },
                ),
            )
        }
    }

    LaunchedEffect(initialProfile?.id, ownUserId) { loadProfile() }
    LaunchedEffect(ownUserId, profileRankingActivityLoaded) {
        val userId = ownUserId ?: return@LaunchedEffect
        if (!profileRankingActivityLoaded) return@LaunchedEffect

        SocialRepository.listeningActivityChangesForUsers(listOf(userId))
            .catch { /* The next profile load reconciles if Realtime drops. */ }
            .collect { change ->
                when (change) {
                    is ListeningActivityChange.Upsert -> {
                        val item = change.value
                        if (item.userId != userId) return@collect
                        val key = item.videoId to item.startedAt
                        var replaced = false
                        val updated = profileRankingActivity.map { existing ->
                            if ((existing.videoId to existing.startedAt) == key) {
                                replaced = true
                                item
                            } else {
                                existing
                            }
                        }
                        profileRankingActivity = if (replaced) updated else updated + item

                        ArtistCreditResolver.resolveAll(listOf(item.artist))
                        val refreshedTastes = buildImmersiveTasteEntries(profileRankingActivity)
                        tastes = refreshedTastes
                        ProfileSnapshotStore.save(
                            userId,
                            CachedProfileSnapshot(
                                profile = profile,
                                counts = counts,
                                playCount = playCount,
                                tastes = tastes.map { it.toCachedTaste() },
                            ),
                        )

                        enrichImmersiveArtistTastes(refreshedTastes) { artist, artwork ->
                            tastes = tastes.withImmersiveArtistArtwork(artist, artwork)
                        }
                        ProfileSnapshotStore.save(
                            userId,
                            CachedProfileSnapshot(
                                profile = profile,
                                counts = counts,
                                playCount = playCount,
                                tastes = tastes.map { it.toCachedTaste() },
                            ),
                        )
                    }
                    is ListeningActivityChange.Remove -> Unit
                }
            }
    }
    BackHandler(enabled = editing && cropEditorUri == null) { if (!saving) editing = false }
    BackHandler(enabled = peoplePage != null && !editing && cropEditorUri == null) {
        peoplePage = null
        scope.launch { scrollState.scrollTo(profileScrollRestore) }
    }

    val avatar = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: account?.thumbnailUrl
    val avatarIcon = profile?.avatarIconUrl?.takeIf { it.isNotBlank() } ?: avatar
    val palette = rememberArtworkPalette(avatar)

    cropEditorUri?.let { cropUri ->
        ProfilePhotoCropScreen(
            uri = cropUri,
            displayName = editName.ifBlank { profile?.displayName.orEmpty() },
            username = editUsername.ifBlank { profile?.username.orEmpty() },
            onCancel = { cropEditorUri = null },
            onConfirm = { selection ->
                selectedAvatar = selection
                cropEditorUri = null
                editErrorRes = null
            },
        )
        return
    }

    if (editing) {
        ProfileEditScreen(
            currentAvatar = avatar,
            currentAvatarIcon = avatarIcon,
            selectedAvatar = selectedAvatar,
            name = editName,
            username = editUsername,
            usernameLockedUntil = profile.usernameLockedUntil(),
            listeningAudience = editListeningAudience,
            followingAudience = editFollowingAudience,
            followersAudience = editFollowersAudience,
            playCountAudience = editPlayCountAudience,
            favoriteArtistsAudience = editFavoriteArtistsAudience,
            favoriteAlbumsAudience = editFavoriteAlbumsAudience,
            favoriteSongsAudience = editFavoriteSongsAudience,
            recentRatingsAudience = editRecentRatingsAudience,
            saving = saving,
            errorRes = editErrorRes,
            bottomPadding = contentPadding.calculateBottomPadding(),
            onClose = { if (!saving) editing = false },
            onChoosePhoto = { photoPicker.launch("image/*") },
            onNameChange = { editName = it.take(80); editErrorRes = null },
            onUsernameChange = {
                editUsername = it.trimStart().removePrefix("@").lowercase().take(30)
                editErrorRes = null
            },
            onListeningAudienceChange = { editListeningAudience = it },
            onFollowingVisibilityChange = { editFollowingAudience = it },
            onFollowersVisibilityChange = { editFollowersAudience = it },
            onPlayCountVisibilityChange = { editPlayCountAudience = it },
            onFavoriteArtistsVisibilityChange = { editFavoriteArtistsAudience = it },
            onFavoriteAlbumsVisibilityChange = { editFavoriteAlbumsAudience = it },
            onFavoriteSongsVisibilityChange = { editFavoriteSongsAudience = it },
            onRecentRatingsAudienceChange = { editRecentRatingsAudience = it },
            onDeleteAccount = {
                if (!saving) scope.launch {
                    saving = true
                    editErrorRes = null
                    SocialRepository.deleteOwnAccount().fold(
                        onSuccess = { onAccountDeleted() },
                        onFailure = { editErrorRes = R.string.profile_delete_account_error },
                    )
                    saving = false
                }
            },
            onSave = {
                val cleanUsername = editUsername.trim().removePrefix("@").lowercase()
                if (editName.trim().isBlank() || !PROFILE_USERNAME_REGEX.matches(cleanUsername)) {
                    editErrorRes = R.string.profile_edit_save_error
                } else scope.launch {
                    saving = true
                    editErrorRes = null
                    val result = runCatching {
                        val uploadedAvatar = selectedAvatar?.let { selection ->
                            val (bytes, mime) = readProfileImage(
                                context = context,
                                crop = selection.portrait,
                                targetWidthPx = PROFILE_CROP_WIDTH_PX,
                                targetHeightPx = PROFILE_CROP_HEIGHT_PX,
                            )
                            SocialRepository.uploadProfileAvatar(bytes, mime).getOrThrow()
                        } ?: avatar
                        val uploadedAvatarIcon = selectedAvatar?.let { selection ->
                            val (bytes, mime) = readProfileImage(
                                context = context,
                                crop = selection.icon,
                                targetWidthPx = PROFILE_ICON_SIZE_PX,
                                targetHeightPx = PROFILE_ICON_SIZE_PX,
                            )
                            SocialRepository.uploadProfileAvatarIcon(bytes, mime).getOrThrow()
                        } ?: avatarIcon

                        SocialRepository.updateOwnProfile(
                            displayName = editName,
                            username = cleanUsername,
                            avatarUrl = uploadedAvatar,
                            avatarIconUrl = uploadedAvatarIcon,
                        ).getOrThrow()
                        SocialRepository.updateProfilePrivacy(
                            nowPlayingVisibility = editListeningAudience.wireValue,
                            listeningVisibility = editListeningAudience.wireValue,
                            followingVisibility = editFollowingAudience.wireValue,
                            followersVisibility = editFollowersAudience.wireValue,
                            playCountVisibility = editPlayCountAudience.wireValue,
                            favoriteArtistsVisibility = editFavoriteArtistsAudience.wireValue,
                            favoriteAlbumsVisibility = editFavoriteAlbumsAudience.wireValue,
                            favoriteSongsVisibility = editFavoriteSongsAudience.wireValue,
                            recentRatingsVisibility = editRecentRatingsAudience.wireValue,
                        ).getOrThrow()
                        SocialRepository.setAlbumRatingsVisibility(editRecentRatingsAudience.wireValue).getOrThrow()
                    }
                    if (result.isSuccess) {
                        ProfilePrivacyStore.setListeningAudience(editListeningAudience)
                        ProfilePrivacyStore.setProfileVisibilityAudiences(
                            following = editFollowingAudience,
                            followers = editFollowersAudience,
                            playCount = editPlayCountAudience,
                            favoriteArtists = editFavoriteArtistsAudience,
                            favoriteAlbums = editFavoriteAlbumsAudience,
                            favoriteSongs = editFavoriteSongsAudience,
                            recentRatings = editRecentRatingsAudience,
                        )
                        selectedAvatar = null
                        editing = false
                        loadProfile()
                    } else {
                        editErrorRes = if (result.exceptionOrNull()?.message.orEmpty().contains("5 MB", ignoreCase = true)) {
                            R.string.profile_photo_error
                        } else {
                            R.string.profile_edit_save_error
                        }
                    }
                    saving = false
                }
            },
        )
        return
    }

    peoplePage?.let { page ->
        val pageTitle = stringResource(
            if (page == ProfilePeoplePage.FOLLOWING) R.string.profile_following else R.string.profile_followers,
        )
        val pageProfiles = if (page == ProfilePeoplePage.FOLLOWING) following else followers
        val surfaceWash = rememberProfileBottomWash(image = avatar, fallback = palette.wash)
        ProfilePeopleListPage(
            title = pageTitle,
            profiles = pageProfiles,
            loading = loading,
            pageColor = surfaceWash,
            palette = palette,
            bottomPadding = contentPadding.calculateBottomPadding(),
            viewerFollowingIds = following.mapTo(linkedSetOf()) { it.id },
            viewerFollowerIds = followers.mapTo(linkedSetOf()) { it.id },
            onOpenProfile = onOpenProfile,
            onUnfollow = { person ->
                SocialRepository.unfollow(person.id)
                following = following.filterNot { it.id == person.id }
                if (ownUserId != null) {
                    counts = SocialRepository.socialCounts(ownUserId) ?: counts
                }
            },
            onRemoveFollower = { person ->
                SocialRepository.removeFollower(person.id)
                followers = followers.filterNot { it.id == person.id }
                if (ownUserId != null) {
                    counts = SocialRepository.socialCounts(ownUserId) ?: counts
                }
            },
            onBlock = { person ->
                SocialRepository.blockUser(person.id)
                following = following.filterNot { it.id == person.id }
                followers = followers.filterNot { it.id == person.id }
                if (ownUserId != null) {
                    counts = SocialRepository.socialCounts(ownUserId) ?: counts
                }
            },
            onBack = {
                peoplePage = null
                scope.launch { scrollState.scrollTo(profileScrollRestore) }
            },
            onTopBarInfoChange = onTopBarInfoChange,
            modifier = modifier,
        )
        return
    }

    ImmersiveProfilePage(
        profile = profile,
        fallbackAccount = account,
        palette = palette,
        loading = loading,
        counts = counts,
        fallbackFollowingCount = following.size,
        fallbackFollowersCount = followers.size,
        playCount = playCount,
        showFollowing = true,
        showFollowers = true,
        showPlayCount = true,
        actionText = stringResource(R.string.profile_edit),
        actionEnabled = profile != null || account != null,
        actionBusy = false,
        onAction = {
            editName = profile?.displayName?.takeIf { it.isNotBlank() } ?: account?.name.orEmpty()
            editUsername = profile?.username.orEmpty()
            editListeningAudience = profileSectionAudience(profile?.nowPlayingVisibility ?: profile?.listeningVisibility ?: listeningAudience.wireValue)
            editFollowingAudience = profile?.followingVisibility?.let(::profileSectionAudience) ?: followingAudience
            editFollowersAudience = profile?.followersVisibility?.let(::profileSectionAudience) ?: followersAudience
            editPlayCountAudience = profile?.playCountVisibility?.let(::profileSectionAudience) ?: playCountAudience
            editFavoriteArtistsAudience = profileSectionAudience(
                profile?.favoriteArtistsVisibility ?: profile?.favoriteContentVisibility,
            )
            editFavoriteAlbumsAudience = profileSectionAudience(
                profile?.favoriteAlbumsVisibility ?: profile?.favoriteContentVisibility,
            )
            editFavoriteSongsAudience = profileSectionAudience(
                profile?.favoriteSongsVisibility ?: profile?.favoriteContentVisibility,
            )
            editRecentRatingsAudience = profileSectionAudience(profile?.recentRatingsVisibility ?: recentRatingsAudience.wireValue)
            selectedAvatar = null
            cropEditorUri = null
            editErrorRes = null
            editing = true
        },
        onFollowingClick = {
            profileScrollRestore = scrollState.value
            peoplePage = ProfilePeoplePage.FOLLOWING
            scope.launch { scrollState.scrollTo(0) }
        },
        onFollowersClick = {
            profileScrollRestore = scrollState.value
            peoplePage = ProfilePeoplePage.FOLLOWERS
            scope.launch { scrollState.scrollTo(0) }
        },
        onBack = onBack,
        bottomPadding = contentPadding.calculateBottomPadding(),
        scrollState = scrollState,
        onTopBarInfoChange = onTopBarInfoChange,
    ) {
        ProfileTasteCarouselSection(
            title = stringResource(R.string.profile_favorite_artists),
            tastes = tastes.filter { it.isArtist }.take(10),
            palette = palette,
            placeholderIcon = Icons.Rounded.Person,
        )
        ProfileTasteCarouselSection(
            title = stringResource(R.string.profile_favorite_albums),
            tastes = tastes.filter { it.isAlbum }.take(10),
            palette = palette,
            placeholderIcon = Icons.Rounded.Album,
        )
        ProfileSongGridSection(
            title = stringResource(R.string.profile_favorite_songs),
            tastes = tastes.filter { it.isTrack }.take(10),
            palette = palette,
        )
        ProfileAlbumRatingGridSection(
            title = stringResource(R.string.profile_recent_album_ratings),
            ratings = recentAlbumRatings,
            palette = palette,
        )
    }
}

@Composable
fun PublicProfileScreen(
    userId: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    onTopBarInfoChange: (ProfileTopBarInfo) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val cachedSnapshot = remember(userId) { ProfileSnapshotStore.load(userId) }
    var profile by remember(userId) { mutableStateOf(cachedSnapshot?.profile) }
    var activities by remember(userId) { mutableStateOf<List<ListeningActivity>>(emptyList()) }
    var tastes by remember(userId) {
        mutableStateOf(cachedSnapshot?.tastes.orEmpty().map { it.toImmersiveTaste() })
    }
    var recentAlbumRatings by remember(userId) { mutableStateOf<List<ProfileAlbumRatingCard>>(emptyList()) }
    var compatibility by remember(userId) {
        mutableStateOf(
            cachedSnapshot?.compatibilityPercent?.let {
                ImmersiveCompatibility(it, cachedSnapshot?.compatibilityGenres.orEmpty())
            },
        )
    }
    var counts by remember(userId) { mutableStateOf(cachedSnapshot?.counts) }
    var playCount by remember(userId) { mutableStateOf(cachedSnapshot?.playCount ?: 0L) }
    var following by remember(userId) { mutableStateOf(cachedSnapshot?.following ?: false) }
    var followBusy by remember(userId) { mutableStateOf(false) }
    var loading by remember(userId) { mutableStateOf(cachedSnapshot == null) }
    var peoplePage by remember(userId) { mutableStateOf<ProfilePeoplePage?>(null) }
    var peopleProfiles by remember(userId) { mutableStateOf<List<OrbProfile>>(emptyList()) }
    var peopleLoading by remember(userId) { mutableStateOf(false) }
    var viewerFollowingIds by remember(userId) { mutableStateOf<Set<String>>(emptySet()) }
    var viewerFollowerIds by remember(userId) { mutableStateOf<Set<String>>(emptySet()) }
    var profileScrollRestore by remember(userId) { mutableStateOf(0) }

    LaunchedEffect(userId) {
        loading = profile == null && tastes.isEmpty() && counts == null
        coroutineScope {
            val profileTask = async { runCatching { SocialRepository.profile(userId) }.getOrNull() }
            // Profile favorites are independent audience-controlled surfaces. Each
            // category comes from its own server-side projection so hiding songs
            // does not accidentally expose song titles through an artist-only
            // profile request (and the same for albums/artists).
            val favoriteArtistsTask = async {
                runCatching { SocialRepository.profileFavoriteArtistActivityForUser(userId, 20_000) }
            }
            val favoriteAlbumsTask = async {
                runCatching { SocialRepository.profileFavoriteAlbumActivityForUser(userId, 20_000) }
            }
            val favoriteSongsTask = async {
                runCatching { SocialRepository.profileFavoriteSongActivityForUser(userId, 20_000) }
            }
            val activityTask = async {
                runCatching { SocialRepository.listeningActivityForUser(userId, maxRows = 10_000) }
            }
            val myActivityTask = async {
                val me = SocialRepository.currentUserId()
                    ?: return@async Result.success(emptyList<ListeningActivity>())
                runCatching { SocialRepository.listeningActivityForUser(me) }
            }
            val countsTask = async { runCatching { SocialRepository.socialCounts(userId) }.getOrNull() }
            val playsTask = async { runCatching { SocialRepository.profilePlayCount(userId) } }
            val followingTask = async { runCatching { SocialRepository.isFollowing(userId) } }
            val publicCompatibilityTask = async {
                runCatching { SocialRepository.profileMusicCompatibility(userId) }.getOrNull()
            }
            val ratingsTask = async {
                runCatching {
                    resolveProfileAlbumRatings(SocialRepository.albumRatingsForUser(userId, limit = 10))
                }.getOrDefault(emptyList())
            }

            profile = profileTask.await() ?: profile
            val favoriteArtistsResult = favoriteArtistsTask.await()
            val favoriteAlbumsResult = favoriteAlbumsTask.await()
            val favoriteSongsResult = favoriteSongsTask.await()
            val activityResult = activityTask.await()
            val myActivityResult = myActivityTask.await()
            if (activityResult.isSuccess) {
                activities = activityResult.getOrDefault(emptyList()).filter { it.reachedListenThreshold() }
            }
            val favoriteArtistActivities = favoriteArtistsResult.getOrDefault(emptyList())
                .filter { it.reachedListenThreshold() }
            val favoriteAlbumActivities = favoriteAlbumsResult.getOrDefault(emptyList())
                .filter { it.reachedListenThreshold() }
            val favoriteSongActivities = favoriteSongsResult.getOrDefault(emptyList())
                .filter { it.reachedListenThreshold() }
            val mine = myActivityResult.getOrDefault(emptyList()).filter { it.reachedListenThreshold() }
            if (
                favoriteArtistsResult.isSuccess || favoriteAlbumsResult.isSuccess ||
                favoriteSongsResult.isSuccess || activityResult.isSuccess || myActivityResult.isSuccess
            ) {
                ArtistCreditResolver.resolveAll(
                    (favoriteArtistActivities + favoriteAlbumActivities + favoriteSongActivities + activities + mine)
                        .map { it.artist },
                )
            }
            counts = countsTask.await() ?: counts
            playCount = maxOf(
                playCount,
                playsTask.await().getOrDefault(playCount),
            )
            following = followingTask.await().getOrDefault(following)
            recentAlbumRatings = ratingsTask.await()
            val anyFavoriteFetchSucceeded = favoriteArtistsResult.isSuccess ||
                favoriteAlbumsResult.isSuccess || favoriteSongsResult.isSuccess
            val initialTastes = if (anyFavoriteFetchSucceeded) {
                buildImmersiveTasteEntries(
                    artistActivities = favoriteArtistActivities,
                    albumActivities = favoriteAlbumActivities,
                    trackActivities = favoriteSongActivities,
                ).also { tastes = it }
            } else {
                tastes
            }
            val detailedCompatibility = if (activityResult.isSuccess && myActivityResult.isSuccess) {
                calculateImmersiveCompatibility(mine, activities)
            } else {
                null
            }
            // Raw listening history remains follower-only. When that surface is
            // unavailable, use the privacy-safe server score so the compatibility
            // card itself is still visible to every profile viewer.
            val compatibilityPercent = detailedCompatibility
                ?: publicCompatibilityTask.await()
                ?: compatibility?.percent
            compatibility = compatibilityPercent?.let { percent ->
                ImmersiveCompatibility(
                    percent = percent,
                    genres = if (detailedCompatibility != null) compatibility?.genres.orEmpty() else emptyList(),
                )
            }
            ProfileSnapshotStore.save(
                userId,
                CachedProfileSnapshot(
                    profile = profile,
                    counts = counts,
                    playCount = playCount,
                    tastes = tastes.map { it.toCachedTaste() },
                    following = following,
                    compatibilityPercent = compatibility?.percent,
                    compatibilityGenres = compatibility?.genres.orEmpty(),
                ),
            )
            loading = false
            launch {
                enrichImmersiveArtistTastes(initialTastes) { artist, artwork ->
                    tastes = tastes.withImmersiveArtistArtwork(artist, artwork)
                }
                ProfileSnapshotStore.save(
                    userId,
                    CachedProfileSnapshot(
                        profile = profile,
                        counts = counts,
                        playCount = playCount,
                        tastes = tastes.map { it.toCachedTaste() },
                        following = following,
                        compatibilityPercent = compatibility?.percent,
                        compatibilityGenres = compatibility?.genres.orEmpty(),
                    ),
                )
            }
            if (compatibilityPercent != null && detailedCompatibility != null) {
                launch {
                    val genres = calculateCompatibleGenreTags(mine, activities)
                    compatibility = ImmersiveCompatibility(compatibilityPercent, genres)
                    ProfileSnapshotStore.save(
                        userId,
                        CachedProfileSnapshot(
                            profile = profile,
                            counts = counts,
                            playCount = playCount,
                            tastes = tastes.map { it.toCachedTaste() },
                            following = following,
                            compatibilityPercent = compatibility?.percent,
                            compatibilityGenres = compatibility?.genres.orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    val palette = rememberArtworkPalette(profile?.avatarUrl)
    BackHandler(enabled = peoplePage != null) {
        peoplePage = null
        scope.launch { scrollState.scrollTo(profileScrollRestore) }
    }
    // Keep public-profile navigation owned by the profile itself. This makes the
    // Android back gesture return to the surface that opened it (including Stats)
    // instead of relying only on the activity-level predictive-back callback.
    BackHandler(enabled = peoplePage == null) {
        onBack()
    }

    peoplePage?.let { page ->
        val pageTitle = stringResource(
            if (page == ProfilePeoplePage.FOLLOWING) R.string.profile_following else R.string.profile_followers,
        )
        val surfaceWash = rememberProfileBottomWash(image = profile?.avatarUrl, fallback = palette.wash)
        ProfilePeopleListPage(
            title = pageTitle,
            profiles = peopleProfiles,
            loading = peopleLoading,
            pageColor = surfaceWash,
            palette = palette,
            bottomPadding = contentPadding.calculateBottomPadding(),
            viewerFollowingIds = viewerFollowingIds,
            viewerFollowerIds = viewerFollowerIds,
            onOpenProfile = onOpenProfile,
            onUnfollow = { person ->
                SocialRepository.unfollow(person.id)
                viewerFollowingIds = viewerFollowingIds - person.id
            },
            onRemoveFollower = { person ->
                SocialRepository.removeFollower(person.id)
                viewerFollowerIds = viewerFollowerIds - person.id
            },
            onBlock = { person ->
                SocialRepository.blockUser(person.id)
                viewerFollowingIds = viewerFollowingIds - person.id
                viewerFollowerIds = viewerFollowerIds - person.id
                peopleProfiles = peopleProfiles.filterNot { it.id == person.id }
            },
            onBack = {
                peoplePage = null
                scope.launch { scrollState.scrollTo(profileScrollRestore) }
            },
            onTopBarInfoChange = onTopBarInfoChange,
            modifier = modifier,
        )
        return
    }

    fun openPeople(page: ProfilePeoplePage) {
        if (peopleLoading) return
        profileScrollRestore = scrollState.value
        peoplePage = page
        peopleProfiles = emptyList()
        peopleLoading = true
        scope.launch {
            scrollState.scrollTo(0)
            coroutineScope {
                val peopleTask = async {
                    runCatching {
                        if (page == ProfilePeoplePage.FOLLOWING) {
                            SocialRepository.followingProfilesForUser(userId)
                        } else {
                            SocialRepository.followersProfilesForUser(userId)
                        }
                    }.getOrDefault(emptyList())
                }
                val viewerFollowingTask = async {
                    runCatching { SocialRepository.followingIds().toSet() }.getOrDefault(emptySet())
                }
                val viewerFollowerTask = async {
                    val me = SocialRepository.currentUserId()
                    if (me == null) emptySet()
                    else runCatching { SocialRepository.followerIdsForUser(me).toSet() }.getOrDefault(emptySet())
                }
                peopleProfiles = peopleTask.await()
                viewerFollowingIds = viewerFollowingTask.await()
                viewerFollowerIds = viewerFollowerTask.await()
            }
            peopleLoading = false
        }
    }

    val isOwner = profile?.id == SocialRepository.currentUserId()
    val showFollowing = isOwner || canViewProfileAudience(
        profile?.followingVisibility,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )
    val showFollowers = isOwner || canViewProfileAudience(
        profile?.followersVisibility,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )
    val showPlayCount = isOwner || canViewProfileAudience(
        profile?.playCountVisibility,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )
    val showFavoriteArtists = isOwner || canViewProfileAudience(
        profile?.favoriteArtistsVisibility ?: profile?.favoriteContentVisibility,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )
    val showFavoriteAlbums = isOwner || canViewProfileAudience(
        profile?.favoriteAlbumsVisibility ?: profile?.favoriteContentVisibility,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )
    val showFavoriteSongs = isOwner || canViewProfileAudience(
        profile?.favoriteSongsVisibility ?: profile?.favoriteContentVisibility,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )
    val showRecentRatings = isOwner || canViewProfileAudience(
        profile?.recentRatingsVisibility ?: ProfileAudience.NOBODY.wireValue,
        viewerFollowsTarget = following,
        targetFollowsViewer = false,
    )

    ImmersiveProfilePage(
        profile = profile,
        fallbackAccount = null,
        palette = palette,
        loading = loading,
        counts = counts,
        fallbackFollowingCount = 0,
        fallbackFollowersCount = 0,
        playCount = playCount,
        showFollowing = showFollowing,
        showFollowers = showFollowers,
        showPlayCount = showPlayCount,
        actionText = stringResource(if (following) R.string.profile_following_button else R.string.profile_follow),
        actionEnabled = profile != null,
        actionBusy = followBusy,
        onAction = {
            if (!followBusy) {
                scope.launch {
                    followBusy = true
                    runCatching {
                        if (following) SocialRepository.unfollow(userId) else SocialRepository.follow(userId)
                    }.onSuccess {
                        following = !following
                        counts = runCatching { SocialRepository.socialCounts(userId) }.getOrNull() ?: counts
                        ProfileSnapshotStore.save(
                            userId,
                            CachedProfileSnapshot(
                                profile = profile,
                                counts = counts,
                                playCount = playCount,
                                tastes = tastes.map { it.toCachedTaste() },
                                following = following,
                                compatibilityPercent = compatibility?.percent,
                                compatibilityGenres = compatibility?.genres.orEmpty(),
                            ),
                        )
                    }
                    followBusy = false
                }
            }
        },
        onFollowingClick = { openPeople(ProfilePeoplePage.FOLLOWING) },
        onFollowersClick = { openPeople(ProfilePeoplePage.FOLLOWERS) },
        onBack = onBack,
        bottomPadding = contentPadding.calculateBottomPadding(),
        scrollState = scrollState,
        onTopBarInfoChange = onTopBarInfoChange,
        modifier = modifier,
    ) {
        compatibility?.let { ImmersiveCompatibilityCard(it, palette) }
        if (showFavoriteArtists) {
            ProfileTasteCarouselSection(
                title = stringResource(R.string.profile_favorite_artists),
                tastes = tastes.filter { it.isArtist }.take(10),
                palette = palette,
                placeholderIcon = Icons.Rounded.Person,
            )
        }
        if (showFavoriteAlbums) {
            ProfileTasteCarouselSection(
                title = stringResource(R.string.profile_favorite_albums),
                tastes = tastes.filter { it.isAlbum }.take(10),
                palette = palette,
                placeholderIcon = Icons.Rounded.Album,
            )
        }
        if (showFavoriteSongs) {
            ProfileSongGridSection(
                title = stringResource(R.string.profile_favorite_songs),
                tastes = tastes.filter { it.isTrack }.take(10),
                palette = palette,
            )
        }
        if (showRecentRatings && recentAlbumRatings.isNotEmpty()) {
            ProfileAlbumRatingGridSection(
                title = stringResource(R.string.profile_recent_album_ratings),
                ratings = recentAlbumRatings,
                palette = palette,
            )
        }
        if (!loading && profile == null) {
            ImmersiveMessage(stringResource(R.string.profile_not_found))
        } else if (!loading && compatibility == null && activities.isEmpty() && tastes.isEmpty() && playCount == 0L) {
            ImmersiveMessage(stringResource(R.string.profile_public_data_unavailable))
        }
    }
}

@Composable
private fun ImmersiveProfilePage(
    profile: OrbProfile?,
    fallbackAccount: Account?,
    palette: ArtworkPalette,
    loading: Boolean,
    counts: ProfileSocialCounts?,
    fallbackFollowingCount: Int,
    fallbackFollowersCount: Int,
    playCount: Long,
    showFollowing: Boolean,
    showFollowers: Boolean,
    showPlayCount: Boolean,
    actionText: String,
    actionEnabled: Boolean,
    actionBusy: Boolean,
    onAction: () -> Unit,
    onFollowingClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onBack: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    scrollState: ScrollState,
    onTopBarInfoChange: (ProfileTopBarInfo) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val image = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: fallbackAccount?.thumbnailUrl
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: fallbackAccount?.name
        ?: stringResource(R.string.profile_title)
    val username = profile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }

    val configuration = LocalConfiguration.current
    val isTabletLandscape = configuration.smallestScreenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenWidthDp >= 840
    val portraitPaneWidth = minOf(configuration.screenHeightDp.dp,
        configuration.screenWidthDp.dp * 0.42f)
    val profileEdgeField = if (isTabletLandscape) {
        rememberArtworkRightEdgeField(image = image, fallback = palette.wash)
    } else {
        rememberArtworkBottomEdgeField(image = image, fallback = palette.wash)
    }
    val surfaceWash = profileEdgeField.solidColor
    val profileForeground = bestForegroundForArtworkSurface(surfaceWash)
    val profilePalette = palette.copy(
        background = surfaceWash,
        wash = surfaceWash,
        elevated = profileForeground.copy(alpha = 0.14f),
        accent = profileForeground,
        onBackground = profileForeground,
        onBackgroundVariant = profileForeground.copy(alpha = 0.78f),
        divider = profileForeground.copy(alpha = 0.12f),
    )
    var titleCollapseOffsetPx by remember(displayName, username) { mutableStateOf(Float.POSITIVE_INFINITY) }

    LaunchedEffect(
        displayName,
        username,
        surfaceWash,
        profilePalette.onBackground,
        profilePalette.onBackgroundVariant,
        titleCollapseOffsetPx,
    ) {
        onTopBarInfoChange(
            ProfileTopBarInfo(
                title = displayName,
                subtitle = username,
                pageColor = surfaceWash,
                contentColor = profilePalette.onBackground,
                contentVariantColor = profilePalette.onBackgroundVariant,
                collapseOffsetPx = titleCollapseOffsetPx,
            ),
        )
    }

    val profileHaze = remember { HazeState() }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(surfaceWash),
    ) {
        val photoSize = if (isTabletLandscape) portraitPaneWidth else maxWidth
        // The confirmed image is a square 1:1 crop. The full original is kept
        // untouched until the user positions and confirms it in the editor.
        // Use a square hero so the saved photo fills the profile width.
        val photoHeight = if (isTabletLandscape) maxHeight else photoSize / PROFILE_CROP_ASPECT
        // Capture only the raw artwork and its continuation, never profile controls.
        Box(Modifier.matchParentSize().clipToBounds().hazeSource(profileHaze).background(surfaceWash)) {
            Box(
                (if (isTabletLandscape) Modifier.width(photoSize).fillMaxHeight()
                    else Modifier.fillMaxWidth().height(photoHeight)).offset {
                    IntOffset(0, if (isTabletLandscape) 0 else -scrollState.value)
                },
                contentAlignment = Alignment.Center,
            ) {
                if (!image.isNullOrBlank()) {
                    ProgressiveProfileArtwork(
                        image = image,
                        contentDescription = displayName,
                        wash = surfaceWash,
                        horizontalFade = isTabletLandscape,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Rounded.Person, contentDescription = null,
                        tint = profilePalette.onBackgroundVariant, modifier = Modifier.size(132.dp))
                }
            }
        }
        val mergeGeometry = if (isTabletLandscape) {
            Modifier.width(ARTWORK_MERGE_BAND).fillMaxHeight()
                .offset(x = photoSize - ARTWORK_MERGE_OVERLAP)
        } else {
            Modifier.fillMaxWidth().height(ARTWORK_MERGE_BAND).offset {
                IntOffset(0, (photoHeight - ARTWORK_MERGE_OVERLAP).roundToPx() - scrollState.value)
            }
        }
        ArtworkMergeBand(
            surfaceColor = surfaceWash,
            hazeState = profileHaze,
            horizontal = isTabletLandscape,
            modifier = mergeGeometry,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (isTabletLandscape) portraitPaneWidth + 24.dp else 0.dp,
                    end = if (isTabletLandscape) 24.dp else 0.dp)
                .verticalScroll(scrollState),
        ) {
            FrostedProfileHero(
                image = image,
                showArtwork = !isTabletLandscape,
                palette = profilePalette,
                heroWash = surfaceWash,
                displayName = displayName,
                username = profile?.username,
                loading = loading,
                counts = counts,
                fallbackFollowingCount = fallbackFollowingCount,
                fallbackFollowersCount = fallbackFollowersCount,
                playCount = playCount,
                showFollowing = showFollowing,
                showFollowers = showFollowers,
                showPlayCount = showPlayCount,
                actionText = actionText,
                actionEnabled = actionEnabled,
                actionBusy = actionBusy,
                onAction = onAction,
                onFollowingClick = onFollowingClick,
                onFollowersClick = onFollowersClick,
                onBack = onBack,
                onIdentityPositioned = { bottomInViewportPx ->
                    val absoluteBottom = scrollState.value.toFloat() + bottomInViewportPx
                    if (absoluteBottom.isFinite() && absoluteBottom > 0f) {
                        titleCollapseOffsetPx = absoluteBottom
                    }
                },
            )
            content()
            Spacer(Modifier.height(bottomPadding + 24.dp))
        }
    }

}

@Composable
private fun FrostedProfileHero(
    image: String?,
    showArtwork: Boolean = true,
    palette: ArtworkPalette,
    heroWash: Color,
    displayName: String,
    username: String?,
    loading: Boolean,
    counts: ProfileSocialCounts?,
    fallbackFollowingCount: Int,
    fallbackFollowersCount: Int,
    playCount: Long,
    showFollowing: Boolean,
    showFollowers: Boolean,
    showPlayCount: Boolean,
    actionText: String,
    actionEnabled: Boolean,
    actionBusy: Boolean,
    onAction: () -> Unit,
    onFollowingClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onBack: () -> Unit,
    onIdentityPositioned: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showArtwork) Modifier.aspectRatio(PROFILE_CROP_ASPECT)
                    else Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).height(96.dp)),
        ) {
            if (showArtwork) androidx.compose.material3.IconButton(
                onClick = onBack,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(start = 12.dp, top = 8.dp)
                    .align(Alignment.TopStart),
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }

        }
        // The title and actions follow the confirmed square artwork and its fade.
        Spacer(Modifier.height(if (showArtwork) 30.dp else 12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), color = palette.accent)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            onIdentityPositioned(coordinates.positionInRoot().y + coordinates.size.height)
                        },
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.displayLarge,
                            color = palette.onBackground,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        username?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "@$it",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onBackgroundVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                ProfileActionPill(
                    text = actionText,
                    palette = palette,
                    enabled = actionEnabled && !loading,
                    busy = actionBusy,
                    onClick = onAction,
                    modifier = Modifier.widthIn(min = 160.dp, max = 280.dp),
                )

                if (showFollowing || showFollowers || showPlayCount) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        if (showFollowing) {
                            CleanProfileMetric(
                                formatProfileCount(counts?.followingCount ?: fallbackFollowingCount.toLong()),
                                stringResource(R.string.profile_following),
                                palette,
                                onClick = onFollowingClick,
                            )
                        }
                        if (showFollowers) {
                            CleanProfileMetric(
                                formatProfileCount(counts?.followersCount ?: fallbackFollowersCount.toLong()),
                                stringResource(R.string.profile_followers),
                                palette,
                                onClick = onFollowersClick,
                            )
                        }
                        if (showPlayCount) {
                            CleanProfileMetric(
                                formatProfileCount(playCount),
                                stringResource(R.string.profile_total_plays),
                                palette,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun rememberProfileBottomWash(
    image: String?,
    fallback: Color,
): Color {
    val context = LocalContext.current
    var sampledWash by remember(image, fallback) { mutableStateOf(fallback) }

    LaunchedEffect(image, fallback) {
        sampledWash = fallback
        if (image.isNullOrBlank()) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(image)
            .size(256)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        val lowerWash = withContext(Dispatchers.Default) { bitmap.profileBottomWashColor() } ?: return@LaunchedEffect
        sampledWash = lerp(fallback, lowerWash, 0.78f)
    }

    return sampledWash
}

private fun Bitmap.profileBottomWashColor(): Color? {
    if (width <= 0 || height <= 0) return null

    val left = (width * 0.06f).toInt().coerceIn(0, width - 1)
    val right = (width * 0.94f).toInt().coerceIn(left + 1, width)
    val top = (height * 0.72f).toInt().coerceIn(0, height - 1)
    val bottom = height
    val sampleWidth = max(1, right - left)
    val sampleHeight = max(1, bottom - top)
    val stepX = max(1, sampleWidth / 72)
    val stepY = max(1, sampleHeight / 72)

    var totalWeight = 0.0
    var red = 0.0
    var green = 0.0
    var blue = 0.0

    for (y in top until bottom step stepY) {
        val yProgress = ((y - top).toFloat() / max(1, sampleHeight - 1)).coerceIn(0f, 1f)
        val yWeight = 0.45f + (yProgress * yProgress * 1.85f)

        for (x in left until right step stepX) {
            val pixel = getPixel(x, y)
            val xProgress = ((x - left).toFloat() / max(1, sampleWidth - 1)).coerceIn(0f, 1f)
            // Favour the lateral water/background areas over the centred subject
            // so the remaining page colour follows the image's lower field rather
            // than skin or foliage above.
            val sideBias = 0.78f + abs(xProgress - 0.5f) * 1.10f
            val weight = (yWeight * sideBias).toDouble()
            red += ((pixel shr 16) and 0xFF) * weight
            green += ((pixel shr 8) and 0xFF) * weight
            blue += (pixel and 0xFF) * weight
            totalWeight += weight
        }
    }

    if (totalWeight <= 0.0) return null

    val r = (red / totalWeight).toInt().coerceIn(0, 255)
    val g = (green / totalWeight).toInt().coerceIn(0, 255)
    val b = (blue / totalWeight).toInt().coerceIn(0, 255)

    return Color(
        red = min(255, (r * 0.96f + 8f).toInt()),
        green = min(255, (g * 0.98f + 6f).toInt()),
        blue = min(255, (b * 1.03f + 10f).toInt()),
    )
}

@Composable
private fun ProgressiveProfileArtwork(
    image: String,
    contentDescription: String,
    wash: Color,
    modifier: Modifier = Modifier,
    horizontalFade: Boolean = false,
) {
    val edge = if (horizontalFade) {
        rememberArtworkRightEdgeField(image = image, fallback = wash)
    } else {
        rememberArtworkBottomEdgeField(image = image, fallback = wash)
    }
    Box(modifier = modifier.background(edge.solidColor)) {
        AsyncImage(
            model = image,
            contentDescription = contentDescription,
            // The uploaded image is already a confirmed square crop. Never
            // fit the original portrait into this area or introduce side bars.
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().then(if (horizontalFade) Modifier.landscapeArtworkFade() else Modifier),
        )
        edge.edgeFlow?.let { field ->
            Image(
                bitmap = field,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = (if (horizontalFade) Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight().width(ARTWORK_LANDSCAPE_FLOW_WIDTH)
                    .offset(x = ARTWORK_LANDSCAPE_FLOW_WIDTH - ARTWORK_LANDSCAPE_FLOW_OVERLAP)
                    else Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(250.dp).offset(y = 174.dp))
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val stops = arrayOf(0f to Color.Transparent,
                            0.16f to Color.White.copy(alpha = 0.30f),
                            0.30f to Color.White.copy(alpha = 0.82f),
                            0.42f to Color.White, 1f to Color.White)
                        drawRect(brush = if (horizontalFade) artworkLandscapeFlowMask()
                            else Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
                    },
            )
        }
    }
}

@Composable
private fun ProfileArtworkDiffusionLayer(
    image: String,
) {
    val stops = arrayOf(
        0.00f to 0f,
        0.87f to 0f,
        0.92f to 0.18f,
        0.96f to 0.52f,
        0.985f to 0.34f,
        1.00f to 0.02f,
    )
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha) }.toTypedArray()
    val positions = stops.map { (position, _) -> position }.toFloatArray()

    AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .blur(88.dp, BlurredEdgeTreatment.Unbounded)
            .graphicsLayer(
                scaleX = 1.18f,
                scaleY = 1.10f,
                compositingStrategy = CompositingStrategy.Offscreen,
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun ProfileArtworkLayer(
    image: String,
    contentDescription: String?,
    blurRadius: Float,
    stops: Array<Pair<Float, Float>>,
) {
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha.coerceIn(0f, 1f)) }.toTypedArray()
    val positions = stops.map { (position, _) -> position.coerceIn(0f, 1f) }.toFloatArray()
    AsyncImage(
        model = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (blurRadius > 0f) Modifier.blur(blurRadius.dp, BlurredEdgeTreatment.Unbounded)
                else Modifier
            )
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun ProfileActionPill(
    text: String,
    palette: ArtworkPalette,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Match the primary Play control used on artist/album pages: same 50dp
    // pill geometry, artwork-derived frosted material and contrast calculation.
    val shape = CircleShape
    val buttonContent = albumGlassContentColor(palette.background)
        .copy(alpha = if (enabled) 1f else 0.48f)
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .albumGlassSurface(palette.background)
            .then(if (enabled && !busy) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = buttonContent,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = buttonContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CleanProfileMetric(
    value: String,
    label: String,
    palette: ArtworkPalette? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(106.dp)
            .clip(shape)
            .then(
                if (onClick != null) {
                    // Profile metrics stay visually naked; only the text is
                    // interactive. This matches the hero typography and avoids
                    // isolated glass tiles behind Following / Followers.
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = palette?.onBackground ?: MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette?.onBackgroundVariant ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfilePhotoCropScreen(
    uri: Uri,
    displayName: String,
    username: String,
    onCancel: () -> Unit,
    onConfirm: (ProfileImageSelection) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var stage by remember(uri) { mutableStateOf(ProfilePhotoEditorStage.PROFILE) }
    var portraitCrop by remember(uri) { mutableStateOf(ProfileImageCrop(uri = uri)) }
    var iconCrop by remember(uri) { mutableStateOf(ProfileImageCrop(uri = uri)) }

    LaunchedEffect(uri) {
        bitmap = runCatching { loadProfileSourceBitmap(context, uri) }.getOrNull()
        loadFailed = bitmap == null
    }
    BackHandler {
        if (stage == ProfilePhotoEditorStage.ICON) {
            stage = ProfilePhotoEditorStage.PROFILE
        } else {
            onCancel()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .verticalScroll(rememberScrollState())
                .padding(top = 64.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = stage == ProfilePhotoEditorStage.PROFILE,
                    onClick = { stage = ProfilePhotoEditorStage.PROFILE },
                    label = { Text(stringResource(R.string.profile_photo_profile_preview)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = stage == ProfilePhotoEditorStage.ICON,
                    onClick = { stage = ProfilePhotoEditorStage.ICON },
                    label = { Text(stringResource(R.string.profile_photo_icon_preview)) },
                    modifier = Modifier.weight(1f),
                )
            }

            when (stage) {
                ProfilePhotoEditorStage.PROFILE -> {
                    FullProfilePhotoPositioner(
                        bitmap = bitmap,
                        crop = portraitCrop,
                        onCropChange = { portraitCrop = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ProfilePhotoEditorStage.ICON -> {
                    Spacer(Modifier.height(22.dp))
                    EditableIconPhotoPreview(
                        bitmap = bitmap,
                        crop = iconCrop,
                        onCropChange = { iconCrop = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (loadFailed) {
                Text(
                    text = stringResource(R.string.profile_photo_cannot_open),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(
                    if (stage == ProfilePhotoEditorStage.PROFILE) {
                        R.string.profile_photo_profile_hint
                    } else {
                        R.string.profile_photo_icon_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }

        Surface(
            color = Color.Black.copy(alpha = 0.76f),
            contentColor = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .zIndex(4f),
        ) {
            Row(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(
                    onClick = {
                        if (stage == ProfilePhotoEditorStage.ICON) {
                            stage = ProfilePhotoEditorStage.PROFILE
                        } else {
                            onCancel()
                        }
                    },
                ) {
                    Icon(
                        if (stage == ProfilePhotoEditorStage.ICON) Icons.Rounded.ArrowBack else Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.profile_cancel),
                    )
                }
                Text(
                    text = stringResource(R.string.profile_photo_position_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (stage == ProfilePhotoEditorStage.PROFILE) {
                            stage = ProfilePhotoEditorStage.ICON
                        } else {
                            onConfirm(
                                ProfileImageSelection(
                                    portrait = portraitCrop,
                                    icon = iconCrop,
                                ),
                            )
                        }
                    },
                    enabled = bitmap != null,
                ) {
                    Text(
                        text = stringResource(
                            if (stage == ProfilePhotoEditorStage.PROFILE) {
                                R.string.profile_photo_next
                            } else {
                                R.string.profile_photo_apply
                            },
                        ),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * The profile editor intentionally shows the ENTIRE source photo. A square
 * guide indicates what WILL be exported after the user confirms the crop.
 * It must not use ContentScale.Crop for the editing preview: that would make
 * a portrait source look permanently cut before any gesture takes place.
 *
 * The shorter dimension of the fitted source defines the square guide.
 * Because the fitted image covers this guide at zoom=1, the existing crop
 * fractions can be exported using renderProfileCropBitmap without black bars.
 */
@Composable
private fun FullProfilePhotoPositioner(
    bitmap: Bitmap?,
    crop: ProfileImageCrop,
    onCropChange: (ProfileImageCrop) -> Unit,
    modifier: Modifier = Modifier,
    circularGuide: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val aspect = remember(bitmap) {
        bitmap?.let { it.width.toFloat() / it.height.toFloat() }
            ?.takeIf { it.isFinite() && it > 0f } ?: 1f
    }
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val guideSidePx = remember(bitmap, viewport) {
        val source = bitmap
        if (source == null || viewport.width <= 0 || viewport.height <= 0) 0f else {
            val fittedScale = min(
                viewport.width.toFloat() / source.width,
                viewport.height.toFloat() / source.height,
            )
            min(source.width * fittedScale, source.height * fittedScale)
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().background(Color.Black)) {
        // Preserve the complete original aspect ratio. Very tall photos are
        // fitted into a scrollable-height editor, never pre-cropped to 1:1.
        val maximumHeight = configuration.screenHeightDp.dp * 0.78f
        val previewHeight = (maxWidth / aspect).coerceAtMost(maximumHeight)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .background(Color(0xFF191919))
                .clipToBounds()
                .onSizeChanged { viewport = it }
                .profileCropGestures(
                    bitmap = bitmap,
                    areaSize = IntSize(guideSidePx.toInt(), guideSidePx.toInt()),
                    crop = crop,
                    onCropChange = onCropChange,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                // Fit shows every edge of the photograph before the user
                // moves or zooms. No crop, resampling or upload happens here.
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = crop.zoom
                            scaleY = crop.zoom
                            translationX = crop.offsetXFraction * guideSidePx
                            translationY = crop.offsetYFraction * guideSidePx
                        },
                )
                Canvas(Modifier.matchParentSize()) {
                    val side = guideSidePx.coerceAtMost(min(size.width, size.height))
                    if (side > 0f) {
                        val left = (size.width - side) / 2f
                        val top = (size.height - side) / 2f
                        // Both editors show the entire original image until saving.
                        // Only the guide changes: square for the profile, circle
                        // for the avatar. Never clip the source to this guide.
                        if (circularGuide) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.80f),
                                radius = (side / 2f - 1.5.dp.toPx()).coerceAtLeast(0f),
                                center = Offset(size.width / 2f, size.height / 2f),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        } else {
                            drawRect(
                                color = Color.White.copy(alpha = 0.80f),
                                topLeft = Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(side, side),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableProfilePhotoPreview(
    bitmap: Bitmap?,
    crop: ProfileImageCrop,
    displayName: String,
    username: String,
    onCropChange: (ProfileImageCrop) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    var edge by remember(bitmap) { mutableStateOf(ArtworkBottomEdgeField(null, Color.Black)) }
    LaunchedEffect(bitmap, crop) {
        val source = bitmap ?: return@LaunchedEffect
        edge = withContext(Dispatchers.Default) {
            val preview = renderProfileCropBitmap(source, crop, 384, 384)
            try { createBottomEdgeField(preview, 0.985f, Color.Black) }
            finally { if (preview !== source) preview.recycle() }
        } ?: ArtworkBottomEdgeField(null, Color.Black)
    }
    val foreground = bestForegroundForArtworkSurface(edge.solidColor)
    val haze = remember { HazeState() }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds().background(edge.solidColor)) {
        val photoHeight = maxWidth / PROFILE_CROP_ASPECT
        Box(Modifier.matchParentSize().hazeSource(haze).background(edge.solidColor)) {
            Box(Modifier.fillMaxWidth().aspectRatio(PROFILE_CROP_ASPECT)) {
                bitmap?.let {
                    EditableProfileCropLayer(it, crop, areaSize, 0f, arrayOf(0f to 1f, 1f to 1f))
                }
            }
            edge.edgeFlow?.let { field ->
                Image(bitmap = field, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth().height(250.dp).offset(y = photoHeight - 76.dp)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(
                                0f to Color.Transparent, 0.16f to Color.White.copy(alpha = 0.30f),
                                0.30f to Color.White.copy(alpha = 0.82f), 0.42f to Color.White,
                                1f to Color.White), blendMode = BlendMode.DstIn)
                        })
            }
        }
        ArtworkMergeBand(surfaceColor = edge.solidColor, hazeState = haze,
            modifier = Modifier.fillMaxWidth().height(ARTWORK_MERGE_BAND)
                .offset(y = photoHeight - ARTWORK_MERGE_OVERLAP))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(PROFILE_CROP_ASPECT)
                .onSizeChanged { areaSize = it }
                .then(if (editable) Modifier.profileCropGestures(bitmap, areaSize, crop, onCropChange) else Modifier)) {
                if (bitmap == null) CircularProgressIndicator(color = foreground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(30.dp))
            Text(displayName.ifBlank { stringResource(R.string.profile_title) },
                style = MaterialTheme.typography.displayLarge, color = foreground,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (username.isNotBlank()) Text("@${username.removePrefix("@")}",
                style = MaterialTheme.typography.bodyMedium,
                color = foreground.copy(alpha = 0.78f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.widthIn(min = 160.dp, max = 280.dp).height(48.dp)
                .clip(CircleShape).background(foreground).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_edit), style = MaterialTheme.typography.titleMedium,
                    color = bestForegroundForArtworkSurface(foreground))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ProfileSelectionPreview(crop: ProfileImageCrop, displayName: String, username: String) {
    val context = LocalContext.current
    var bitmap by remember(crop.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(crop.uri) { bitmap = loadProfileSourceBitmap(context, crop.uri) }
    EditableProfilePhotoPreview(bitmap, crop, displayName, username, onCropChange = {}, editable = false)
}

@Composable
private fun ProfilePhotoPreviewMetric(
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.76f),
            maxLines = 1,
        )
    }
}

@Composable
private fun EditableIconPhotoPreview(
    bitmap: Bitmap?,
    crop: ProfileImageCrop,
    onCropChange: (ProfileImageCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The source must remain fully visible while the user adjusts the icon.
    // The circle is a crop GUIDE only; the saved avatar is produced later,
    // using the selected zoom and pan in renderProfileCropBitmap.
    FullProfilePhotoPositioner(
        bitmap = bitmap,
        crop = crop,
        onCropChange = onCropChange,
        modifier = modifier,
        circularGuide = true,
    )
}

@Composable
private fun ProfileCropThumbnail(
    crop: ProfileImageCrop,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(crop.uri) { mutableStateOf<Bitmap?>(null) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(crop.uri) {
        bitmap = loadProfileSourceBitmap(context, crop.uri)
    }

    Box(
        modifier = modifier.onSizeChanged { areaSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val source = bitmap
        if (source == null) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = crop.zoom
                        scaleY = crop.zoom
                        translationX = crop.offsetXFraction * areaSize.width
                        translationY = crop.offsetYFraction * areaSize.height
                    },
            )
        }
    }
}

private fun Modifier.profileCropGestures(
    bitmap: Bitmap?,
    areaSize: IntSize,
    crop: ProfileImageCrop,
    onCropChange: (ProfileImageCrop) -> Unit,
): Modifier = pointerInput(bitmap, areaSize) {
    var currentZoom = crop.zoom
    var currentXFraction = crop.offsetXFraction
    var currentYFraction = crop.offsetYFraction

    detectTransformGestures { _, pan, gestureZoom, _ ->
        val source = bitmap ?: return@detectTransformGestures
        if (areaSize.width <= 0 || areaSize.height <= 0) return@detectTransformGestures

        val areaWidth = areaSize.width.toFloat()
        val areaHeight = areaSize.height.toFloat()
        val newZoom = (currentZoom * gestureZoom).coerceIn(1f, 4f)
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val areaAspect = areaWidth / areaHeight
        val baseWidth: Float
        val baseHeight: Float
        // ContentScale.Crop: the displayed 1:1 selection must be fully
        // covered at all times, without stretching or letterboxing.
        if (sourceAspect > areaAspect) {
            baseHeight = areaHeight
            baseWidth = areaHeight * sourceAspect
        } else {
            baseWidth = areaWidth
            baseHeight = areaWidth / sourceAspect
        }

        val maxX = max(0f, (baseWidth * newZoom - areaWidth) / 2f)
        val maxY = max(0f, (baseHeight * newZoom - areaHeight) / 2f)
        val nextX = (currentXFraction * areaWidth + pan.x).coerceIn(-maxX, maxX)
        val nextY = (currentYFraction * areaHeight + pan.y).coerceIn(-maxY, maxY)

        currentZoom = newZoom
        currentXFraction = nextX / areaWidth
        currentYFraction = nextY / areaHeight
        onCropChange(
            crop.copy(
                zoom = currentZoom,
                offsetXFraction = currentXFraction,
                offsetYFraction = currentYFraction,
            ),
        )
    }
}

@Composable
private fun EditableProfileCropLayer(
    bitmap: Bitmap,
    crop: ProfileImageCrop,
    areaSize: IntSize,
    blurRadius: Float,
    stops: Array<Pair<Float, Float>>,
) {
    val colors = stops.map { (_, alpha) -> Color.White.copy(alpha = alpha.coerceIn(0f, 1f)) }.toTypedArray()
    val positions = stops.map { (position, _) -> position.coerceIn(0f, 1f) }.toFloatArray()
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = crop.zoom
                scaleY = crop.zoom
                translationX = crop.offsetXFraction * areaSize.width
                translationY = crop.offsetYFraction * areaSize.height
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .then(
                if (blurRadius > 0f) Modifier.blur(blurRadius.dp, BlurredEdgeTreatment.Unbounded)
                else Modifier,
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        *positions.zip(colors).map { it.first to it.second }.toTypedArray(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

@Composable
private fun ProfileEditScreen(
    currentAvatar: String?,
    currentAvatarIcon: String?,
    selectedAvatar: ProfileImageSelection?,
    name: String,
    username: String,
    usernameLockedUntil: Instant?,
    listeningAudience: ProfileAudience,
    followingAudience: ProfileAudience,
    followersAudience: ProfileAudience,
    playCountAudience: ProfileAudience,
    favoriteArtistsAudience: ProfileAudience,
    favoriteAlbumsAudience: ProfileAudience,
    favoriteSongsAudience: ProfileAudience,
    recentRatingsAudience: ProfileAudience,
    saving: Boolean,
    errorRes: Int?,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
    onChoosePhoto: () -> Unit,
    onNameChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onListeningAudienceChange: (ProfileAudience) -> Unit,
    onFollowingVisibilityChange: (ProfileAudience) -> Unit,
    onFollowersVisibilityChange: (ProfileAudience) -> Unit,
    onPlayCountVisibilityChange: (ProfileAudience) -> Unit,
    onFavoriteArtistsVisibilityChange: (ProfileAudience) -> Unit,
    onFavoriteAlbumsVisibilityChange: (ProfileAudience) -> Unit,
    onFavoriteSongsVisibilityChange: (ProfileAudience) -> Unit,
    onRecentRatingsAudienceChange: (ProfileAudience) -> Unit,
    onSave: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    if (showDeleteConfirmation) {
        com.music.orb.ui.components.OrbConfirmationDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.profile_delete_account)) },
            text = { Text(stringResource(R.string.profile_delete_account_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteAccount()
                    },
                ) {
                    Text(
                        stringResource(R.string.profile_delete_account_confirm_action),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    val usernameLocked = usernameLockedUntil?.isAfter(Instant.now()) == true
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 110.dp, bottom = bottomPadding + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (selectedAvatar != null) {
                ProfileCropThumbnail(
                    crop = selectedAvatar.icon,
                    modifier = Modifier
                        .size(124.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            } else {
                AsyncImage(
                    model = currentAvatarIcon ?: currentAvatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(124.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            }
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onChoosePhoto, enabled = !saving) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_change_photo))
            }
            selectedAvatar?.let { selection ->
                ProfileSelectionPreview(selection.portrait, name, username)
                Spacer(Modifier.height(16.dp))
            }
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.profile_display_name)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.profile_username)) },
                prefix = { Text("@") },
                singleLine = true,
                enabled = !saving && !usernameLocked,
                supportingText = {
                    Text(
                        if (usernameLockedUntil != null && usernameLocked) {
                            stringResource(R.string.profile_username_available_on, formatProfileDate(usernameLockedUntil))
                        } else {
                            stringResource(R.string.profile_username_monthly_rule)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
            SettingsGroup(
                header = stringResource(R.string.profile_privacy_header),
                footer = stringResource(R.string.profile_privacy_footer),
            ) {
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.Headphones,
                    title = stringResource(R.string.profile_listening_visibility),
                    subtitle = stringResource(R.string.profile_listening_visibility_subtitle),
                    selected = listeningAudience,
                    enabled = !saving,
                    onSelected = onListeningAudienceChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.profile_favorite_artists),
                    selected = favoriteArtistsAudience,
                    enabled = !saving,
                    onSelected = onFavoriteArtistsVisibilityChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.Album,
                    title = stringResource(R.string.profile_favorite_albums),
                    selected = favoriteAlbumsAudience,
                    enabled = !saving,
                    onSelected = onFavoriteAlbumsVisibilityChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.MusicNote,
                    title = stringResource(R.string.profile_favorite_songs),
                    selected = favoriteSongsAudience,
                    enabled = !saving,
                    onSelected = onFavoriteSongsVisibilityChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.Star,
                    title = stringResource(R.string.profile_recent_album_ratings),
                    subtitle = stringResource(R.string.profile_recent_ratings_visibility_subtitle),
                    selected = recentRatingsAudience,
                    enabled = !saving,
                    onSelected = onRecentRatingsAudienceChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.Group,
                    title = stringResource(R.string.profile_share_following),
                    selected = followingAudience,
                    enabled = !saving,
                    onSelected = onFollowingVisibilityChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.PersonAdd,
                    title = stringResource(R.string.profile_share_followers),
                    selected = followersAudience,
                    enabled = !saving,
                    onSelected = onFollowersVisibilityChange,
                )
                RowDivider()
                ProfileVisibilityAudienceRow(
                    icon = Icons.Rounded.Equalizer,
                    title = stringResource(R.string.profile_share_play_count),
                    selected = playCountAudience,
                    enabled = !saving,
                    onSelected = onPlayCountVisibilityChange,
                )
            }
            errorRes?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            HoldToDeleteAccountButton(
                enabled = !saving,
                onConfirmed = { showDeleteConfirmation = true },
            )
            Text(
                text = stringResource(R.string.profile_delete_account_hold_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
                    .widthIn(max = 260.dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).zIndex(3f),
        ) {
            Row(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onClose, enabled = !saving) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.profile_cancel),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = stringResource(R.string.profile_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSave, enabled = !saving) {
                    if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.profile_save))
                }
            }
        }
    }
}

@Composable
private fun HoldToDeleteAccountButton(
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    val currentConfirm by rememberUpdatedState(onConfirmed)
    val progress by animateFloatAsState(
        targetValue = if (holding && enabled) 1f else 0f,
        animationSpec = if (holding && enabled) {
            tween(durationMillis = 900, easing = LinearEasing)
        } else {
            tween(durationMillis = 180)
        },
        label = "profileDeleteHoldProgress",
    )

    LaunchedEffect(holding, enabled) {
        if (!holding || !enabled) return@LaunchedEffect
        delay(900L)
        if (holding && enabled) {
            holding = false
            OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
            currentConfirm()
        }
    }

    val shape = RoundedCornerShape(18.dp)
    val foreground = MaterialTheme.colorScheme.primary

    // Intentionally mirrors the compact "Sair" button used on the Account page.
    // The progress layer is matchParentSize(), so it never changes the button's
    // measured width while the user is holding it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 128.dp, max = 184.dp)
                .clip(shape)
                .orbGlassSurface(
                    pageColor = orbOverlayGlassBaseColor(),
                    cornerRadius = 18.dp,
                    observeTapFeedback = false,
                    manageDialogBackdrop = false,
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            holding = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                holding = false
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = progress.coerceIn(0f, 1f)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }
                    .background(
                        foreground.copy(alpha = if (enabled) 0.18f else 0.08f),
                    ),
            )
            Text(
                text = stringResource(R.string.profile_delete_account),
                style = MaterialTheme.typography.bodyLarge,
                color = foreground.copy(alpha = if (enabled) 1f else 0.38f),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ProfileVisibilityAudienceRow(
    icon: ImageVector = Icons.Rounded.Visibility,
    title: String,
    subtitle: String? = null,
    selected: ProfileAudience,
    enabled: Boolean,
    onSelected: (ProfileAudience) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val popupPageColor = orbOverlayGlassBaseColor()
    val popupSubstrate = orbExpressiveGlassTone(popupPageColor)
    Box(Modifier.fillMaxWidth()) {
        SettingsRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            value = profileAudienceLabel(selected),
            enabled = enabled,
            onClick = { if (enabled) expanded = true },
        )
        // Align the popup to the audience value at the right, not to a 1dp
        // anchor on the screen edge (which pushes Material's menu to the left).
        // The one-pixel-high anchor is at the row bottom so the menu opens from
        // the tapped field, below it when there is space.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp)
                .width(184.dp)
                .height(1.dp),
        ) {
            OrbGlassContentTheme(popupPageColor) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .width(184.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(popupSubstrate.copy(alpha = 0.96f))
                        .orbGlassSurface(
                            pageColor = popupPageColor,
                            cornerRadius = 20.dp,
                            overlayStrength = true,
                            overlayOpacity = 0.82f,
                            manageDialogBackdrop = false,
                            observeTapFeedback = false,
                        ),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                ) {
                    ProfileAudience.profileSectionChoices.forEach { audience ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = profileAudienceLabel(audience),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = if (selected == audience) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            } else null,
                            onClick = {
                                expanded = false
                                onSelected(audience)
                            },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAudienceEditor(
    title: String,
    subtitle: String,
    selected: ProfileAudience,
    enabled: Boolean,
    onSelected: (ProfileAudience) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .orbGlassSurface(
                pageColor = orbOverlayGlassBaseColor(),
                cornerRadius = 24.dp,
                manageDialogBackdrop = false,
            )
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ProfileAudience.profileSectionChoices.forEach { audience ->
                FilterChip(
                    selected = selected == audience,
                    onClick = { onSelected(audience) },
                    enabled = enabled,
                    label = { Text(profileAudienceLabel(audience)) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Flat action inside the shared glass popup; no per-item button background. */
@Composable
private fun ProfilePeopleMenuAction(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProfilePeopleListPage(
    title: String,
    profiles: List<OrbProfile>,
    loading: Boolean,
    pageColor: Color,
    palette: ArtworkPalette,
    bottomPadding: androidx.compose.ui.unit.Dp,
    viewerFollowingIds: Set<String>,
    viewerFollowerIds: Set<String>,
    onOpenProfile: (String) -> Unit,
    onUnfollow: suspend (OrbProfile) -> Unit,
    onRemoveFollower: suspend (OrbProfile) -> Unit,
    onBlock: suspend (OrbProfile) -> Unit,
    onBack: () -> Unit,
    onTopBarInfoChange: (ProfileTopBarInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var busyPersonId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title, pageColor, palette.onBackground, palette.onBackgroundVariant) {
        onTopBarInfoChange(
            ProfileTopBarInfo(
                title = title,
                subtitle = null,
                pageColor = pageColor,
                contentColor = palette.onBackground,
                contentVariantColor = palette.onBackgroundVariant,
                collapseOffsetPx = 0f,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listScrollState)
                .padding(top = 94.dp, bottom = bottomPadding + 24.dp),
        ) {
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = palette.accent)
                }
            } else if (profiles.isEmpty()) {
                ImmersiveMessage(stringResource(R.string.profile_public_data_unavailable))
            } else {
                profiles.forEachIndexed { index, person ->
                    var menuExpanded by remember(person.id) { mutableStateOf(false) }
                    val followsPerson = person.id in viewerFollowingIds
                    val followedByPerson = person.id in viewerFollowerIds
                    val busy = busyPersonId == person.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { onOpenProfile(person.id) }
                            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = person.avatarIconUrl?.takeIf { it.isNotBlank() } ?: person.avatarUrl,
                            contentDescription = person.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(palette.elevated),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = person.displayName.ifBlank { person.username ?: "Orb" },
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            person.username?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = "@$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.onBackgroundVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { if (!busy) menuExpanded = true },
                                enabled = !busy,
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.onBackgroundVariant,
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = stringResource(R.string.profile_action_more),
                                        tint = palette.onBackgroundVariant,
                                    )
                                }
                            }
                            // The popup itself provides the glass container. Its actions
                            // are flat, transparent rows rather than nested button surfaces.
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier.orbGlassSurface(
                                    orbOverlayGlassBaseColor(),
                                    cornerRadius = 18.dp,
                                ),
                                containerColor = Color.Transparent,
                            ) {
                                if (followsPerson) {
                                    ProfilePeopleMenuAction(
                                        label = stringResource(R.string.profile_action_unfollow),
                                        onClick = {
                                            menuExpanded = false
                                            scope.launch {
                                                busyPersonId = person.id
                                                runCatching { onUnfollow(person) }
                                                busyPersonId = null
                                            }
                                        },
                                    )
                                }
                                if (followedByPerson) {
                                    ProfilePeopleMenuAction(
                                        label = stringResource(R.string.profile_action_remove_follower),
                                        onClick = {
                                            menuExpanded = false
                                            scope.launch {
                                                busyPersonId = person.id
                                                runCatching { onRemoveFollower(person) }
                                                busyPersonId = null
                                            }
                                        },
                                    )
                                }
                                ProfilePeopleMenuAction(
                                    label = stringResource(R.string.profile_action_block),
                                    onClick = {
                                        menuExpanded = false
                                        scope.launch {
                                            busyPersonId = person.id
                                            runCatching { onBlock(person) }
                                            busyPersonId = null
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (index < profiles.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = PAGE_GUTTER + 64.dp, end = PAGE_GUTTER)
                                .height(0.5.dp)
                                .background(palette.divider),
                        )
                    }
                }
            }
        }

        // Global header rule: back is only the arrow, never a filled circle.
        androidx.compose.material3.IconButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(start = 12.dp, top = 8.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = null,
                tint = palette.onBackground,
            )
        }
    }
}

@Composable
private fun ProfileTasteCarouselSection(
    title: String,
    tastes: List<ImmersiveProfileTaste>,
    palette: ArtworkPalette,
    placeholderIcon: ImageVector,
) {
    if (tastes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(tastes, key = { "${it.name}:${it.subtitle.orEmpty()}" }) { taste ->
                Column(modifier = Modifier.width(118.dp).height(188.dp)) {
                    val shape = RoundedCornerShape(14.dp)
                    if (taste.artworkUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(118.dp)
                                .clip(shape)
                                .background(palette.elevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = placeholderIcon,
                                contentDescription = null,
                                tint = palette.onBackgroundVariant,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    } else {
                        AsyncImage(
                            model = taste.artworkUrl.artworkAt(320),
                            contentDescription = taste.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(118.dp)
                                .clip(shape)
                                .background(palette.elevated),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = taste.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    taste.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onBackgroundVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSongGridSection(
    title: String,
    tastes: List<ImmersiveProfileTaste>,
    palette: ArtworkPalette,
) {
    if (tastes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )
        val rankedSongs = tastes.take(10)
        repeat((rankedSongs.size + 1) / 2) { row ->
            // Preserve ranking row-major: 1–2, 3–4, 5–6, 7–8, 9–10.
            val leftSong = rankedSongs.getOrNull(row * 2) ?: return@repeat
            val rightSong = rankedSongs.getOrNull(row * 2 + 1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
                    .height(62.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoriteSongCell(
                    taste = leftSong,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(palette.onBackground.copy(alpha = 0.16f)),
                )

                if (rightSong != null) {
                    FavoriteSongCell(
                        taste = rightSong,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FavoriteSongCell(
    taste: ImmersiveProfileTaste,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artModifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(9.dp))

        if (taste.artworkUrl.isNullOrBlank()) {
            Box(
                modifier = artModifier.background(palette.elevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = palette.onBackgroundVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            AsyncImage(
                model = taste.artworkUrl.artworkAt(192),
                contentDescription = taste.name,
                contentScale = ContentScale.Crop,
                modifier = artModifier,
            )
        }

        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = taste.name,
                style = MaterialTheme.typography.titleSmall,
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            taste.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private suspend fun resolveProfileAlbumRatings(
    rows: List<ProfileAlbumRatingRow>,
): List<ProfileAlbumRatingCard> = supervisorScope {
    rows.take(10).map { row ->
        async {
            val page = runCatching { YtMusicRepository.browseSongs(row.albumId).getOrThrow() }.getOrNull()
            val firstSong = page?.songs?.firstOrNull()
            val title = page?.title?.takeIf { it.isNotBlank() }
                ?: firstSong?.albumName?.takeIf { it.isNotBlank() }
                ?: row.albumId
            val artist = page?.headerArtists
                ?.map { it.name.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
                ?: firstSong?.artist.orEmpty()
            ProfileAlbumRatingCard(
                albumId = row.albumId,
                title = title,
                artist = artist,
                artworkUrl = page?.thumbnailUrl ?: firstSong?.thumbnailUrl,
                rating = row.ratingTenths.coerceIn(0, 50) / 10f,
                updatedAt = row.updatedAt,
            )
        }
    }.awaitAll()
}

@Composable
private fun ProfileAlbumRatingGridSection(
    title: String,
    ratings: List<ProfileAlbumRatingCard>,
    palette: ArtworkPalette,
) {
    if (ratings.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )
        val visible = ratings.take(10)
        repeat((visible.size + 1) / 2) { row ->
            val left = visible.getOrNull(row * 2) ?: return@repeat
            val right = visible.getOrNull(row * 2 + 1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
                    .height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAlbumRatingCell(left, palette, Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(palette.onBackground.copy(alpha = 0.16f)),
                )
                if (right != null) {
                    ProfileAlbumRatingCell(right, palette, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfileAlbumRatingCell(
    rating: ProfileAlbumRatingCard,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artModifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(9.dp))
        if (rating.artworkUrl.isNullOrBlank()) {
            Box(
                modifier = artModifier.background(palette.elevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = palette.onBackgroundVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            AsyncImage(
                model = rating.artworkUrl.artworkAt(192),
                contentDescription = rating.title,
                contentScale = ContentScale.Crop,
                modifier = artModifier,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = rating.title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            rating.artist.takeIf { it.isNotBlank() }?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier
                    .height(14.5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .orbBadgeGlassSurface(
                        pageColor = palette.background,
                        cornerRadius = 5.dp,
                        drawRim = false,
                    )
                    .padding(horizontal = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = palette.onBackground,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = String.format(Locale.getDefault(), "%.1f", rating.rating),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onBackground,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ImmersiveCompatibilityCard(
    compatibility: ImmersiveCompatibility,
    palette: ArtworkPalette,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 6.dp),
    ) {
        val featureWidth = (maxWidth - PAGE_GUTTER * 2f) * 0.92f
        Column(
            modifier = Modifier
                .width(featureWidth)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(34.dp))
                .background(palette.elevated.copy(alpha = 0.86f))
                .border(
                    width = 0.5.dp,
                    color = palette.onBackground.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(34.dp),
                )
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(0.92f)) {
                    Text(
                        text = stringResource(R.string.profile_compatibility_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onBackgroundVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${compatibility.percent}%",
                        style = MaterialTheme.typography.displayLarge,
                        color = palette.accent,
                    )
                }
                if (compatibility.genres.isNotEmpty()) {
                    Column(
                        modifier = Modifier.weight(1.08f),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        compatibility.genres.take(5).chunked(2).forEach { rowGenres ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rowGenres.forEach { genre ->
                                    Text(
                                        text = genre,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.onBackground,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(palette.onBackground.copy(alpha = 0.10f))
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (compatibility.genres.isNotEmpty()) {
                    stringResource(
                        R.string.profile_compatibility_genres_subtitle,
                        compatibility.genres.take(5).joinToString(", "),
                    )
                } else {
                    stringResource(R.string.profile_compatibility_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ImmersiveNowPlayingCard(value: FriendNowPlaying) {
    ImmersiveMediaCard(stringResource(R.string.profile_listening_now), value.artworkUrl, value.title, value.artist)
}

@Composable
private fun ImmersiveLastPlayedCard(value: ListeningActivity) {
    ImmersiveMediaCard(stringResource(R.string.profile_last_played), value.artworkUrl, value.title, value.artist)
}

@Composable
private fun ImmersiveMediaCard(header: String, artwork: String?, title: String, artist: String) {
    SettingsGroup(header = header) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .orbGlassSurface(
                    pageColor = orbOverlayGlassBaseColor(),
                    cornerRadius = 20.dp,
                    manageDialogBackdrop = false,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artwork.isNullOrBlank()) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                }
            } else {
                AsyncImage(model = artwork.artworkAt(160), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ImmersiveMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 14.dp)
            .clip(RoundedCornerShape(24.dp))
            .orbGlassSurface(
                pageColor = orbOverlayGlassBaseColor(),
                cornerRadius = 24.dp,
                manageDialogBackdrop = false,
            )
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private suspend fun loadProfileSourceBitmap(
    context: android.content.Context,
    uri: Uri,
): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(uri)
        // Large enough for the 1080x1080 profile export while avoiding a
        // full-resolution phone photo living in Compose memory. Coil also
        // respects the source orientation, unlike the old raw BitmapFactory
        // path that could make portrait photos appear rotated/stretched.
        .size(2048)
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    return (result as? SuccessResult)?.image?.toBitmap()
}

/**
 * The original image remains intact while the user positions it in a square
 * selection viewport. After confirmation, both profile photo and icon are
 * exported in 1:1. Geometry matches ContentScale.Crop + graphicsLayer in UI.
 * The output always fills the square with real image pixels (no black bars).
 */
private fun renderProfileCropBitmap(
    bitmap: Bitmap,
    crop: ProfileImageCrop,
    targetWidthPx: Int,
    targetHeightPx: Int,
): Bitmap {
    require(bitmap.width > 0 && bitmap.height > 0) { "Unable to decode profile image" }
    require(targetWidthPx > 0 && targetHeightPx > 0) { "Invalid profile image target." }

    val width = targetWidthPx.toFloat()
    val height = targetHeightPx.toFloat()
    val fillScale = max(width / bitmap.width, height / bitmap.height)
    val zoom = crop.zoom.coerceIn(1f, 4f)
    val drawWidth = bitmap.width * fillScale * zoom
    val drawHeight = bitmap.height * fillScale * zoom
    val dx = (crop.offsetXFraction * width).coerceIn(
        -max(0f, (drawWidth - width) / 2f), max(0f, (drawWidth - width) / 2f),
    )
    val dy = (crop.offsetYFraction * height).coerceIn(
        -max(0f, (drawHeight - height) / 2f), max(0f, (drawHeight - height) / 2f),
    )

    val result = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(result)
    // No empty margins: the square is covered by the confirmed photo selection.
    canvas.drawBitmap(
        bitmap,
        null,
        RectF(
            (width - drawWidth) / 2f + dx,
            (height - drawHeight) / 2f + dy,
            (width + drawWidth) / 2f + dx,
            (height + drawHeight) / 2f + dy,
        ),
        AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG),
    )
    return result
}

/** Checks actual source bytes before decoding, including providers with unknown size. */
private suspend fun isProfileSourceWithinLimit(
    context: android.content.Context,
    uri: Uri,
): Boolean = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Unable to open the selected profile image")
    input.use { stream ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > PROFILE_IMAGE_MAX_BYTES.toLong()) return@withContext false
        }
        true
    }
}

private suspend fun readProfileImage(
    context: android.content.Context,
    crop: ProfileImageCrop,
    targetWidthPx: Int,
    targetHeightPx: Int,
): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
    val bitmap = loadProfileSourceBitmap(context, crop.uri)
        ?: error("Unable to read profile image")
    val output = renderProfileCropBitmap(bitmap, crop, targetWidthPx, targetHeightPx)
    val stream = ByteArrayOutputStream()
    try {
        check(output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) { "Unable to encode profile image" }
    } finally {
        if (output !== bitmap) output.recycle()
    }
    val bytes = stream.toByteArray()
    require(bytes.size <= PROFILE_IMAGE_MAX_BYTES) { "Profile images can be at most 50 MB." }
    bytes to "image/jpeg"
}

private fun OrbProfile?.usernameLockedUntil(): Instant? {
    val value = this?.usernameUpdatedAt ?: return null
    val changed = runCatching { Instant.parse(value) }.getOrNull() ?: return null
    return changed.atZone(ZoneId.of("UTC")).plusMonths(1).toInstant()
}

private fun formatProfileDate(value: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(value)

private fun formatProfileCount(value: Long): String = when {
    value >= 1_000_000 && value % 1_000_000L == 0L -> "${value / 1_000_000L}M"
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
    value >= 1_000 && value % 1_000L == 0L -> "${value / 1_000L}K"
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000f)
    else -> value.toString()
}

private fun buildImmersiveTasteEntries(activities: List<ListeningActivity>): List<ImmersiveProfileTaste> =
    buildImmersiveTasteEntries(
        artistActivities = activities,
        albumActivities = activities,
        trackActivities = activities,
    )

private fun buildImmersiveTasteEntries(
    artistActivities: List<ListeningActivity>,
    albumActivities: List<ListeningActivity>,
    trackActivities: List<ListeningActivity>,
): List<ImmersiveProfileTaste> {
    // Profile favorites are the monthly Stats ranking rendered in profile form.
    // Separate activity projections preserve privacy per category while the
    // ranking functions remain exactly the same ones Stats uses.
    val artistMonthly = currentMonthListeningActivity(artistActivities)
    val albumMonthly = currentMonthListeningActivity(albumActivities)
    val trackMonthly = currentMonthListeningActivity(trackActivities)

    val artists = rankListeningArtists(artistMonthly, limit = 10).map { ranked ->
        ImmersiveProfileTaste(
            name = ranked.title,
            artworkUrl = ArtistArtworkRepository.cached(ranked.title),
            plays = ranked.plays,
        )
    }

    val albums = rankListeningAlbums(albumMonthly, limit = 10).map { ranked ->
        ImmersiveProfileTaste(
            name = ranked.title,
            subtitle = ranked.subtitle,
            artworkUrl = ranked.artworkUrl,
            plays = ranked.plays,
        )
    }

    val tracks = rankListeningTracks(trackMonthly, limit = 10).map { ranked ->
        ImmersiveProfileTaste(
            name = ranked.title,
            subtitle = ranked.subtitle,
            artworkUrl = ranked.artworkUrl,
            plays = ranked.plays,
            // Preserve the real video id when present; fallback identities stay
            // namespaced exactly like the previous profile cache format.
            videoId = when {
                ranked.identity.isBlank() -> "profile:${ranked.title}"
                '' in ranked.identity -> "profile:${ranked.identity}"
                else -> ranked.identity
            },
        )
    }

    return artists + albums + tracks
}

private suspend fun enrichImmersiveArtistTastes(
    tastes: List<ImmersiveProfileTaste>,
    onResolved: (String, String?) -> Unit,
) = supervisorScope {
    tastes.asSequence()
        .filter { it.isArtist }
        .take(10)
        .distinctBy { it.name.trim().lowercase() }
        .map { taste -> launch { onResolved(taste.name, ArtistArtworkRepository.resolve(taste.name)) } }
        .toList()
        .joinAll()
}

private fun List<ImmersiveProfileTaste>.withImmersiveArtistArtwork(
    artist: String,
    artwork: String?,
): List<ImmersiveProfileTaste> = map { taste ->
    if (taste.isArtist && taste.name.equals(artist, ignoreCase = true)) taste.copy(artworkUrl = artwork) else taste
}

private fun calculateImmersiveCompatibility(
    mine: List<ListeningActivity>,
    theirs: List<ListeningActivity>,
): Int? {
    val qualifiedMine = mine.filter { it.reachedListenThreshold() }
    val qualifiedTheirs = theirs.filter { it.reachedListenThreshold() }
    val myArtists = qualifiedMine
        .flatMap { ArtistCreditResolver.creditsForCounting(it.artist) }
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
    val theirArtists = qualifiedTheirs
        .flatMap { ArtistCreditResolver.creditsForCounting(it.artist) }
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
    val myAlbums = qualifiedMine.mapNotNull { it.album?.trim()?.lowercase()?.takeIf(String::isNotBlank) }.toSet()
    val theirAlbums = qualifiedTheirs.mapNotNull { it.album?.trim()?.lowercase()?.takeIf(String::isNotBlank) }.toSet()
    if (myArtists.isEmpty() || theirArtists.isEmpty()) return null
    val artistScore = immersiveOverlap(myArtists, theirArtists)
    val albumScore = if (myAlbums.isEmpty() || theirAlbums.isEmpty()) artistScore else immersiveOverlap(myAlbums, theirAlbums)
    return ((artistScore * 0.72f + albumScore * 0.28f) * 100f).toInt().coerceIn(0, 100)
}

private fun immersiveOverlap(first: Set<String>, second: Set<String>): Float {
    val union = first union second
    return if (union.isEmpty()) 0f else (first intersect second).size.toFloat() / union.size.toFloat()
}


private suspend fun calculateCompatibleGenreTags(
    mine: List<ListeningActivity>,
    theirs: List<ListeningActivity>,
): List<String> = coroutineScope {
    val myArtists = profileArtistCounts(mine).take(12)
    val theirArtists = profileArtistCounts(theirs).take(12)
    if (myArtists.isEmpty() || theirArtists.isEmpty()) return@coroutineScope emptyList()

    val artistNames = (myArtists.map { it.first } + theirArtists.map { it.first })
        .distinctBy { it.trim().lowercase(Locale.ROOT) }
    val genresByArtist = artistNames.map { artist ->
        async {
            artist.trim().lowercase(Locale.ROOT) to ArtistArtworkRepository.resolveGenres(artist)
                .mapNotNull(::normalizeOrbGenre)
                .distinct()
        }
    }.awaitAll().toMap()

    val myWeights = profileGenreWeights(myArtists, genresByArtist)
    val theirWeights = profileGenreWeights(theirArtists, genresByArtist)
    (myWeights.keys intersect theirWeights.keys)
        .map { genre -> genre to min(myWeights.getValue(genre), theirWeights.getValue(genre)) }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(5)
}

private fun profileArtistCounts(activities: List<ListeningActivity>): List<Pair<String, Int>> =
    activities
        .asSequence()
        .filter { it.reachedListenThreshold() }
        .flatMap { row -> ArtistCreditResolver.creditsForCounting(row.artist).asSequence() }
        .filter { it.isNotBlank() }
        .groupBy { it.trim().lowercase(Locale.ROOT) }
        .values
        .map { credits -> credits.first() to credits.size }
        .sortedByDescending { it.second }

private fun profileGenreWeights(
    artists: List<Pair<String, Int>>,
    genresByArtist: Map<String, List<String>>,
): Map<String, Float> {
    val total = artists.sumOf { it.second }.coerceAtLeast(1).toFloat()
    val out = linkedMapOf<String, Float>()
    artists.forEach { (artist, plays) ->
        val genres = genresByArtist[artist.trim().lowercase(Locale.ROOT)].orEmpty()
        if (genres.isEmpty()) return@forEach
        val perGenre = (plays / total) / genres.size.toFloat()
        genres.forEach { genre -> out[genre] = (out[genre] ?: 0f) + perGenre }
    }
    return out
}

private fun profileSectionAudience(value: String?): ProfileAudience = when (ProfileAudience.fromWire(value)) {
    ProfileAudience.EVERYONE -> ProfileAudience.EVERYONE
    ProfileAudience.FOLLOWERS -> ProfileAudience.FOLLOWERS
    ProfileAudience.NOBODY -> ProfileAudience.NOBODY
}

private fun canViewProfileAudience(
    value: String?,
    viewerFollowsTarget: Boolean,
    targetFollowsViewer: Boolean,
    isOwner: Boolean = false,
): Boolean {
    if (isOwner) return true
    return when (ProfileAudience.fromWire(value)) {
        ProfileAudience.EVERYONE -> true
        ProfileAudience.FOLLOWERS -> viewerFollowsTarget
        ProfileAudience.NOBODY -> false
    }
}

@Composable
private fun profileAudienceLabel(value: ProfileAudience): String = when (value) {
    ProfileAudience.EVERYONE -> stringResource(R.string.profile_audience_everyone)
    ProfileAudience.FOLLOWERS -> stringResource(R.string.profile_audience_followers)
    ProfileAudience.NOBODY -> stringResource(R.string.profile_audience_nobody)
}
