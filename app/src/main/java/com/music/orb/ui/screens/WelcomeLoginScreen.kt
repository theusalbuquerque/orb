package com.music.orb.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.orb.R
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.artworkAt
import kotlinx.coroutines.delay

private const val WELCOME_ART_PX = 1440
private const val WELCOME_ROTATION_MS = 30_000L
private const val WELCOME_RETRY_MS = 8_000L
private const val THEUS_THREADS_URL = "https://www.threads.com/@theusalbuquerque"

/**
 * Signed-out gate shown before any of Orb's catalogue UI becomes reachable.
 *
 * The background is sourced from YouTube Music's public new-releases feed. A
 * random release is shown for 30 seconds, then crossfades to another one.
 */
@Composable
fun WelcomeLoginScreen(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pool by remember { mutableStateOf<List<ShelfItem>>(emptyList()) }
    var current by remember { mutableStateOf<ShelfItem?>(null) }

    LaunchedEffect(Unit) {
        while (pool.isEmpty()) {
            pool = YtMusicRepository.homeSupplemental()
                .getOrNull()
                .orEmpty()
                .flatMap { it.items }
                .filter { item ->
                    !item.thumbnailUrl.isNullOrBlank() &&
                        (item.type == BrowseType.ALBUM || item.browseId?.startsWith("MPREb") == true)
                }
                .distinctBy { it.browseId ?: it.thumbnailUrl }

            if (pool.isEmpty()) delay(WELCOME_RETRY_MS)
        }

        while (true) {
            val candidates = if (pool.size > 1) {
                pool.filterNot {
                    it.browseId == current?.browseId && it.thumbnailUrl == current?.thumbnailUrl
                }
            } else {
                pool
            }
            current = candidates.randomOrNull() ?: pool.randomOrNull()
            delay(WELCOME_ROTATION_MS)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090A0D)),
    ) {
        // Blurred full-screen copy keeps the bottom CTA area visually tied to
        // the release art even though the crisp hero ends above it.
        AnimatedContent(
            targetState = current?.thumbnailUrl,
            transitionSpec = {
                fadeIn(tween(1_000)) togetherWith fadeOut(tween(1_000))
            },
            label = "welcomeBackdrop",
            modifier = Modifier.fillMaxSize(),
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url.artworkAt(WELCOME_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(34.dp),
                )
            }
        }

        // The crisp release module now reaches both side edges and the top of
        // the display. Only the bottom corners remain rounded, matching the
        // reference while preserving the approved gap above the login button.
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                fadeIn(tween(850)) togetherWith fadeOut(tween(850))
            },
            label = "welcomeArtwork",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 122.dp),
        ) { release ->
            val heroShape = RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = 38.dp,
                bottomEnd = 38.dp,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(heroShape)
                    .background(Color(0xFF111217)),
            ) {
                val url = release?.thumbnailUrl
                if (url != null) {
                    AsyncImage(
                        model = url.artworkAt(WELCOME_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Soft shading for system icons and for the release metadata.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black.copy(alpha = 0.24f),
                                    0.18f to Color.Transparent,
                                    0.58f to Color.Transparent,
                                    0.76f to Color.Black.copy(alpha = 0.16f),
                                    1.00f to Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                        ),
                )

                if (release != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                    ) {
                        val shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.78f),
                            offset = Offset(0f, 2f),
                            blurRadius = 9f,
                        )
                        Text(
                            text = release.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.W700,
                                shadow = shadow,
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = release.artistLabel(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.W500,
                                shadow = shadow,
                            ),
                            color = Color.White.copy(alpha = 0.88f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Text(
            text = buildAnnotatedString {
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = Color.White,
                        fontWeight = FontWeight.W800,
                    ),
                )
                append("Orb")
                append("  •  ")
                append(stringResource(R.string.welcome_made_by))
                append(" ")
                withLink(LinkAnnotation.Url(THEUS_THREADS_URL, linkStyles)) {
                    append("THEUS")
                }
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.W600,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.72f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 7f,
                ),
            ),
            color = Color.White.copy(alpha = 0.96f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        GoogleSignInButton(
            onClick = onSignIn,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 22.dp),
        )
    }
}

@Composable
private fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonShape = RoundedCornerShape(26.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(buttonShape)
            .background(Color(0xFFF6F4F0))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_google_g),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.welcome_continue_google),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
            color = Color(0xFF17181B),
        )
    }
}

private fun ShelfItem.artistLabel(): String {
    val segments = subtitle
        .split('•', '·')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val mediaLabels = setOf(
        "album", "álbum", "single", "sencillo", "ep", "music", "música",
    )
    return segments.firstOrNull { part ->
        val normalized = part.lowercase()
        normalized !in mediaLabels && !normalized.matches(Regex("\\d{4}"))
    } ?: subtitle.ifBlank { title }
}
