package com.music.orb.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton as MaterialFilledIconButton
import androidx.compose.material3.FilledTonalButton as MaterialFilledTonalButton
import androidx.compose.material3.FilledTonalIconButton as MaterialFilledTonalIconButton
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton as MaterialFloatingActionButton
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip as MaterialInputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared button family for Orb Expressive Glass.
 *
 * The geometry still comes from each control, but the material no longer changes
 * from screen to screen: neutral frosted glass first, contextual colour second.
 * Selection/state is expressed by content/accent rather than by replacing the
 * whole control with an opaque Material container.
 */
@Composable
private fun expressiveGlassControlModifier(
    modifier: Modifier,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    pageColor: Color,
    cornerRadius: Dp?,
    selected: Boolean = false,
    transparentLikeNowPlaying: Boolean = false,
    transparentShape: Shape = RoundedCornerShape(24.dp),
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = when {
        !enabled -> 1f
        pressed -> 0.955f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "orbExpressiveGlassControlPress",
    )
    return modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.54f
        }
        .then(
            if (transparentLikeNowPlaying) {
                Modifier
                    .clip(transparentShape)
                    .orbNowPlayingStyleGlass(bestForegroundForArtworkSurface(pageColor))
            } else {
                Modifier.orbGlassSurface(
                    pageColor = pageColor,
                    cornerRadius = cornerRadius,
                    drawRim = selected,
                    overlayStrength = selected,
                    observeTapFeedback = false,
                    manageDialogBackdrop = false,
                )
            },
        )
}

@Composable
fun OrbGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: Any? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable RowScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, 24.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.54f),
        ),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: Any? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    pageColor: Color = orbOverlayGlassBaseColor(),
    transparentLikeNowPlaying: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = if (transparentLikeNowPlaying) {
        bestForegroundForArtworkSurface(pageColor)
    } else orbExpressiveGlassContentColor(pageColor)
    MaterialFilledTonalButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(
            modifier = modifier,
            interactionSource = source,
            enabled = enabled,
            pageColor = pageColor,
            cornerRadius = 24.dp,
            transparentLikeNowPlaying = transparentLikeNowPlaying,
            transparentShape = shape,
        ),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.54f),
        ),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: Any? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable RowScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, 24.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.54f),
        ),
        border = null,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    colors: Any? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable RowScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val role = LocalOrbDialogActionRole.current
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    when (role) {
        OrbDialogActionRole.DISMISS -> {
            // Cancel is text only, without an expressive glass container.
            MaterialTextButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = scheme.onSurfaceVariant,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.42f),
                ),
                contentPadding = contentPadding,
                content = content,
            )
        }
        OrbDialogActionRole.CONFIRM -> {
            // Only the primary confirmation is filled.
            MaterialButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
                ),
                contentPadding = contentPadding,
                content = content,
            )
        }
        OrbDialogActionRole.DEFAULT -> {
            val source = remember { MutableInteractionSource() }
            val contentColor = orbExpressiveGlassContentColor(pageColor)
            MaterialTextButton(
                onClick = onClick,
                modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, 20.dp),
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = contentColor,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = contentColor.copy(alpha = 0.54f),
                ),
                contentPadding = contentPadding,
                interactionSource = source,
                content = content,
            )
        }
    }
}

@Composable
fun OrbGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: Any? = null,
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialIconButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, null),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.54f),
        ),
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    colors: Any? = null,
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialFilledIconButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, null),
        enabled = enabled,
        shape = shape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.54f),
        ),
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    colors: Any? = null,
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialFilledTonalIconButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, null),
        enabled = enabled,
        shape = shape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.54f),
        ),
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: Color = Color.Transparent,
    contentColor: Color = Color.Unspecified,
    pageColor: Color = orbOverlayGlassBaseColor(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = containerColor
    val source = remember { MutableInteractionSource() }
    @Suppress("UNUSED_VARIABLE") val preserveRequestedContentColor = contentColor
    val resolvedContent = orbExpressiveGlassContentColor(pageColor)
    MaterialFloatingActionButton(
        onClick = onClick,
        modifier = expressiveGlassControlModifier(modifier, source, true, pageColor, 22.dp),
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = resolvedContent,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun OrbGlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(18.dp),
    colors: Any? = null,
    pageColor: Color = orbOverlayGlassBaseColor(),
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, 18.dp, selected),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = contentColor,
            iconColor = contentColor,
            selectedContainerColor = Color.Transparent,
            selectedLabelColor = contentColor,
            selectedLeadingIconColor = contentColor,
            selectedTrailingIconColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = contentColor.copy(alpha = 0.54f),
        ),
        border = null,
        interactionSource = source,
    )
}

@Composable
fun OrbGlassInputChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    avatar: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(18.dp),
    colors: Any? = null,
    pageColor: Color = orbOverlayGlassBaseColor(),
) {
    @Suppress("UNUSED_VARIABLE") val preserveSourceCompatibility = colors
    val source = remember { MutableInteractionSource() }
    val contentColor = orbExpressiveGlassContentColor(pageColor)
    MaterialInputChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = expressiveGlassControlModifier(modifier, source, enabled, pageColor, 18.dp, selected),
        enabled = enabled,
        leadingIcon = leadingIcon,
        avatar = avatar,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = InputChipDefaults.inputChipColors(
            containerColor = Color.Transparent,
            labelColor = contentColor,
            leadingIconColor = contentColor,
            trailingIconColor = contentColor,
            selectedContainerColor = Color.Transparent,
            selectedLabelColor = contentColor,
            selectedLeadingIconColor = contentColor,
            selectedTrailingIconColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = contentColor.copy(alpha = 0.54f),
        ),
        border = null,
        interactionSource = source,
    )
}
