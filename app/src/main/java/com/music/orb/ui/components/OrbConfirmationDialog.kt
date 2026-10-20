package com.music.orb.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dialog action policy for the Orb UI.
 * A left-aligned text-only dismiss action and a right-aligned filled confirm action.
 * Provided only within dialog buttons, leaving all other button surfaces unchanged.
 */
internal enum class OrbDialogActionRole { DEFAULT, DISMISS, CONFIRM }
internal val LocalOrbDialogActionRole = staticCompositionLocalOf { OrbDialogActionRole.DEFAULT }

@Composable
fun OrbConfirmationDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 0.dp,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        dismissButton = null,
        confirmButton = {
            OrbDialogWindowEffects()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dismissButton != null) {
                    Box {
                        CompositionLocalProvider(LocalOrbDialogActionRole provides OrbDialogActionRole.DISMISS) {
                            dismissButton()
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    CompositionLocalProvider(LocalOrbDialogActionRole provides OrbDialogActionRole.CONFIRM) {
                        confirmButton()
                    }
                }
            }
        },
    )
}
