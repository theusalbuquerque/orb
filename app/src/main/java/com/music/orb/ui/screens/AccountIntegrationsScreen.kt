package com.music.orb.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import com.music.orb.ui.components.orbGlassSurface
import com.music.orb.ui.components.orbOverlayGlassBaseColor
import kotlinx.coroutines.CancellationException
import com.music.orb.data.spotify.SpotifySession
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.R
import com.music.orb.data.model.Account
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.social.SocialRepository
import com.music.orb.ui.flavor.OrbFlavorUi

@Composable
fun AccountIntegrationsScreen(
    signedIn: Boolean,
    account: Account?,
    youtubeAccount: Account?,
    youtubeNeedsSetup: Boolean,
    onSignIn: () -> Unit,
    onOpenOrbProfile: () -> Unit = {},
    onManageYouTube: () -> Unit,
    onOpenLastfmLogin: () -> Unit = {},
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val context = LocalContext.current
    var showSpotifyLogin by remember { mutableStateOf(false) }
    val spotifyConnected by SpotifySession.connected.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn, account?.email) {
        try { SpotifySession.init(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A Spotify setup failure must not close Accounts. */ }
        if (!signedIn) showSpotifyLogin = false
    }
    val useYouTubeMusicLibrary by AppSettings.useYouTubeMusicLibrary.collectAsStateWithLifecycle()
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlaying by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val lastfmUser by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmConnected = lastfmKey.isNotBlank()
    val orbProfile by SocialRepository.myProfileState.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn) {
        if (signedIn) {
            runCatching { SocialRepository.myProfile() }
        } else {
            SocialRepository.clearMyProfileState()
        }
    }

    val youtubeHandle = youtubeAccount?.handle
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("@")) it else "@$it" }
        ?: youtubeAccount?.email
            ?.trim()
            ?.takeIf { it.startsWith("@") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding)
            .padding(top = 18.dp),
    ) {
        val activeOrbProfile = orbProfile?.takeIf { it.id == SocialRepository.currentUserId() }
        val profileAccount = account?.copy(
            name = activeOrbProfile?.displayName?.takeIf { it.isNotBlank() } ?: account.name,
            thumbnailUrl = activeOrbProfile?.avatarIconUrl?.takeIf { it.isNotBlank() }
                ?: activeOrbProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                ?: account.thumbnailUrl,
        )
        AccountCard(
            signedIn = signedIn,
            account = profileAccount,
            onSignIn = onSignIn,
            onSignedInClick = onOpenOrbProfile,
        )

        if (signedIn) {
            SettingsGroup(header = stringResource(R.string.account_connections_header)) {
                SettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    iconContent = {
                        Image(
                            painter = painterResource(R.drawable.youtube_music_account_logo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    },
                    title = youtubeHandle ?: youtubeAccount?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.account_youtube_music),
                    subtitle = when {
                        youtubeNeedsSetup && youtubeAccount != null ->
                            stringResource(R.string.account_youtube_library_partial)
                        youtubeNeedsSetup || youtubeAccount == null ->
                            stringResource(R.string.account_youtube_finish_setup)
                        else -> null
                    },
                    iconHasOwnBackground = true,
                    onClick = onManageYouTube,
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.LibraryMusic,
                    title = stringResource(R.string.account_library_sync_title),
                    subtitle = stringResource(
                        if (useYouTubeMusicLibrary) {
                            R.string.settings_youtube_music_library_linked
                        } else {
                            R.string.settings_youtube_music_library_unlinked
                        },
                    ),
                    trailing = {
                        Switch(
                            checked = useYouTubeMusicLibrary,
                            onCheckedChange = null,
                        )
                    },
                    onClick = { AppSettings.setUseYouTubeMusicLibrary(!useYouTubeMusicLibrary) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    iconContent = {
                        Image(
                            painter = painterResource(R.drawable.spotify_account_logo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        )
                    },
                    iconHasOwnBackground = true,
                    title = stringResource(if (spotifyConnected) R.string.spotify_connected else R.string.spotify_login),
                    subtitle = if (spotifyConnected) null else stringResource(R.string.spotify_library_sync_note),
                    trailing = {
                        if (spotifyConnected) {
                            TextButton(onClick = { SpotifySession.disconnect() }) {
                                Text(stringResource(R.string.spotify_disconnect))
                            }
                        } else {
                            TextButton(onClick = { showSpotifyLogin = true }) {
                                Text(stringResource(R.string.spotify_login_action))
                            }
                        }
                    },
                    onClick = { showSpotifyLogin = true },
                )
                RowDivider()
                LastfmIntegrationCard(
                    connected = lastfmConnected,
                    username = lastfmUser,
                    enabled = lastfmEnabled,
                    scrobbleEnabled = lastfmScrobbleEnabled,
                    nowPlayingEnabled = lastfmNowPlaying,
                    onConnect = onOpenLastfmLogin,
                    onDisconnect = {
                        AppSettings.setLastfmEnabled(false)
                        AppSettings.setLastfmSessionKey("")
                        AppSettings.setLastfmUsername("")
                        AppSettings.setLastfmScrobbleEnabled(false)
                        AppSettings.setLastfmNowPlaying(false)
                    },
                    onEnabledChange = AppSettings::setLastfmEnabled,
                    onScrobbleChange = AppSettings::setLastfmScrobbleEnabled,
                    onNowPlayingChange = AppSettings::setLastfmNowPlaying,
                )
            }
        }

        if (signedIn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 128.dp, max = 184.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .orbGlassSurface(
                            pageColor = orbOverlayGlassBaseColor(),
                            cornerRadius = 18.dp,
                            manageDialogBackdrop = false,
                        )
                        .clickable(onClick = onSignOut)
                        .padding(horizontal = 30.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.account_sign_out),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        OrbAppFooter(account = account)
    }

    if (signedIn && showSpotifyLogin) {
        SpotifyLoginDialog(onClose = { showSpotifyLogin = false })
    }

}


/** Last.fm connection and preferences share one glass card, with an expandable header. */
@Composable
private fun LastfmIntegrationCard(
    connected: Boolean,
    username: String,
    enabled: Boolean,
    scrobbleEnabled: Boolean,
    nowPlayingEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onScrobbleChange: (Boolean) -> Unit,
    onNowPlayingChange: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "LastfmChevronRotation",
    )
    val cornerRadius = if (OrbFlavorUi.expressive) 18.dp else 14.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (OrbFlavorUi.expressive) {
                    Modifier.orbGlassSurface(
                        pageColor = orbOverlayGlassBaseColor(),
                        cornerRadius = cornerRadius,
                        manageDialogBackdrop = false,
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (connected) expanded = !expanded else onConnect() }
                .heightIn(min = if (OrbFlavorUi.expressive) 68.dp else 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (OrbFlavorUi.expressive) 42.dp else 34.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.lastfm_account_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Last.fm",
                    style = if (OrbFlavorUi.expressive) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (connected) stringResource(R.string.lastfm_subtitle_signed_in, username)
                        else stringResource(R.string.lastfm_subtitle_tap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (connected) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).rotate(if (connected) arrowRotation else 0f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(150)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (connected) {
                    LastfmToggleSetting(
                        title = stringResource(R.string.lastfm_enable),
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                    )
                    LastfmOptionDivider()
                    LastfmToggleSetting(
                        title = stringResource(R.string.scrobble_tracks_title),
                        checked = scrobbleEnabled,
                        enabled = enabled,
                        onCheckedChange = onScrobbleChange,
                    )
                    LastfmOptionDivider()
                    LastfmToggleSetting(
                        title = stringResource(R.string.now_playing_title),
                        checked = nowPlayingEnabled,
                        enabled = enabled,
                        onCheckedChange = onNowPlayingChange,
                    )
                }
                if (connected) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        TextButton(onClick = { expanded = false; onDisconnect() }) {
                            Text(stringResource(R.string.lastfm_disconnect))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastfmOptionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 18.dp, end = 18.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

@Composable
private fun LastfmToggleSetting(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 18.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}
