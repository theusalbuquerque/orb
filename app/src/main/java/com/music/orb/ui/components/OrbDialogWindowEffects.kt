package com.music.orb.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Normalises popup window dim/blur so Orb dialogs match the same light 10% 
 * backdrop treatment already used by sheets.
 */
@Composable
internal fun OrbDialogWindowEffects(
    dimAmount: Float = 0f,
    blurBehindRadius: Dp = 0.dp,
) {
    val view = LocalView.current
    val density = LocalDensity.current

    DisposableEffect(view, dimAmount, blurBehindRadius, density) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousAttributes = WindowManager.LayoutParams().also { it.copyFrom(window.attributes) }
            val previousDim = window.attributes.dimAmount
            val radiusPx = with(density) { blurBehindRadius.roundToPx() }

            window.setDimAmount(dimAmount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (radiusPx > 0) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        this.blurBehindRadius = radiusPx
                    }
                    runCatching { window.setBackgroundBlurRadius(radiusPx) }
                } else {
                    runCatching { window.setBackgroundBlurRadius(0) }
                }
            }

            onDispose {
                window.attributes = previousAttributes
                window.setDimAmount(previousDim)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { window.setBackgroundBlurRadius(0) }
                }
            }
        }
    }
}
