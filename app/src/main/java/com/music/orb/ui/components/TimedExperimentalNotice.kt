package com.music.orb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
