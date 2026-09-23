package com.music.orb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.ArtistCreditResolver
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.data.model.Account
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.ProfileAudience
import com.music.orb.data.settings.ProfilePrivacyStore
import com.music.orb.data.social.FriendNowPlaying
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.OrbProfile
import com.music.orb.data.social.ProfileSocialCounts
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.social.reachedListenThreshold
import com.music.orb.ui.currentMonthListeningActivity
import com.music.orb.ui.currentMonthStartInstant
import com.music.orb.ui.rankListeningAlbums
import com.music.orb.ui.rankListeningArtists
import com.music.orb.ui.components.PAGE_GUTTER
import java.util.Locale
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

private data class ProfileTaste(
    val name: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val plays: Int,
)

@Composable
private fun LegacyOrbProfileScreen(
    account: Account?,
    initialProfile: OrbProfile? = null,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val shareNowPlaying by ProfilePrivacyStore.shareNowPlaying.collectAsStateWithLifecycle()
    val favoriteContentAudience by ProfilePrivacyStore.favoriteContentAudience.collectAsStateWithLifecycle()
    var profile by remember(initialProfile?.id) { mutableStateOf(initialProfile) }
    var following by remember(initialProfile?.id) { mutableStateOf<List<OrbProfile>>(emptyList()) }
    var followers by remember(initialProfile?.id) { mutableStateOf<List<OrbProfile>>(emptyList()) }
    var counts by remember(initialProfile?.id) { mutableStateOf<ProfileSocialCounts?>(null) }
    var tastes by remember(initialProfile?.id) { mutableStateOf<List<ProfileTaste>>(emptyList()) }
    var loading by remember(initialProfile?.id) { mutableStateOf(true) }
    var username by remember(initialProfile?.id) { mutableStateOf(initialProfile?.username.orEmpty()) }
    var usernameSaving by remember(initialProfile?.id) { mutableStateOf(false) }
    var usernameError by remember(initialProfile?.id) { mutableStateOf(false) }

    LaunchedEffect(initialProfile?.id) {
        val userId = SocialRepository.currentUserId()
        if (userId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val loadedProfile = runCatching { SocialRepository.profile(userId) }.getOrNull()
        profile = loadedProfile ?: initialProfile
        username = profile?.username.orEmpty()
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
            )
        }
        following = runCatching { SocialRepository.followingProfiles() }.getOrDefault(emptyList())
        followers = runCatching { SocialRepository.followersProfiles() }.getOrDefault(emptyList())
        counts = runCatching { SocialRepository.socialCounts(userId) }.getOrNull()
        val initialTastes = runCatching {
            val activities = SocialRepository.myListeningActivitySince(
                since = currentMonthStartInstant(),
                maxRows = 20_000,
            )
            ArtistCreditResolver.resolveAll(activities.map { it.artist })
            buildTasteEntries(activities)
        }.getOrDefault(emptyList())
        tastes = initialTastes
        loading = false
        enrichArtistTastesProgressively(initialTastes) { artist, artwork ->
            tastes = tastes.withArtistArtwork(artist, artwork)
        }
    }

    fun savePrivacy(
        share: Boolean = shareNowPlaying,
        favorites: ProfileAudience = favoriteContentAudience,
    ) {
        scope.launch {
            SocialRepository.updateProfilePrivacy(
                nowPlayingVisibility = if (share) {
                    ProfileAudience.FOLLOWERS.wireValue
                } else {
                    ProfileAudience.NOBODY.wireValue
                },
                listeningVisibility = if (share) {
                    ProfileAudience.FOLLOWERS.wireValue
                } else {
                    ProfileAudience.NOBODY.wireValue
                },
                favoriteContentVisibility = favorites.wireValue,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        HomeHeaderAura(
            scrollOffsetPx = scrollState.value,
            modifier = Modifier
                .fillMaxWidth()
                .height(PAGE_AURA_HEIGHT)
                .align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(contentPadding),
        ) {
            ProfileHero(
                profile = profile,
                account = account,
                loading = loading,
            )

            if (profile?.username.isNullOrBlank()) {
                UsernameInlineSetup(
                    username = username,
                    saving = usernameSaving,
                    error = usernameError,
                    onUsernameChange = {
                        username = it.trimStart().removePrefix("@").lowercase().take(30)
                        usernameError = false
                    },
                    onSave = {
                        val normalized = username.trim().removePrefix("@").lowercase()
                        if (!USERNAME_REGEX.matches(normalized)) {
                            usernameError = true
                        } else {
                            scope.launch {
                                usernameSaving = true
                                val result = SocialRepository.setUsername(normalized)
                                if (result.isSuccess) {
                                    username = normalized
                                    profile = (profile ?: OrbProfile(
                                        id = SocialRepository.currentUserId().orEmpty(),
                                    )).copy(username = normalized)
                                    usernameError = false
                                } else {
                                    usernameError = true
                                }
                                usernameSaving = false
                            }
                        }
                    },
                )
            }

            SettingsGroup(header = stringResource(R.string.profile_social_header)) {
                ProfileCountRow(
                    icon = Icons.Rounded.Group,
                    label = stringResource(R.string.profile_following),
                    count = counts?.followingCount?.toInt() ?: following.size,
                )
                RowDivider()
                ProfileCountRow(
                    icon = Icons.Rounded.Person,
                    label = stringResource(R.string.profile_followers),
                    count = counts?.followersCount?.toInt() ?: followers.size,
                )
            }

            ProfilePeopleSection(
                title = stringResource(R.string.profile_following),
                profiles = following,
            )
            ProfilePeopleSection(
                title = stringResource(R.string.profile_followers),
                profiles = followers,
            )

            SettingsGroup(
                header = stringResource(R.string.profile_privacy_header),
                footer = stringResource(R.string.profile_privacy_footer),
            ) {
                SettingsRow(
                    icon = Icons.Rounded.Visibility,
                    title = stringResource(R.string.profile_share_now_playing),
                    subtitle = stringResource(R.string.profile_share_now_playing_subtitle),
                    trailing = {
                        Switch(
                            checked = shareNowPlaying,
                            onCheckedChange = {
                                ProfilePrivacyStore.setShareNowPlaying(it)
                                savePrivacy(share = it)
                            },
                        )
                    },
                )
            }

            VisibilityGroup(
                title = stringResource(R.string.profile_favorites_visibility),
                subtitle = stringResource(R.string.profile_favorites_visibility_subtitle),
                selected = favoriteContentAudience,
                onSelected = {
                    ProfilePrivacyStore.setFavoriteContentAudience(it)
                    savePrivacy(favorites = it)
                },
            )

            TasteSection(
                title = stringResource(R.string.profile_favorite_artists),
                icon = Icons.Rounded.Person,
                tastes = tastes.filter { it.subtitle == null }.take(5),
            )
            TasteSection(
                title = stringResource(R.string.profile_favorite_albums),
                icon = Icons.Rounded.Album,
                tastes = tastes.filter { it.subtitle != null }.take(5),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LegacyPublicProfileScreen(
    userId: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var profile by remember(userId) { mutableStateOf<OrbProfile?>(null) }
    var nowPlaying by remember(userId) { mutableStateOf<FriendNowPlaying?>(null) }
    var activities by remember(userId) { mutableStateOf<List<ListeningActivity>>(emptyList()) }
    var tastes by remember(userId) { mutableStateOf<List<ProfileTaste>>(emptyList()) }
    var compatibility by remember(userId) { mutableStateOf<Int?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }

    LaunchedEffect(userId) {
        loading = true
        profile = runCatching { SocialRepository.profile(userId) }.getOrNull()
        nowPlaying = runCatching {
            SocialRepository.nowPlayingForUsers(listOf(userId))[userId]
        }.getOrNull()
        // Raw activity is the friends/Stats privacy surface: only followers
        // can receive it, and only while sharing is enabled.
        activities = runCatching {
            SocialRepository.listeningActivityForUser(userId, maxRows = 10_000)
        }.getOrDefault(emptyList())
        val me = SocialRepository.currentUserId()
        val profileTasteActivities = runCatching {
            if (userId == me) {
                SocialRepository.myListeningActivitySince(
                    since = currentMonthStartInstant(),
                    maxRows = 20_000,
                )
            } else {
                SocialRepository.profileMonthlyActivityForUser(userId, maxRows = 20_000)
            }
        }.getOrDefault(emptyList())
        val mine = if (me != null) {
            runCatching { SocialRepository.listeningActivityForUser(me) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        ArtistCreditResolver.resolveAll((profileTasteActivities + activities + mine).map { it.artist })
        val initialTastes = buildTasteEntries(profileTasteActivities)
        tastes = initialTastes
        val detailedCompatibility = if (me != null) calculateTasteCompatibility(mine, activities) else null
        compatibility = detailedCompatibility
            ?: runCatching { SocialRepository.profileMusicCompatibility(userId) }.getOrNull()
        loading = false
        enrichArtistTastesProgressively(initialTastes) { artist, artwork ->
            tastes = tastes.withArtistArtwork(artist, artwork)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        HomeHeaderAura(
            scrollOffsetPx = scrollState.value,
            modifier = Modifier
                .fillMaxWidth()
                .height(PAGE_AURA_HEIGHT)
                .align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(contentPadding),
        ) {
            ProfileHero(profile = profile, account = null, loading = loading)
            if (profile != null) {
                if (compatibility != null) {
                    CompatibilityCard(compatibility = compatibility!!)
                }
                nowPlaying?.takeIf { it.isFresh() }?.let { live ->
                    PublicNowPlayingCard(live)
                } ?: activities.firstOrNull()?.let { latest ->
                    PublicLastPlayedCard(latest)
                }
                TasteSection(
                    title = stringResource(R.string.profile_favorite_artists),
                    icon = Icons.Rounded.Person,
                    tastes = tastes.filter { it.subtitle == null }.take(5),
                )
                TasteSection(
                    title = stringResource(R.string.profile_favorite_albums),
                    icon = Icons.Rounded.Album,
                    tastes = tastes.filter { it.subtitle != null }.take(5),
                )
                if (compatibility == null && activities.isEmpty() && tastes.isEmpty()) {
                    ProfileMessage(stringResource(R.string.profile_public_data_unavailable))
                }
            } else if (!loading) {
                ProfileMessage(stringResource(R.string.profile_not_found))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileHero(profile: OrbProfile?, account: Account?, loading: Boolean) {
    val image = profile?.avatarIconUrl?.takeIf { it.isNotBlank() } ?: profile?.avatarUrl ?: account?.thumbnailUrl
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading && profile == null) {
            CircularProgressIndicator(modifier = Modifier.size(42.dp))
        } else {
            ProfileImage(image = image, size = 104)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = profile?.displayName?.takeIf { it.isNotBlank() }
                ?: account?.name
                ?: stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        profile?.username?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "@$it",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        profile?.bio?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ProfileImage(image: String?, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer)
    if (!image.isNullOrBlank()) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size((size * 0.48f).dp),
            )
        }
    }
}

@Composable
private fun ProfileCountRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProfilePeopleSection(title: String, profiles: List<OrbProfile>) {
    if (profiles.isEmpty()) return
    SettingsGroup(header = title) {
        profiles.take(8).forEachIndexed { index, profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileImage(profile.avatarIconUrl?.takeIf { it.isNotBlank() } ?: profile.avatarUrl, 42)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.displayLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    profile.username?.takeIf { it.isNotBlank() }?.let {
                        Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index == 0) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            if (index < profiles.take(8).lastIndex) RowDivider()
        }
    }
}

@Composable
private fun VisibilityGroup(
    title: String,
    subtitle: String,
    selected: ProfileAudience,
    onSelected: (ProfileAudience) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProfileAudience.profileSectionChoices.forEach { audience ->
                FilterChip(
                    selected = selected == audience,
                    onClick = { onSelected(audience) },
                    label = { Text(audienceLabel(audience), maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TasteSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tastes: List<ProfileTaste>,
) {
    if (tastes.isEmpty()) return
    SettingsGroup(header = title) {
        tastes.forEachIndexed { index, taste ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val artwork = taste.artworkUrl
                if (artwork.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                } else {
                    AsyncImage(
                        model = artwork.artworkAt(160),
                        contentDescription = taste.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(taste.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    taste.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(stringResource(R.string.profile_play_count, taste.plays), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (index < tastes.lastIndex) RowDivider()
        }
    }
}

@Composable
private fun CompatibilityCard(compatibility: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.profile_compatibility_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(5.dp))
            Text("$compatibility%", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(stringResource(R.string.profile_compatibility_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun PublicNowPlayingCard(nowPlaying: FriendNowPlaying) {
    SettingsGroup(header = stringResource(R.string.profile_listening_now)) {
        MediaLine(
            artworkUrl = nowPlaying.artworkUrl,
            title = nowPlaying.title,
            subtitle = nowPlaying.artist,
            trailing = stringResource(R.string.profile_listening_now),
        )
    }
}

@Composable
private fun PublicLastPlayedCard(activity: ListeningActivity) {
    SettingsGroup(header = stringResource(R.string.profile_last_played)) {
        MediaLine(
            artworkUrl = activity.artworkUrl,
            title = activity.title,
            subtitle = activity.artist,
            trailing = stringResource(R.string.profile_last_played),
        )
    }
}

@Composable
private fun MediaLine(artworkUrl: String?, title: String, subtitle: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artworkUrl.isNullOrBlank()) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null)
            }
        } else {
            AsyncImage(
                model = artworkUrl.artworkAt(160),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ProfileMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 14.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsernameInlineSetup(
    username: String,
    saving: Boolean,
    error: Boolean,
    onUsernameChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.profile_create_username_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.profile_create_username_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                singleLine = true,
                prefix = { Text("@") },
                isError = error,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(9.dp))
            Button(onClick = onSave, enabled = !saving && username.isNotBlank()) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.profile_save))
            }
        }
        if (error) {
            Text(stringResource(R.string.profile_username_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

private fun buildTasteEntries(activities: List<ListeningActivity>): List<ProfileTaste> {
    val monthly = currentMonthListeningActivity(activities)
    val artists = rankListeningArtists(monthly, limit = 10).map { ranked ->
        ProfileTaste(
            name = ranked.title,
            artworkUrl = ArtistArtworkRepository.cached(ranked.title),
            plays = ranked.plays,
        )
    }
    val albums = rankListeningAlbums(monthly, limit = 10).map { ranked ->
        ProfileTaste(
            name = ranked.title,
            subtitle = ranked.subtitle,
            artworkUrl = ranked.artworkUrl,
            plays = ranked.plays,
        )
    }
    return artists + albums
}

private suspend fun enrichArtistTastesProgressively(
    tastes: List<ProfileTaste>,
    onResolved: (artist: String, artwork: String?) -> Unit,
) = supervisorScope {
    tastes.asSequence()
        .filter { it.subtitle == null }
        .take(10)
        .distinctBy { it.name.trim().lowercase() }
        .map { taste ->
            launch {
                val artwork = ArtistArtworkRepository.resolve(taste.name)
                onResolved(taste.name, artwork)
            }
        }
        .toList()
        .joinAll()
}

private fun List<ProfileTaste>.withArtistArtwork(
    artist: String,
    artwork: String?,
): List<ProfileTaste> = map { taste ->
    if (taste.subtitle == null && taste.name.equals(artist, ignoreCase = true)) {
        taste.copy(artworkUrl = artwork)
    } else {
        taste
    }
}

private fun calculateTasteCompatibility(
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
    val artistScore = overlap(myArtists, theirArtists)
    val albumScore = if (myAlbums.isEmpty() || theirAlbums.isEmpty()) artistScore else overlap(myAlbums, theirAlbums)
    return ((artistScore * 0.72f + albumScore * 0.28f) * 100f).toInt().coerceIn(0, 100)
}

private fun overlap(first: Set<String>, second: Set<String>): Float {
    val union = first union second
    return if (union.isEmpty()) 0f else (first intersect second).size.toFloat() / union.size.toFloat()
}

@Composable
private fun audienceLabel(audience: ProfileAudience): String = when (audience) {
    ProfileAudience.EVERYONE -> stringResource(R.string.profile_audience_everyone)
    ProfileAudience.FOLLOWERS -> stringResource(R.string.profile_audience_followers)
    ProfileAudience.NOBODY -> stringResource(R.string.profile_audience_nobody)
}

private fun OrbProfile.displayLabel(): String =
    displayName.takeIf { it.isNotBlank() } ?: username?.let { "@$it" } ?: "Orb"

private val USERNAME_REGEX = Regex("^[a-z0-9._]{3,30}$")
