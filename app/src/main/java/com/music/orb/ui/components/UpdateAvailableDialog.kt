package com.music.orb.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.update.ReleaseNotesTranslation
import com.music.orb.data.update.SoftwareUpdateDialogMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

private val UPDATE_DIALOG_CORNER = 28.dp
private val UPDATE_ACTION_HEIGHT = 58.dp
internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.40f)

private const val ORB_README_BANNER_URL =
    "https://raw.githubusercontent.com/theusalbuquerque/orb/main/app/src/main/assets/orb_banner.png"

/**
 * Large software-update card inspired by the system-style BitChord surface:
 * status + progress at the top, a proper What's New area in the middle and a
 * full-width action row at the bottom. The same composable morphs from
 * available -> downloading -> ready, so an in-app download does not swap to a
 * second unrelated popup midway through the update.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun UpdateAvailableDialog(
    version: String,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    mode: SoftwareUpdateDialogMode = SoftwareUpdateDialogMode.AVAILABLE,
    progress: Float? = null,
    releaseNotes: String? = null,
    heroImageUrl: String? = null,
    title: String? = null,
    message: String? = null,
    primaryActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(UPDATE_DIALOG_CORNER)
    val status = message ?: when (mode) {
        SoftwareUpdateDialogMode.AVAILABLE -> stringResource(R.string.software_update_available_status, version)
        SoftwareUpdateDialogMode.DOWNLOADING -> stringResource(R.string.software_update_downloading_status, version)
        SoftwareUpdateDialogMode.READY -> stringResource(R.string.software_update_ready_status, version)
    }
    val configuration = LocalConfiguration.current
    val targetLanguageTag = configuration.locales[0].toLanguageTag()
    val targetLanguage = configuration.locales[0].language
    val originalNotes = remember(releaseNotes) { cleanReleaseNotes(releaseNotes.orEmpty()) }
    var translatedNotes by remember(originalNotes, targetLanguageTag) { mutableStateOf<String?>(null) }
    LaunchedEffect(originalNotes, targetLanguageTag) {
        translatedNotes = if (originalNotes.isBlank() || targetLanguage.equals("en", ignoreCase = true)) {
            null
        } else {
            ReleaseNotesTranslation.translate(originalNotes, targetLanguageTag)
        }
    }
    // Translation is best-effort. If it is unavailable, the complete original
    // changelog is shown rather than replacing it with a generic summary.
    val notes = translatedNotes ?: originalNotes

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                enabled = mode != SoftwareUpdateDialogMode.DOWNLOADING,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp, vertical = 30.dp)
                .fillMaxWidth()
                .widthIn(max = 430.dp)
                .heightIn(max = 760.dp)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
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
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title ?: stringResource(R.string.software_update_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 29.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.W700,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = status,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.W400,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                if (mode == SoftwareUpdateDialogMode.DOWNLOADING || mode == SoftwareUpdateDialogMode.READY) {
                    Spacer(Modifier.height(20.dp))
                    if (mode == SoftwareUpdateDialogMode.READY) {
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                            drawStopIndicator = {},
                        )
                    } else if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                            drawStopIndicator = {},
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                        )
                    }
                }

                Box(
                    Modifier
                        .padding(top = 22.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                )

                Text(
                    text = stringResource(R.string.software_update_whats_new),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 21.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateHero(heroImageUrl = heroImageUrl, version = version)

                Text(
                    text = notes.ifBlank { stringResource(R.string.software_update_no_notes) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            UpdateRule()
            when (mode) {
                SoftwareUpdateDialogMode.AVAILABLE -> {
                    UpdateAction(
                        label = primaryActionLabel ?: stringResource(R.string.download_now),
                        emphasised = true,
                        onClick = onUpdate,
                    )
                    UpdateRule()
                    UpdateAction(
                        label = secondaryActionLabel ?: stringResource(R.string.remind_me_later),
                        emphasised = false,
                        onClick = onSecondaryAction ?: onDismiss,
                    )
                }
                SoftwareUpdateDialogMode.DOWNLOADING -> {
                    UpdateAction(
                        label = secondaryActionLabel ?: stringResource(R.string.software_update_cancel),
                        emphasised = false,
                        onClick = onSecondaryAction ?: onDismiss,
                    )
                }
                SoftwareUpdateDialogMode.READY -> {
                    UpdateAction(
                        label = primaryActionLabel ?: stringResource(R.string.beta_updates_install_now),
                        emphasised = true,
                        onClick = onUpdate,
                    )
                    UpdateRule()
                    UpdateAction(
                        label = secondaryActionLabel ?: stringResource(R.string.remind_me_later),
                        emphasised = false,
                        onClick = onSecondaryAction ?: onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateHero(heroImageUrl: String?, version: String) {
    val heroShape = RoundedCornerShape(4.dp)
    val resolvedHeroUrl = heroImageUrl?.takeIf { it.isNotBlank() } ?: ORB_README_BANNER_URL
    if (resolvedHeroUrl.isNotBlank()) {
        AsyncImage(
            model = resolvedHeroUrl,
            contentDescription = stringResource(R.string.software_update_whats_new),
            contentScale = ContentScale.Inside,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(heroShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(heroShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                )
                Column {
                    Text(
                        text = "Orb",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.W700),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.software_update_version_label, version),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateAction(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(UPDATE_ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400,
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
private fun UpdateRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
}

private fun cleanReleaseNotes(raw: String): String {
    if (raw.isBlank()) return ""
    val imageLine = Regex("""!\[[^]]*]\([^)]+\)""")
    val link = Regex("""\[([^]]+)]\([^)]+\)""")
    val html = Regex("""<[^>]+>""")
    return raw.lineSequence()
        .map { line ->
            line
                .replace(imageLine, "")
                .replace(link, "$1")
                .replace(html, "")
                .replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")
                .replace(Regex("""^\s*[-*+]\s+"""), "• ")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trimEnd()
        }
        .toList()
        .fold(mutableListOf<String>()) { acc, line ->
            if (line.isNotBlank() || acc.lastOrNull()?.isNotBlank() == true) acc += line
            acc
        }
        .joinToString("\n")
        .trim()
}
