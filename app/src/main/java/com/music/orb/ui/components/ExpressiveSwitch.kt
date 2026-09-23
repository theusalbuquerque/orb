package com.music.orb.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Orb's app-wide Material 3 Expressive switch.
 *
 * The reference motion deliberately separates mechanics from paint: the thumb
 * reaches the other side on a soft spring while the track colour settles a
 * fraction later. That makes a settings toggle feel physical instead of
 * snapping between the two Material states.
 *
 * [colors] is intentionally accepted for source compatibility with older
 * Material3 Switch call-sites. Orb owns the expressive palette here.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: Any? = null,
) {
    val position by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.64f,
            stiffness = 360f,
            visibilityThreshold = 0.001f,
        ),
        label = "orb-switch-position",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (checked) 1.04f else 0.96f,
        animationSpec = spring(
            dampingRatio = 0.58f,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = 0.001f,
        ),
        label = "orb-switch-thumb-scale",
    )

    val enabledTrack = MaterialTheme.colorScheme.primary
    val disabledTrack = MaterialTheme.colorScheme.surfaceContainerHighest
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> enabledTrack.copy(alpha = 0.44f)
            !enabled -> disabledTrack.copy(alpha = 0.54f)
            checked -> enabledTrack
            else -> disabledTrack
        },
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "orb-switch-track",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            checked -> Color.Transparent
            !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow),
        label = "orb-switch-border",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            checked -> MaterialTheme.colorScheme.onPrimary
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow),
        label = "orb-switch-thumb",
    )

    val interactive = onCheckedChange != null
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (interactive) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onCheckedChange?.invoke(it) },
                    )
                } else {
                    Modifier.semantics { role = Role.Switch }
                },
            )
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 4dp edge inset + 24dp thumb gives a 20dp travel distance.
        Box(
            modifier = Modifier
                .offset(x = 4.dp + (20.dp * position.coerceIn(-0.08f, 1.08f)))
                .size(24.dp)
                .graphicsLayer {
                    val scale = thumbScale.coerceIn(0.90f, 1.08f)
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}
