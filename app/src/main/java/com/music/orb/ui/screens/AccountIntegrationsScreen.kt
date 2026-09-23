package com.music.orb.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import com.music.orb.ui.components.ExpressiveSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@Composable
fun AccountIntegrationsScreen(
    signedIn: Boolean,
    account: Account?,
    youtubeAccount: Account?,
    youtubeNeedsSetup: Boolean,
    onSignIn: () -> Unit,
    onOpenOrbProfile: () -> Unit = {},
    onManageYouTube: () -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val useYouTubeMusicLibrary by AppSettings.useYouTubeMusicLibrary.collectAsStateWithLifecycle()
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
            .padding(contentPadding),
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
                    title = youtubeHandle ?: stringResource(R.string.account_youtube_music),
                    subtitle = if (youtubeNeedsSetup || youtubeHandle == null) {
                        stringResource(R.string.account_youtube_finish_setup)
                    } else {
                        null
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
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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

}
