package com.music.orb.ui.flavor

import android.graphics.Typeface
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.music.orb.R
import com.music.orb.data.settings.AppFont

/**
 * Visual feature switches that are resolved by the Android product flavor.
 *
 * Dev is deliberately allowed to move faster than Stable: the expressive UI
 * and experimental typography can be exercised by beta users without changing
 * the production experience.
 */
object OrbFlavorUi {
    const val expressive = true
    const val fontPicker = true

    /** True only when the beta build should use the expressive GSF scale. */
    fun isGoogleSansFlexActive(selected: AppFont): Boolean =
        selected == AppFont.GOOGLE_SANS_FLEX

    /**
     * Builds the Google Sans Flex scale with a real native Typeface per
     * semantic role. A single FontFamily(Typeface) was the reason every role
     * could collapse visually toward the provider's regular face: Compose saw
     * one downloaded Typeface and did not have distinct family entries to pick
     * for W400/W700/W900.
     *
     * Here the app itself derives the requested native weight from the same
     * Google Sans Flex face. The result is deterministic across manufacturers:
     * page titles get a true heavy face, names get medium/heavy faces, while
     * usernames/descriptions keep a regular/light optical colour.
     */
    @Composable
    fun resolveTypography(
        selected: AppFont,
        defaultTypography: Typography,
        googleSansFlexTypography: Typography,
    ): Typography {
        if (selected != AppFont.GOOGLE_SANS_FLEX) return defaultTypography

        val context = LocalContext.current
        var downloadedTypeface by remember(context) { mutableStateOf<Typeface?>(null) }

        LaunchedEffect(context, selected) {
            if (downloadedTypeface == null) {
                runCatching {
                    ResourcesCompat.getFont(
                        context,
                        R.font.google_sans_flex,
                        object : ResourcesCompat.FontCallback() {
                            override fun onFontRetrieved(typeface: Typeface) {
                                downloadedTypeface = typeface
                            }

                            override fun onFontRetrievalFailed(reason: Int) {
                                // Keep the bundled default until Google Play's
                                // provider can serve/cache the beta font.
                            }
                        },
                        null,
                    )
                }
            }
        }

        val baseTypeface = downloadedTypeface ?: return defaultTypography
        return remember(baseTypeface, googleSansFlexTypography) {
            fun nativeTypefaceFor(weight: FontWeight): Typeface =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(baseTypeface, weight.weight.coerceIn(1, 1000), false)
                } else {
                    Typeface.create(
                        baseTypeface,
                        if (weight.weight >= 600) Typeface.BOLD else Typeface.NORMAL,
                    )
                }

            fun TextStyle.withNativeFlexWeight(): TextStyle {
                val requestedWeight = fontWeight ?: FontWeight.Normal
                return copy(fontFamily = FontFamily(nativeTypefaceFor(requestedWeight)))
            }

            Typography(
                displayLarge = googleSansFlexTypography.displayLarge.withNativeFlexWeight(),
                displayMedium = googleSansFlexTypography.displayMedium.withNativeFlexWeight(),
                displaySmall = googleSansFlexTypography.displaySmall.withNativeFlexWeight(),
                headlineLarge = googleSansFlexTypography.headlineLarge.withNativeFlexWeight(),
                headlineMedium = googleSansFlexTypography.headlineMedium.withNativeFlexWeight(),
                headlineSmall = googleSansFlexTypography.headlineSmall.withNativeFlexWeight(),
                titleLarge = googleSansFlexTypography.titleLarge.withNativeFlexWeight(),
                titleMedium = googleSansFlexTypography.titleMedium.withNativeFlexWeight(),
                titleSmall = googleSansFlexTypography.titleSmall.withNativeFlexWeight(),
                bodyLarge = googleSansFlexTypography.bodyLarge.withNativeFlexWeight(),
                bodyMedium = googleSansFlexTypography.bodyMedium.withNativeFlexWeight(),
                bodySmall = googleSansFlexTypography.bodySmall.withNativeFlexWeight(),
                labelLarge = googleSansFlexTypography.labelLarge.withNativeFlexWeight(),
                labelMedium = googleSansFlexTypography.labelMedium.withNativeFlexWeight(),
                labelSmall = googleSansFlexTypography.labelSmall.withNativeFlexWeight(),
            )
        }
    }

    /**
     * Google Sans Flex is fetched through the Google Play font provider only
     * when the beta user selects it. Until the provider answers, Orb keeps its
     * bundled default family so text never disappears or blocks app startup.
     */
    @Composable
    fun resolveFontFamily(
        selected: AppFont,
        fallback: FontFamily,
    ): FontFamily {
        if (selected != AppFont.GOOGLE_SANS_FLEX) return fallback

        val context = LocalContext.current
        var downloadedTypeface by remember(context) {
            mutableStateOf<Typeface?>(null)
        }

        LaunchedEffect(context, selected) {
            if (downloadedTypeface == null) {
                runCatching {
                    ResourcesCompat.getFont(
                        context,
                        R.font.google_sans_flex,
                        object : ResourcesCompat.FontCallback() {
                            override fun onFontRetrieved(typeface: Typeface) {
                                downloadedTypeface = typeface
                            }

                            override fun onFontRetrievalFailed(reason: Int) {
                                // Keep the bundled Orb family. Google Play
                                // Services can retry from its own font cache
                                // the next time the option is selected.
                            }
                        },
                        null,
                    )
                }
            }
        }

        return remember(downloadedTypeface, fallback) {
            downloadedTypeface?.let(::FontFamily) ?: fallback
        }
    }
}
