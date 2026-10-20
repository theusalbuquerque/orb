package com.music.orb.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import com.music.orb.ui.components.OrbConfirmationDialog as AlertDialog
import androidx.compose.material3.Text
import com.music.orb.ui.components.OrbGlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Acknowledgement dialog whose only exit unlocks after the safety delay. */
@Composable
fun TimedExperimentalNotice(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirmed: () -> Unit,
    unlockAfterMs: Long = 6_000L,
) {
    var unlocked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(unlockAfterMs)
        unlocked = true
    }

    AlertDialog(
        // Back and outside taps remain blocked. Once unlocked, the visible
        // confirmation action is the deliberate way out of the notice.
        onDismissRequest = {},
        modifier = Modifier.orbGlassSurface(orbOverlayGlassBaseColor(), cornerRadius = 28.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = null,
        confirmButton = {
            AnimatedVisibility(visible = unlocked) {
                TextButton(onClick = onConfirmed) {
                    Text(confirmLabel)
                }
            }
        },
    )
}
