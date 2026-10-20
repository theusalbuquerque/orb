package com.music.orb.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.orb.R
import com.music.orb.data.settings.OrbAppLanguage
import com.music.orb.ui.components.OrbGlassSheetSurface
import com.music.orb.ui.components.OrbModalBottomSheet
import com.music.orb.ui.components.orbOverlayGlassBaseColor

private fun Context.findOrbActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findOrbActivity()
    else -> null
}

/** The first page selects the language, the second page selects its region. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbAppLanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selectedLanguageCode by remember { mutableStateOf<String?>(null) }
    val language = OrbAppLanguage.languages.firstOrNull { it.code == selectedLanguageCode }
    val current = remember(context) { OrbAppLanguage.choice(context) }
    val title = if (language == null) stringResource(R.string.orb_language_choose_language)
        else stringResource(R.string.orb_language_choose_region)

    val choose: (String) -> Unit = { tag ->
        if (tag != current) {
            OrbAppLanguage.setChoice(context, tag)
            // Recreate to refresh every Compose stringResource, resources in navigation
            // destinations and the Activity's locale-dependent formatters.
            context.findOrbActivity()?.recreate()
        }
        onDismiss()
    }

    // The sheet itself owns Back navigation when choosing a region.
    BackHandler(enabled = language != null) { selectedLanguageCode = null }
    OrbModalBottomSheet(
        floatingTitle = title,
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        navigationBarInsetInContent = true,
        dragHandle = null,
    ) {
        OrbGlassSheetSurface(
            pageColor = orbOverlayGlassBaseColor(),
            modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .78f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                if (language == null) {
                    Text(
                        text = stringResource(R.string.orb_language_settings_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    LanguageChoiceRow(
                        title = stringResource(R.string.orb_language_auto_title),
                        description = stringResource(
                            R.string.orb_language_auto_description,
                            OrbAppLanguage.labelFor(OrbAppLanguage.deviceTag()),
                        ),
                        selected = current.isEmpty(),
                        onClick = { choose("") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OrbAppLanguage.languages.forEach { candidate ->
                        LanguageChoiceRow(
                            title = candidate.label,
                            selected = candidate.regions.any { it.tag == current },
                            onClick = { selectedLanguageCode = candidate.code },
                            showChevron = true,
                        )
                    }
                } else {
                    LanguageChoiceRow(
                        title = stringResource(R.string.orb_language_back),
                        onClick = { selectedLanguageCode = null },
                        leading = {
                            Icon(Icons.Rounded.ArrowBack, null, Modifier.size(20.dp))
                        },
                    )
                    Text(
                        text = stringResource(R.string.orb_language_region_description, language.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    language.regions.forEach { region ->
                        LanguageChoiceRow(
                            title = region.label,
                            selected = current == region.tag,
                            onClick = { choose(region.tag) },
                            leading = {
                                Text(
                                    text = OrbAppLanguage.flagForRegion(region.tag),
                                    fontSize = 22.sp,
                                    modifier = Modifier.width(26.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageChoiceRow(
    title: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    description: String? = null,
    showChevron: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            leading()
        } else {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
        else if (showChevron) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
