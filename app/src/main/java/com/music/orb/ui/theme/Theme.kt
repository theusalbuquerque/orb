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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextGeometricTransform
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
    background = Color.Black,
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
    background = Color.White,
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

private val BitChordTypographyBase = Typography(
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

/**
 * Expressive Google Sans Flex scale used only when that beta-only font is selected.
 * The family already changes, but this scale makes the difference *visible*: it
 * pushes display/headline roles harder, tightens tracking, and gives the UI a
 * more editorial/variable-font character like the user's references.
 */
/*
 * Semantic Google Sans Flex scale.
 *
 * This is intentionally NOT a uniform "replace SF Pro with Google Sans" pass.
 * Material typography roles are already the semantic vocabulary used by Orb:
 *
 *   display*   -> page/hero text and the strongest visual statements
 *   headline*  -> section and large contextual headings
 *   title*     -> song/album/artist/person names and primary row text
 *   body*      -> descriptions, artist/subtitle text and supporting copy
 *   label*     -> metadata, counters, chips, buttons and compact UI
 *
 * Google Sans Flex mode gives every family of roles its own width, weight,
 * tracking and occasional slant. That makes the same variable face behave like
 * a small responsive type system instead of looking like one static font.
 *
 * TextGeometricTransform is also important here: it lets existing screens that
 * already use MaterialTheme.typography inherit a visibly different width/slant
 * character without hundreds of per-screen hard-coded font sizes.
 */
private val GoogleSansFlexTypographyBase = Typography(
    // Page/hero titles: intentionally strong and condensed. These are the
    // places where Flex should immediately look expressive instead of "regular".
    displayLarge = TextStyle(
        fontWeight = FontWeight.W900,
        fontSize = 42.sp,
        lineHeight = 43.sp,
        letterSpacing = (-1.35).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.86f),
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.W900,
        fontSize = 37.sp,
        lineHeight = 39.sp,
        letterSpacing = (-1.05).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.94f, skewX = -0.035f),
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.85).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.91f),
    ),

    // Section headers keep a strong editorial voice without competing with the
    // page title. Different width/slant creates the Flex contrast seen in the
    // reference material.
    headlineLarge = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.80).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.90f, skewX = -0.018f),
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 26.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.48).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.98f),
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 22.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.28).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.96f),
    ),

    // Names: songs, albums, artists, playlists and people. They are visibly
    // heavier than descriptions, but calmer than page/section headings.
    titleLarge = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 22.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.34).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.93f),
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.12).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.97f),
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 15.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.04).sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.98f),
    ),

    // Descriptions/supporting copy stay optically normal and thin. This is the
    // counterpoint that stops the whole app from looking uniformly bold.
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 1.00f),
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.01.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 1.00f),
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.04.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 1.00f),
    ),

    // Usernames, metadata and compact UI are intentionally much lighter than
    // names/titles. labelMedium is especially neutral because @user handles and
    // secondary metadata commonly use this role throughout Orb.
    labelLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.98f),
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.05.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 1.01f),
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.10.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 0.99f),
    ),
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
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dynamicColor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    var dynamicPaletteVersion by remember { mutableIntStateOf(0) }
    val selectedAppFont by AppSettings.appFont.collectAsState()
    val defaultTypography = remember { BitChordTypographyBase.withFamily(SFProDisplay) }
    val typography = OrbFlavorUi.resolveTypography(
        selected = selectedAppFont,
        defaultTypography = defaultTypography,
        googleSansFlexTypography = GoogleSansFlexTypographyBase,
    )

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
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context).copy(
                // Keep the complete Material You primary/secondary/tertiary
                // families and container roles; only Orb's page canvas stays
                // true black in Dark mode.
                background = Color.Black,
            )
            dynamicColor && !darkTheme -> dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }
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