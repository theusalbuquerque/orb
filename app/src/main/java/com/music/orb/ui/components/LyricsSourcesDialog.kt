package com.music.orb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.R
import com.music.orb.data.lyrics.LyricsSource
import com.music.orb.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** Selects every lyrics provider Orb is allowed to contact. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LyricsSourcesDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val selected by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    val paxSenixApiKey by AppSettings.paxSenixApiKey.collectAsStateWithLifecycle()
    var showPaxSenixKeyDialog by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) Modifier.background(MaterialTheme.colorScheme.surface)
                    else Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                    ),
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_sources_dialog_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.lyrics_sources_dialog_description),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            Box(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    LyricsSource.entries
                        .filterNot { it == LyricsSource.YOUTUBE_MUSIC }
                        .forEach { source ->
                        AlertRule()
                        val checked = source in selected
                        SourceRow(
                            source = source,
                            checked = checked,
                            enabled = !checked || selected.size > 1,
                            onToggle = {
                                AppSettings.setLyricsSources(
                                    if (checked) selected - source else selected + source,
                                )
                            },
                        )
                    }
                }
            }

            AlertRule()
            ToggleActionRow(
                title = stringResource(R.string.lyrics_prioritize_syllable),
                detail = stringResource(R.string.lyrics_prioritize_syllable_description),
                checked = prioritizeSyllableSync,
                onClick = { AppSettings.setPrioritizeSyllableSync(!prioritizeSyllableSync) },
            )
            AlertRule()
            AlertAction(
                label = stringResource(
                    if (paxSenixApiKey.isBlank()) R.string.lyrics_paxsenix_api_key
                    else R.string.lyrics_paxsenix_api_key_configured,
                ),
                emphasised = false,
                onClick = { showPaxSenixKeyDialog = true },
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.lyrics_sources_dialog_done),
                emphasised = true,
                onClick = onDismiss,
            )
        }
    }

    if (showPaxSenixKeyDialog) {
        var input by remember(paxSenixApiKey) { mutableStateOf(paxSenixApiKey) }
        AlertDialog(
            onDismissRequest = { showPaxSenixKeyDialog = false },
            title = { Text(stringResource(R.string.lyrics_paxsenix_api_key)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setPaxSenixApiKey(input)
                    showPaxSenixKeyDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPaxSenixKeyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ToggleActionRow(
    title: String,
    detail: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interaction,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}


@Composable
private fun sourceDetail(source: LyricsSource): String = when (source) {
    LyricsSource.BETTER_LYRICS -> stringResource(R.string.lyrics_source_betterlyrics_detail)
    LyricsSource.LYRICS_PLUS -> stringResource(R.string.lyrics_source_lyricsplus_detail)
    LyricsSource.SIMP_MUSIC -> stringResource(R.string.lyrics_source_simpmusic_detail)
    LyricsSource.LRCLIB -> stringResource(R.string.lyrics_source_lrclib_detail)
    LyricsSource.BINI_LYRICS -> stringResource(R.string.lyrics_source_bini_detail)
    LyricsSource.BETTER_LYRICS_PORTATO -> stringResource(R.string.lyrics_source_portato_detail)
    LyricsSource.PAXSENIX -> stringResource(R.string.lyrics_source_paxsenix_detail)
    LyricsSource.PAXSENIX_SPOTIFY -> stringResource(R.string.lyrics_source_paxsenix_spotify_detail)
    LyricsSource.PAXSENIX_MUSIXMATCH -> stringResource(R.string.lyrics_source_paxsenix_musixmatch_detail)
    LyricsSource.UNISON -> stringResource(R.string.lyrics_source_unison_detail)
    LyricsSource.YOUTUBE_TRANSCRIPT -> stringResource(R.string.lyrics_source_youtube_captions_detail)
    LyricsSource.YOUTUBE_MUSIC -> stringResource(R.string.lyrics_source_youtube_music_detail)
    LyricsSource.MEGALOBIZ -> stringResource(R.string.lyrics_source_megalobiz_detail)
    LyricsSource.KUGOU -> stringResource(R.string.lyrics_source_kugou_detail)
    LyricsSource.MUSIXMATCH -> stringResource(R.string.lyrics_source_musixmatch_detail)
    LyricsSource.GENIUS -> stringResource(R.string.lyrics_source_genius_detail)
}

/** One checkable source: name and what it is good for, ticked when enabled. */
@Composable
private fun SourceRow(
    source: LyricsSource,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                else Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            )
            Text(
                text = sourceDetail(source),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.lyrics_sources_enabled_description),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
