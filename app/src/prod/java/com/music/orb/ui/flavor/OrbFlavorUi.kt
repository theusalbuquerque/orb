package com.music.orb.ui.flavor

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.music.orb.data.settings.AppFont

/**
 * Stable 1.5.1 shares Orb's graduated Material 3 Expressive interface with
 * Beta. Google Sans Flex remains the only beta-exclusive visual experiment.
 */
object OrbFlavorUi {
    const val expressive = true
    const val fontPicker = false

    /** Google Sans Flex is intentionally unavailable in Stable. */
    fun isGoogleSansFlexActive(selected: AppFont): Boolean = false

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun resolveTypography(
        selected: AppFont,
        defaultTypography: Typography,
        googleSansFlexTypography: Typography,
    ): Typography = defaultTypography

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun resolveFontFamily(
        selected: AppFont,
        fallback: FontFamily,
    ): FontFamily = fallback
}
