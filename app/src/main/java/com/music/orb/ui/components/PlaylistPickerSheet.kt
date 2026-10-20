package com.music.orb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import com.music.orb.ui.components.OrbGlassButton as Button
import com.music.orb.ui.components.OrbGlassFilledTonalButton as FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.music.orb.ui.components.OrbGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.model.PlaylistPrivacy
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.UserPlaylist
import com.music.orb.data.model.artworkAt
import com.music.orb.ui.icons.OrbIcons
import com.music.orb.ui.flavor.OrbFlavorUi

/**
 * Where a track goes: one of the account's playlists, or a new one.
 *
 * Two panels in one sheet rather than a sheet that opens a dialog. Creating a
 * playlist here is nearly always in service of adding the track that opened
 * this — so the form comes back to the same place, and the create request
 * carries the track with it instead of leaving a new empty playlist behind
 * for the user to add to a second time.
 *
 * [song] is null when the flow started from the Library tab rather than from a
 * track, which is the one case where the header has no track to draw and
 * "New playlist" is the whole point of the sheet.
 */
@Composable
fun PlaylistPickerSheet(
    playlists: List<UserPlaylist>,
    loading: Boolean,
    onPick: (UserPlaylist) -> Unit,
    onCreate: (String, PlaylistPrivacy) -> Unit,
    modifier: Modifier = Modifier,
    song: Song? = null,
    startCreating: Boolean = false,
    showTrackHeader: Boolean = true,
    // The inline Action Button has its own artwork-derived sheet color; standalone
    // pickers inherit the surrounding Orb glass sheet color.
    sheetPageColor: androidx.compose.ui.graphics.Color? = null,
) {
    var creating by remember { mutableStateOf(startCreating) }
    val tactileContext = LocalContext.current
    val pickerPageColor = sheetPageColor ?: orbOverlayGlassBaseColor()
    val glassForegrounds = artworkSurfaceForegrounds(orbExpressiveGlassTone(pickerPageColor))

    // Do not inherit the floating artwork hero's foreground: that foreground is
    // computed for the dimmed album art OUTSIDE the sheet. On light glass it can
    // be dark while playlist rows are dark, making the row labels unreadable.
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = glassForegrounds.primary,
            onBackground = glassForegrounds.primary,
            onSurface = glassForegrounds.primary,
            onSurfaceVariant = glassForegrounds.secondary,
        ),
    ) {
    if (creating) {
        NewPlaylistForm(
            // Nowhere to go back to when the sheet opened straight onto the
            // form; the sheet's own dismiss is the way out.
            onBack = if (startCreating) null else ({ creating = false }),
            onCreate = onCreate,
            modifier = modifier,
        )
        return@MaterialTheme
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // The track context/hero already says what this flow is for. Avoid repeating
        // “Add to playlist” as a second title; Library's standalone picker keeps its
        // “Your playlists” heading because it has no track/action sheet behind it.
        if (song == null) {
            Text(
                text = stringResource(R.string.playlist_picker_your_playlists_heading),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
        if (song != null && showTrackHeader) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()) { SheetTrackHeader(song) }
            Spacer(Modifier.height(16.dp))
        }
        FilledTonalButton(
            onClick = {
                OrbHaptics.perform(tactileContext, OrbHaptics.Kind.ACTION)
                creating = true
            },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            pageColor = pickerPageColor,
        ) {
            Icon(OrbIcons.Plus, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.playlist_picker_new_playlist), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        when {
            // Only while there is nothing to show: re-fetching under a list
            // that is already up would replace it with a spinner for no gain.
            loading && playlists.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(26.dp),
                )
            }

            playlists.isEmpty() -> Text(
                text = stringResource(R.string.playlist_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            )

            else -> {
                // Capped so a long list can't push the sheet past the screen;
                // it scrolls inside the sheet instead.
                val playlistScroll = rememberLazyListState()
                LazyColumn(
                    Modifier.heightIn(max = 360.dp),
                    state = playlistScroll,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(playlists, key = { _, item -> item.playlistId }) { index, playlist ->
                        Box(Modifier.orbSheetProgressiveLazyItem(playlistScroll, index)) {
                            PlaylistRow(
                                playlist = playlist,
                                sheetPageColor = pickerPageColor,
                                onClick = { onPick(playlist) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun PlaylistRow(
    playlist: UserPlaylist,
    sheetPageColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val rowForegrounds = artworkSurfaceForegrounds(orbExpressiveGlassTone(sheetPageColor))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .orbGlassSurface(
                pageColor = sheetPageColor,
                cornerRadius = 22.dp,
                overlayOpacity = 0.68f,
                contentColorHint = rowForegrounds.primary,
                // The sheet already manages its own dialog blur and touch feedback.
                manageDialogBackdrop = false,
                observeTapFeedback = false,
            )
            .clickable {
                OrbHaptics.perform(context, OrbHaptics.Kind.ACTION)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = playlist.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .thumbnailBorder(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                color = rowForegrounds.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.subtitle.isNotBlank()) {
                Text(
                    text = playlist.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = rowForegrounds.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Name and visibility, and nothing else. YouTube also takes a description,
 * which nobody fills in from a phone at the moment of saving a song.
 */
@Composable
private fun NewPlaylistForm(
    onBack: (() -> Unit)?,
    onCreate: (String, PlaylistPrivacy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val tactileContext = LocalContext.current

    // The form exists to be typed into; opening it with the keyboard already
    // up saves the tap that would otherwise always follow.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (name.isNotBlank()) {
            focusManager.clearFocus()
            onCreate(name, privacy)
        }
    }

    // As above: the keyboard is up from the moment this opens, and "Create
    // playlist" is below the fold without this.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 8.dp else 22.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                IconButton(
                    onClick = {
                        OrbHaptics.perform(tactileContext, OrbHaptics.Kind.NAVIGATION)
                        it()
                    },
                    modifier = Modifier.then(
                        if (OrbFlavorUi.expressive) Modifier.size(44.dp) else Modifier
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.playlist_picker_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.playlist_picker_new_playlist_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.playlist_picker_saved_to_youtube),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) {
                    Text(
                        text = stringResource(R.string.playlist_picker_name_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (name.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            OrbHaptics.perform(tactileContext, OrbHaptics.Kind.ACTION)
                            name = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.playlist_picker_clear_name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        SheetHeading(stringResource(R.string.playlist_picker_visibility_heading))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaylistPrivacy.entries.forEach { option ->
                PrivacyPill(
                    icon = option.icon,
                    label = option.localizedLabel(),
                    selected = option == privacy,
                    onClick = {
                        OrbHaptics.perform(tactileContext, OrbHaptics.Kind.TOGGLE)
                        privacy = option
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                OrbHaptics.perform(tactileContext, OrbHaptics.Kind.ACTION)
                submit()
            },
            enabled = name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
        ) {
            Text(stringResource(R.string.playlist_picker_create))
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Long-press menu for one of the account's own playlists, on the Library tab —
 * the other half of being able to create them.
 *
 * Deleting asks a second time, in place. A playlist is the only thing in this
 * app whose loss can't be undone by tapping the same row again, and a
 * mis-tapped row in a shelf is exactly how it would happen.
 */
@Composable
fun PlaylistActionsSheet(
    playlist: UserPlaylist,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (renaming) {
        RenamePlaylistForm(
            playlist = playlist,
            onBack = { renaming = false },
            onRename = onRename,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxWidth()) {
        ProgressiveActionSheetItem(
            index = 0,
            itemKey = "playlist:${playlist.browseId}:header",
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = playlist.thumbnailUrl.artworkAt(ROW_ART_PX),
                        contentDescription = null,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .thumbnailBorder(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = playlist.subtitle.takeIf { it.isNotBlank() } ?: stringResource(R.string.orb_l10n_playlist_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            }
        }

        ProgressiveActionSheetItem(
            index = 1,
            itemKey = "playlist:${playlist.browseId}:open",
        ) {
            ActionRow(Icons.Rounded.PlayArrow, stringResource(R.string.orb_l10n_playlist_open), onClick = onOpen)
        }
        ProgressiveActionSheetItem(
            index = 2,
            itemKey = "playlist:${playlist.browseId}:rename",
        ) {
            ActionRow(Icons.Rounded.Edit, stringResource(R.string.orb_l10n_playlist_rename_action)) { renaming = true }
        }
        ProgressiveActionSheetItem(
            index = 3,
            itemKey = "playlist:${playlist.browseId}:delete",
        ) {
            if (confirmingDelete) {
                ActionRow(
                    icon = Icons.Rounded.DeleteForever,
                    label = stringResource(R.string.orb_l10n_playlist_delete_confirm, playlist.title),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            } else {
                ActionRow(Icons.Rounded.Delete, stringResource(R.string.orb_l10n_playlist_delete)) { confirmingDelete = true }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RenamePlaylistForm(
    playlist: UserPlaylist,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(playlist.title) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (name.isNotBlank()) {
            focusManager.clearFocus()
            onRename(name)
        }
    }

    // The form opens with the keyboard already up, which on a bottom sheet
    // would otherwise sit over the button the form exists to reach.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.then(
                    if (OrbFlavorUi.expressive) Modifier.size(44.dp) else Modifier
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.playlist_picker_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.orb_l10n_playlist_rename_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }
        Button(
            onClick = submit,
            enabled = name.isNotBlank() && name != playlist.title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
        ) {
            Text(stringResource(R.string.orb_l10n_playlist_save_name))
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The glyph that says what a visibility actually means — a padlock, a shared
 * link, a globe. Three words that all sound like degrees of the same thing
 * read much faster as three different shapes.
 */
@Composable
private fun PlaylistPrivacy.localizedLabel(): String = when (this) {
    PlaylistPrivacy.PRIVATE -> stringResource(R.string.playlist_picker_privacy_private)
    PlaylistPrivacy.UNLISTED -> stringResource(R.string.playlist_picker_privacy_unlisted)
    PlaylistPrivacy.PUBLIC -> stringResource(R.string.playlist_picker_privacy_public)
}

private val PlaylistPrivacy.icon: ImageVector
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> Icons.Rounded.Lock
        PlaylistPrivacy.UNLISTED -> Icons.Rounded.Link
        PlaylistPrivacy.PUBLIC -> Icons.Rounded.Public
    }

/** The search filters' pill, carrying an icon ahead of its label. */
@Composable
private fun PrivacyPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}
