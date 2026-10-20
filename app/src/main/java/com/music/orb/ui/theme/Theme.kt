package com.music.orb.ui.theme

import android.app.Activity
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.ui.flavor.OrbFlavorUi

// Apple Music's signature red, used sparingly as the single accent.
val AccentRed = Color(0xFFFA2D48)

private val DarkColors = darkColorScheme(
    primary = AccentRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF65001E),
    onPrimaryContainer = Color(0xFFFFD9E0),
    secondary = Color(0xFFCBBEFF),
    onSecondary = Color(0xFF32245D),
    secondaryContainer = Color(0xFF493B75),
    onSecondaryContainer = Color(0xFFE7DEFF),
    tertiary = Color(0xFF8DD5F7),
    onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0xFF004D68),
    onTertiaryContainer = Color(0xFFC4E8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B090D),
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242126),
    onSurfaceVariant = Color(0xFFCAC4CB),
    outline = Color(0xFF958E96),
    outlineVariant = Color(0xFF4A454C),
    inverseSurface = Color(0xFFE7E0E7),
    inverseOnSurface = Color(0xFF302D31),
    inversePrimary = Color(0xFFBE0038),
    surfaceTint = AccentRed,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB90036),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E0),
    onPrimaryContainer = Color(0xFF40000F),
    secondary = Color(0xFF61558D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7DEFF),
    onSecondaryContainer = Color(0xFF1D1146),
    tertiary = Color(0xFF006685),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBDE9FF),
    onTertiaryContainer = Color(0xFF001F2A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8FA),
    onBackground = Color(0xFF1D1B1E),
    surface = Color(0xFFFFF8FA),
    onSurface = Color(0xFF1D1B1E),
    surfaceVariant = Color(0xFFECE0E4),
    onSurfaceVariant = Color(0xFF4E444B),
    outline = Color(0xFF80747B),
    outlineVariant = Color(0xFFD2C2C8),
    inverseSurface = Color(0xFF322F32),
    inverseOnSurface = Color(0xFFF6EEF2),
    inversePrimary = Color(0xFFFFB1C0),
    surfaceTint = Color(0xFFB90036),
)

/**
 * SF Pro Display, the face Apple Music itself is set in. Only the weights the
 * type scale actually asks for are bundled; Compose synthesises nothing, so a
 * missing weight would silently fall back to the nearest one shipped.
 */
val SFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

// Heavy, tight typography — the backbone of the Apple Music look.

// Dev/beta uses a rounder shape scale across Material components so the
// Expressive language is not limited to the bottom bar and mini player.
// Prod keeps Material's established/default scale until the redesign graduates.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val ClassicShapes = Shapes()

private val OrbTypographyBase = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
)

/** Applies [family] to every style in the scale, so nothing is left on Roboto. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

@Composable
fun OrbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dynamicColor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    var dynamicPaletteVersion by remember { mutableIntStateOf(0) }
    val typography = remember { OrbTypographyBase.withFamily(SFProDisplay) }

    // Android does not always recreate an already-running Activity when the
    // wallpaper/Material You palette changes. Listen to the system palette at
    // the theme boundary and invalidate ColorScheme immediately instead of
    // making the user kill and reopen Orb. LocalConfiguration is also part of
    // the key for OEMs that deliver palette changes as configuration updates.
    DisposableEffect(context, dynamicColor) {
        if (!dynamicColor || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O_MR1) {
            onDispose { }
        } else {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val listener = WallpaperManager.OnColorsChangedListener { _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    dynamicPaletteVersion++
                }
            }
            wallpaperManager.addOnColorsChangedListener(
                listener,
                Handler(Looper.getMainLooper()),
            )
            onDispose { wallpaperManager.removeOnColorsChangedListener(listener) }
        }
    }

    val colorScheme = remember(
        context,
        darkTheme,
        configuration,
        dynamicPaletteVersion,
    ) {
        val base = when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor && !darkTheme -> dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }

        // Material 3 Expressive canvas. The first pass was intentionally
        // conservative, but on-device it still read as pure black/white while
        // the header looked like a separate tinted slab. Keep the neutral OLED/
        // light anchors, then introduce enough palette chroma for Haze and
        // Expressive Glass to have something to refract.
        val neutralCanvas = if (darkTheme) Color.Black else Color.White
        val primaryTint = lerp(
            neutralCanvas,
            base.primaryContainer,
            if (darkTheme) 0.22f else 0.28f,
        )
        val secondaryTint = lerp(
            primaryTint,
            base.secondaryContainer,
            if (darkTheme) 0.09f else 0.12f,
        )
        val expressiveCanvas = lerp(
            secondaryTint,
            base.tertiaryContainer,
            if (darkTheme) 0.045f else 0.05f,
        )

        // Surface stays in exactly the same chromatic family as background.
        // Elevated containers remain free to provide depth, but the page itself
        // can no longer snap from a tinted header to a neutral black/white body.
        base.copy(
            background = expressiveCanvas,
            surface = lerp(expressiveCanvas, base.surface, 0.12f),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = if (OrbFlavorUi.expressive) ExpressiveShapes else ClassicShapes,
        content = content,
    )
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the surface the app is actually painting. Status and
 * navigation bars may sit on different surfaces, so callers can choose them
 * independently; ordinary screens keep the one-argument behaviour.
 */
@Composable
fun SystemBarIcons(
    dark: Boolean,
    navigationDark: Boolean = dark,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = navigationDark
        }
    }
}