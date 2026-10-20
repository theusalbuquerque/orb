package com.music.orb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts modal surfaces, including nested sheets; the host blurs only the content behind them. */
object SheetBackdropRegistry {
    private val count = MutableStateFlow(0)
    val openSheets: StateFlow<Int> = count.asStateFlow()
    @Synchronized internal fun opened() { count.value += 1 }
    @Synchronized internal fun closed() { count.value = (count.value - 1).coerceAtLeast(0) }
}

@Composable
fun RegisterSheetBackdrop() {
    val context = LocalContext.current
    DisposableEffect(context) {
        SheetBackdropRegistry.opened()
        OrbHaptics.overlayEnter(context)
        onDispose {
            OrbHaptics.overlayExit(context)
            SheetBackdropRegistry.closed()
        }
    }
}
