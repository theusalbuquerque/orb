package com.music.orb.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.LocalAudioQuality
import com.music.orb.download.DownloadState
import com.music.orb.download.Downloads
import com.music.orb.data.settings.AppSettings
import com.music.orb.ui.flavor.OrbFlavorUi
import com.music.orb.data.sources.LosslessTier
import com.music.orb.data.sources.SourceKind
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.SourceResolver
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.orb.data.model.ROW_ART_PX
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.music.orb.R
import com.music.orb.ui.player.NowPlayingLaunchOriginRegistry

fun Modifier.thumbnailBorder(shape: Shape): Modifier = composed {
    this.border(
        width = 1.dp,
        color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
        shape = shape
    )
}

/**
 * The left and right inset every page's content sits at.
 *
 * It is the same inset the mini player and the tab bar float at, so the edge of
 * a track row, a card or a heading lines up with the edge of the bars stacked
 * below them rather than stepping in from them. One constant, shared by the
 * bars and the pages, is what keeps that true.
 */
val PAGE_GUTTER = 10.dp

/** Where a divider under a track row starts: clear of the 52dp of artwork. */
val ROW_DIVIDER_INSET = PAGE_GUTTER + 68.dp

/**
 * Width of a card in the compact carousels — home shelves, library shelves and
 * the artist page's releases alike.
 *
 * Sized so a phone-width row shows two cards whole with the edge of a third
 * showing: enough to say the row scrolls without a card being half a card.
 */
val SHELF_CARD_WIDTH = 150.dp

/**
 * The exact user-facing lossless tier behind a badge.
 *
 * This is intentionally richer than a Boolean: flattening a proven 24/96
 * stream into `true` is how Home/Search used to lose the distinction the
 * player had already measured.
 */
data class LosslessBadgeState(
    val isLossless: Boolean = false,
    val isHiResLossless: Boolean = false,
    val isHiQuality: Boolean = false,
)

private const val HI_QUALITY_BADGE_LABEL = "Hi-Q Audio"

private fun LosslessTier.toBadgeState(): LosslessBadgeState =
    LosslessBadgeState(
        isLossless = isLossless,
        isHiResLossless = isHiRes,
    )

private fun SourceResolver.PlayableBadgeQuality.toBadgeState(): LosslessBadgeState =
    if (!resolved) {
        // A candidate/partially-known collection must never paint a quality
        // badge. The resolver only flips resolved after the stream/bitrate has
        // actually been verified, so every UI surface shares the same rule.
        LosslessBadgeState()
    } else {
        LosslessBadgeState(
            isLossless = losslessTier.isLossless,
            isHiResLossless = losslessTier.isHiRes,
            isHiQuality = hiQuality && !losslessTier.isLossless,
        )
    }


@Composable
fun HiQualityWordmark(
    color: Color,
    modifier: Modifier = Modifier,
    iconWidth: Dp = 19.dp,
    iconHeight: Dp = 12.dp,
    style: TextStyle = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        lineHeight = 9.sp,
    ),
) {
    val description = stringResource(R.string.hi_quality_audio_available)
    val badgeColor = color

    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_hi_q_audio_mark),
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(width = iconWidth, height = iconHeight),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = HI_QUALITY_BADGE_LABEL,
            color = badgeColor,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Context-aware Lossless wordmark used everywhere in the app. The ribbon is a
 * VectorDrawable traced from the reference supplied for Orb; icon and text use
 * the same dynamic content colour as the Explicit E on the current surface.
 */
@Composable
fun LosslessWordmark(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    iconWidth: Dp = 19.dp,
    iconHeight: Dp = 12.dp,
    style: TextStyle = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        lineHeight = 9.sp,
    ),
) {
    // Use the caller's contextual content colour exactly. Album/playlist
    // surfaces already derive this colour from the artwork/background, and
    // the Explicit E receives the very same value. Do not collapse it to
    // black/white here: doing so makes Lossless diverge from the dynamic E.
    val badgeColor = color

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lossless_wave),
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(width = iconWidth, height = iconHeight),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = badgeColor,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Release-level badges shown below album/single metadata. Release pages spell
 * Explicit out in full while preserving the compact outlined-seal language of
 * the universal E used in rows/player. Lossless and Hi-Q Audio remain
 * monochrome wordmarks that inherit the surrounding surface colour.
 */
@Composable
fun ReleaseBadges(
    isExplicit: Boolean,
    isLossless: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    isHiResLossless: Boolean = false,
    isHiQuality: Boolean = false,
) {
    if (!isExplicit && !isLossless && !isHiQuality) return
    val losslessDescription = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_available else R.string.lossless_available,
    )
    val losslessLabel = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_badge else R.string.lossless_badge,
    )
    val arrangement = if (centered) Arrangement.Center else Arrangement.Start

    Row(
        modifier = modifier,
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isExplicit) {
            ReleaseExplicitWordmark(color = color.copy(alpha = 0.88f))
        }
        if (isExplicit && (isLossless || isHiQuality)) Spacer(Modifier.width(6.dp))
        if (isLossless) {
            LosslessWordmark(
                text = losslessLabel,
                color = color.copy(alpha = 0.88f),
                iconWidth = 20.dp,
                iconHeight = 13.dp,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    lineHeight = 10.sp,
                ),
                modifier = Modifier
                    .height(18.dp)
                    .semantics { contentDescription = losslessDescription },
            )
        } else if (isHiQuality) {
            HiQualityWordmark(
                color = color.copy(alpha = 0.88f),
                iconWidth = 20.dp,
                iconHeight = 13.dp,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    lineHeight = 10.sp,
                ),
                modifier = Modifier.height(18.dp),
            )
        }
    }
}


@Composable
private fun ReleaseExplicitWordmark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.explicit_badge_full).uppercase()
    val description = stringResource(R.string.explicit_content)
    Box(
        modifier = modifier
            .height(16.dp)
            .border(
                width = 0.8.dp,
                color = color,
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = TextStyle(
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.sp,
                lineHeight = 8.sp,
            ),
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** Compact title-row badges used by Search and local-device tracks. The
 * universal E marker and the exact Lossless tier stay next to the media title
 * rather than becoming detached metadata at the row edge. */
@Composable
fun CompactMediaBadges(
    isExplicit: Boolean,
    isLossless: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    isHiResLossless: Boolean = false,
    isHiQuality: Boolean = false,
) {
    if (!isExplicit && !isLossless && !isHiQuality) return
    val explicitDescription = stringResource(R.string.explicit_content)
    val losslessDescription = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_available else R.string.lossless_available,
    )
    val losslessLabel = stringResource(
        if (isHiResLossless) R.string.hi_res_lossless_badge else R.string.lossless_badge,
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isExplicit) {
            Icon(
                painter = painterResource(R.drawable.ic_explicit_badge),
                contentDescription = explicitDescription,
                tint = color.copy(alpha = 0.86f),
                modifier = Modifier.size(13.dp),
            )
        }
        if (isLossless) {
            LosslessWordmark(
                text = losslessLabel,
                color = color.copy(alpha = 0.86f),
                iconWidth = 17.5.dp,
                iconHeight = 11.dp,
                style = TextStyle(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    lineHeight = 9.sp,
                ),
                modifier = Modifier
                    .height(17.dp)
                    .semantics { contentDescription = losslessDescription },
            )
        } else if (isHiQuality) {
            HiQualityWordmark(
                color = color.copy(alpha = 0.86f),
                iconWidth = 17.5.dp,
                iconHeight = 11.dp,
                style = TextStyle(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    lineHeight = 9.sp,
                ),
                modifier = Modifier.height(17.dp),
            )
        }
    }
}

/**
 * Exact lossless tier for one track.
 *
 * Local files are special: their badge must describe the bytes the listener
 * owns, so it is read from FLAC/WAV/container metadata and does not depend on
 * the online "Lossless audio" preference. Network tracks keep the existing
 * verified-source probe, but retain the stream's native Hi-Res tier.
 */
@Composable
fun rememberTrackLosslessBadgeState(
    song: Song,
    allowProbe: Boolean = true,
): LosslessBadgeState {
    val local = !song.localUri.isNullOrBlank() || !song.localPath.isNullOrBlank()
    val context = LocalContext.current

    if (local) {
        val cachedLocal = LocalAudioQuality.cached(song)
        var localState by remember(song.videoId, song.localUri, song.localPath, cachedLocal) {
            mutableStateOf(cachedLocal?.losslessTier?.toBadgeState() ?: LosslessBadgeState())
        }
        LaunchedEffect(song.videoId, song.localUri, song.localPath, cachedLocal) {
            val format = cachedLocal ?: withContext(Dispatchers.IO) {
                LocalAudioQuality.inspect(context, song)
            }
            localState = format?.losslessTier?.toBadgeState() ?: LosslessBadgeState()
            if (!localState.isLossless && (format?.kbps ?: 0) >= 256) {
                localState = localState.copy(isHiQuality = true)
            }
        }
        return localState.copy(
            isHiQuality = !localState.isLossless && ((cachedLocal?.kbps ?: 0) >= 256 || localState.isHiQuality),
        )
    }

    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val badgeCacheRevision by SourceResolver.badgeCacheRevision.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val wifiMaximum by AppSettings.audioQualityWifiMaximum.collectAsStateWithLifecycle()
    val losslessEnabled by AppSettings.losslessAudio.collectAsStateWithLifecycle()
    val onWifi by AppSettings.wifiConnection.collectAsStateWithLifecycle()

    var state by remember(
        song.videoId, song.title, song.artist, badgeCacheRevision,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        mutableStateOf(SourceResolver.cachedTrackPlayableBadge(song).toBadgeState())
    }

    LaunchedEffect(
        song.videoId, song.title, song.artist, allowProbe,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        state = SourceResolver.cachedTrackPlayableBadge(song).toBadgeState()
    }

    LaunchedEffect(badgeCacheRevision) {
        state = SourceResolver.cachedTrackPlayableBadge(song).toBadgeState()
    }
    return state
}

/** Backwards-compatible Boolean view for callers that do not paint a tier. */
@Composable
fun rememberTrackLosslessAvailable(song: Song, allowProbe: Boolean = true): Boolean =
    rememberTrackLosslessBadgeState(song, allowProbe).isLossless

/**
 * Exact release-level quality state. A collection is only promoted to
 * Hi-Res Lossless after SourceResolver has proven its collection-level Hi-Res
 * criteria; an unresolved/unknown resolution remains ordinary Lossless.
 */
@Composable
fun rememberReleaseLosslessBadgeState(
    title: String,
    subtitle: String,
    isRelease: Boolean,
    knownSongs: List<Song> = emptyList(),
    browseId: String? = null,
    allowProbe: Boolean = true,
): LosslessBadgeState {
    if (!isRelease) return LosslessBadgeState()

    val badgeCacheRevision by SourceResolver.badgeCacheRevision.collectAsStateWithLifecycle()
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val wifiMaximum by AppSettings.audioQualityWifiMaximum.collectAsStateWithLifecycle()
    val losslessEnabled by AppSettings.losslessAudio.collectAsStateWithLifecycle()
    val onWifi by AppSettings.wifiConnection.collectAsStateWithLifecycle()
    val songIdentityKey = remember(knownSongs) {
        knownSongs.joinToString("\u001f") { "${it.videoId}|${it.title}|${it.artist}" }
    }

    var state by remember(
        title, subtitle, browseId, songIdentityKey, badgeCacheRevision,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        mutableStateOf(
            SourceResolver.cachedCollectionPlayableBadge(title, subtitle, browseId, knownSongs).toBadgeState(),
        )
    }

    LaunchedEffect(
        title, subtitle, browseId, songIdentityKey, allowProbe,
        wifiQuality, cellularQuality, wifiMaximum, losslessEnabled, onWifi, configs,
    ) {
        state = SourceResolver.cachedCollectionPlayableBadge(title, subtitle, browseId, knownSongs).toBadgeState()
    }

    LaunchedEffect(badgeCacheRevision) {
        state = SourceResolver.cachedCollectionPlayableBadge(title, subtitle, browseId, knownSongs).toBadgeState()
    }
    return state
}

/** Backwards-compatible Boolean view for callers that do not paint a tier. */
@Composable
fun rememberReleaseLosslessAvailable(
    title: String,
    subtitle: String,
    isRelease: Boolean,
    knownSongs: List<Song> = emptyList(),
    browseId: String? = null,
    allowProbe: Boolean = true,
): Boolean = rememberReleaseLosslessBadgeState(
    title = title,
    subtitle = subtitle,
    isRelease = isRelease,
    knownSongs = knownSongs,
    browseId = browseId,
    allowProbe = allowProbe,
).isLossless

data class CollectionBadgeState(
    val isExplicit: Boolean,
    val isLossless: Boolean,
    val isHiResLossless: Boolean = false,
    val isHiQuality: Boolean = false,
)

/**
 * Badge state for albums/singles and playlists.
 *
 * Albums keep the release-level probe. Playlists — including the account's own
 * playlists — derive Explicit/quality from their contents and share a cache
 * with the detail page, so cards and destinations use the same exact tier.
 */
@Composable
fun rememberCollectionBadges(
    type: BrowseType,
    title: String,
    subtitle: String,
    browseId: String?,
    explicitHint: Boolean,
    knownSongs: List<Song> = emptyList(),
    allowProbe: Boolean = true,
): CollectionBadgeState {
    // Explicit and quality share the same cache revision so a card on Home,
    // Explore or Library repaints immediately after another surface validates it.
    val badgeCacheRevision by SourceResolver.badgeCacheRevision.collectAsStateWithLifecycle()
    val verifiedQuality = rememberReleaseLosslessBadgeState(
        title = title,
        subtitle = subtitle,
        isRelease = type == BrowseType.ALBUM || type == BrowseType.PLAYLIST,
        knownSongs = knownSongs,
        browseId = browseId,
        allowProbe = allowProbe,
    )

    val albumExplicit = if (type == BrowseType.ALBUM) {
        explicitHint || knownSongs.any { it.isExplicit } ||
            SourceResolver.cachedReleaseExplicit(title, subtitle, browseId) == true
    } else false

    val isPlaylist = type == BrowseType.PLAYLIST && !browseId.isNullOrBlank()
    val cachedPlaylist = if (isPlaylist) {
        SourceResolver.cachedPlaylistBadges(requireNotNull(browseId))
    } else null
    var playlistExplicit by remember(
        browseId, type, explicitHint, cachedPlaylist?.isExplicit, knownSongs, badgeCacheRevision,
    ) {
        mutableStateOf(explicitHint || knownSongs.any { it.isExplicit } || cachedPlaylist?.isExplicit == true)
    }


    return when (type) {
        BrowseType.ALBUM -> CollectionBadgeState(
            isExplicit = albumExplicit,
            isLossless = verifiedQuality.isLossless,
            isHiResLossless = verifiedQuality.isHiResLossless,
            isHiQuality = verifiedQuality.isHiQuality,
        )
        BrowseType.PLAYLIST -> CollectionBadgeState(
            isExplicit = playlistExplicit || explicitHint || knownSongs.any { it.isExplicit },
            isLossless = verifiedQuality.isLossless,
            isHiResLossless = verifiedQuality.isHiResLossless,
            isHiQuality = verifiedQuality.isHiQuality,
        )
        else -> CollectionBadgeState(
            isExplicit = explicitHint,
            isLossless = false,
            isHiResLossless = false,
            isHiQuality = false,
        )
    }
}

/**
 * A title with a compact explicit-content marker.
 *
 * The E is always kept outside the title text whenever [isExplicit] is true,
 * so ellipsis can never hide a confirmed Explicit badge anywhere in the app.
 * Regular surfaces keep the title static and apply their normal [overflow].
 * MiniPlayer and Now Playing opt into [keepBadgeVisible], which additionally
 * lets a single-line title marquee while the E remains fixed in place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplicitTitle(
    text: String,
    isExplicit: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    badgeSpacing: Dp = 4.dp,
    // One universal visual size for the Explicit seal across the whole app.
    // The VectorDrawable itself owns the frame/glyph proportions.
    badgeSize: Dp = 13.dp,
    // Kept for source compatibility with existing callers. The vector owns
    // the E geometry now, so text size no longer affects the rendered badge.
    badgeFontSize: TextUnit = 9.sp,
    badgeVerticalOffset: Dp = 1.dp,
    fadeOverflow: Boolean = false,
    keepBadgeVisible: Boolean = false,
) {
    if (!isExplicit) {
        var overflowing by remember(text, maxLines) { mutableStateOf(false) }
        val marqueeEnabled = keepBadgeVisible && maxLines == 1
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier
                .then(
                    if (marqueeEnabled) {
                        Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    } else {
                        Modifier
                    },
                )
                .endFade(
                    enabled = fadeOverflow && overflowing,
                    width = 14.dp,
                ),
            maxLines = maxLines,
            overflow = if (marqueeEnabled) TextOverflow.Clip else overflow,
            textAlign = textAlign,
            onTextLayout = { overflowing = it.hasVisualOverflow },
        )
        return
    }

    val description = stringResource(R.string.explicit_content)
    var overflowing by remember(text, maxLines) { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            // Only MiniPlayer/Now Playing marquee. Every other surface keeps
            // its normal ellipsis while the badge receives reserved width.
            overflow = if (keepBadgeVisible && maxLines == 1) TextOverflow.Clip else overflow,
            textAlign = textAlign,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (keepBadgeVisible && maxLines == 1) {
                        Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    } else {
                        Modifier
                    },
                )
                .endFade(
                    enabled = keepBadgeVisible && fadeOverflow && overflowing,
                    width = 14.dp,
                ),
            onTextLayout = { overflowing = it.hasVisualOverflow },
        )
        Spacer(Modifier.width(badgeSpacing))
        // The frame and E are one geometric vector. Its default 13dp size is
        // shared by Now Playing, MiniPlayer, cards and ordinary list rows.
        Icon(
            painter = painterResource(R.drawable.ic_explicit_badge),
            contentDescription = description,
            tint = color.copy(alpha = 0.88f),
            modifier = Modifier
                .offset(y = badgeVerticalOffset)
                .size(badgeSize),
        )
    }
}

private fun Modifier.endFade(
    enabled: Boolean,
    width: Dp,
): Modifier = if (!enabled) {
    this
} else {
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadeWidth = width.toPx().coerceAtMost(size.width)
            val fadeStart = (size.width - fadeWidth).coerceAtLeast(0f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf<Pair<Float, Color>>(
                        0f to Color.Black,
                        (if (size.width == 0f) 1f else (fadeStart / size.width)) to Color.Black,
                        1f to Color.Transparent,
                    ),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * One track row, used by search, library and detail pages.
 *
 * Swiping springs the row back rather than dismissing it. Album/playlist
 * callers can supply direction-specific actions (right = Add to queue, left =
 * Play next); legacy callers may keep [onSwipeToQueue], which still follows
 * [AppSettings.swipeToPlayNext]. Long-press opens the actions menu.
 */
/**
 * Compact, passive download state shown next to a track duration. Queued and
 * active downloads share the in-progress glyph; a saved track gets the
 * completed-download glyph. Failed/absent downloads intentionally render
 * nothing here — the action sheet remains the place for errors and retries.
 */
@Composable
fun DownloadStatusGlyph(
    videoId: String,
    tint: Color,
    modifier: Modifier = Modifier,
    assumeSaved: Boolean = false,
) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val icon = when (active[videoId]) {
        is DownloadState.Queued, is DownloadState.Running -> Icons.Rounded.Downloading
        else -> if (assumeSaved || videoId in saved) Icons.Rounded.DownloadDone else null
    } ?: return

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(15.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    /**
     * What the row paints over the swipe reveal as it slides back.
     *
     * It has to be the colour of the page the row is *on*, not the theme's
     * background — an album page tinted from its sleeve would otherwise drag a
     * black band across itself on every swipe.
     */
    rowBackground: Color = MaterialTheme.colorScheme.background,
    /**
     * Drawn in place of the artwork, for lists where every row would otherwise
     * repeat the same cover — an album's own track listing.
     */
    trackNumber: Int? = null,
    /**
     * The artist line, and the track number when there is one.
     *
     * A page tinted from its artwork wants this brighter than the flat feeds
     * do: the usual dim grey is pitched against black, and against a mid-toned
     * wash it stops being legible as a second line and starts disappearing.
     */
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** Search-only compact E/Lossless badges; other SongRow callers stay unchanged. */
    showSearchBadges: Boolean = false,
    /** Search pipeline already resolved this row; do not launch a duplicate probe from Compose. */
    searchBadgesPreResolved: Boolean = false,
    /** Search/feed rows can opt into the rounded Expressive surface without changing album track lists. */
    expressiveContainer: Boolean = false,
    forceDownloadedGlyph: Boolean = false,
    fullExplicitBadge: Boolean = false,
) {
    val swipeStateHolder = remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    var boxWidth by remember { mutableFloatStateOf(0f) }
    val localTrack = !song.localUri.isNullOrBlank() || !song.localPath.isNullOrBlank()
    val rowQuality = if (showSearchBadges || localTrack) {
        rememberTrackLosslessBadgeState(
            song = song,
            allowProbe = showSearchBadges && !searchBadgesPreResolved,
        )
    } else {
        LosslessBadgeState()
    }
    val showInlineBadges = showSearchBadges || rowQuality.isLossless || rowQuality.isHiQuality

    val hasDirectionalSwipe = onSwipeRight != null || onSwipeLeft != null
    val hasAnySwipe = hasDirectionalSwipe || onSwipeToQueue != null
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && hasAnySwipe) {
                val offset = try { swipeStateHolder.value?.requireOffset() ?: 0f } catch (e: Exception) { 0f }
                // Only act if the physical drag reached almost half the row,
                // ignoring short accidental flings. Use the signed pixel offset
                // rather than Start/End so “right” and “left” remain literal even
                // if the device is running an RTL locale.
                if (abs(offset) >= boxWidth * 0.45f) {
                    when {
                        offset > 0f -> (onSwipeRight ?: onSwipeToQueue)?.invoke()
                        offset < 0f -> (onSwipeLeft ?: onSwipeToQueue)?.invoke()
                    }
                }
            }
            false // never actually dismiss; snap back
        },
        positionalThreshold = { distance -> distance * 0.5f },
    )
    swipeStateHolder.value = swipeState

    if (!hasAnySwipe) {
        SongRowContent(
            song, onClick, onLongPress, modifier, trackNumber, subtitleColor,
            showInlineBadges = showInlineBadges,
            quality = rowQuality,
            expressiveContainer = expressiveContainer,
            forceDownloadedGlyph = forceDownloadedGlyph,
            fullExplicitBadge = fullExplicitBadge,
        )
        return
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.onSizeChanged { boxWidth = it.width.toFloat() },
        backgroundContent = { QueueSwipeBackground(swipeState, directionalActions = hasDirectionalSwipe) },
    ) {
        SongRowContent(
            song = song,
            onClick = onClick,
            onLongPress = onLongPress,
            modifier = Modifier.background(rowBackground),
            trackNumber = trackNumber,
            subtitleColor = subtitleColor,
            showInlineBadges = showInlineBadges,
            quality = rowQuality,
            expressiveContainer = expressiveContainer,
            forceDownloadedGlyph = forceDownloadedGlyph,
            fullExplicitBadge = fullExplicitBadge,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwipeBackground(
    swipeState: SwipeToDismissBoxState,
    directionalActions: Boolean,
) {
    val legacyPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val offset = try { swipeState.requireOffset() } catch (e: Exception) { 0f }
                if (offset > 0f) {
                    clipRect(left = 0f, top = 0f, right = offset, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                } else if (offset < 0f) {
                    clipRect(left = size.width + offset, top = 0f, right = size.width, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = PAGE_GUTTER + 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (directionalActions) {
            // Dragging right reveals the left edge: Add to queue. Dragging left
            // reveals the right edge: Play next.
            QueueSwipeLabel(playNext = false)
            QueueSwipeLabel(playNext = true)
        } else {
            QueueSwipeLabel(legacyPlayNext)
            QueueSwipeLabel(legacyPlayNext)
        }
    }
}

@Composable
private fun QueueSwipeLabel(playNext: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (playNext) Icons.Rounded.PlaylistPlay else Icons.Rounded.PlaylistAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (playNext) R.string.song_action_play_next else R.string.song_action_add_to_queue,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showInlineBadges: Boolean = false,
    quality: LosslessBadgeState = LosslessBadgeState(),
    expressiveContainer: Boolean = false,
    forceDownloadedGlyph: Boolean = false,
    fullExplicitBadge: Boolean = false,
) {
    var launchBounds by remember(song.videoId) { mutableStateOf<Rect?>(null) }
    val launchAwareClick = {
        launchBounds?.let(NowPlayingLaunchOriginRegistry::record)
        onClick()
    }
    val rowModifier = if (OrbFlavorUi.expressive && expressiveContainer) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = launchAwareClick, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    } else {
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = launchAwareClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            // Same 52dp the artwork would take, so a numbered list and an
            // illustrated one share a left edge and a divider inset.
            Box(
                Modifier
                    .size(52.dp)
                    .onGloballyPositioned { coordinates ->
                        val topLeft = coordinates.positionInRoot()
                        launchBounds = Rect(
                            left = topLeft.x,
                            top = topLeft.y,
                            right = topLeft.x + coordinates.size.width,
                            bottom = topLeft.y + coordinates.size.height,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$trackNumber",
                    style = MaterialTheme.typography.bodyLarge,
                    color = subtitleColor,
                )
            }
        } else {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .onGloballyPositioned { coordinates ->
                        val topLeft = coordinates.positionInRoot()
                        launchBounds = Rect(
                            left = topLeft.x,
                            top = topLeft.y,
                            right = topLeft.x + coordinates.size.width,
                            bottom = topLeft.y + coordinates.size.height,
                        )
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (showInlineBadges) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AnimatedVisibility(
                        visible = song.isExplicit || quality.isLossless || quality.isHiQuality,
                        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.86f),
                        exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = 0.90f),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(6.dp))
                            if (fullExplicitBadge) {
                                ReleaseBadges(
                                    isExplicit = song.isExplicit,
                                    isLossless = quality.isLossless,
                                    isHiResLossless = quality.isHiResLossless,
                                    isHiQuality = quality.isHiQuality,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                CompactMediaBadges(
                                    isExplicit = song.isExplicit,
                                    isLossless = quality.isLossless,
                                    isHiResLossless = quality.isHiResLossless,
                                    isHiQuality = quality.isHiQuality,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                ExplicitTitle(
                    text = song.title,
                    isExplicit = song.isExplicit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song.durationText?.let { duration ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelMedium,
                color = subtitleColor,
            )
        }
        // Download state is independent from whether duration metadata exists.
        // Old/legacy downloads can lack durationText, but they are still valid
        // offline files and must keep the completed-download affordance.
        Spacer(Modifier.width(5.dp))
        DownloadStatusGlyph(
            videoId = song.videoId,
            tint = subtitleColor,
            assumeSaved = forceDownloadedGlyph,
        )
        // Same sheet the long-press opens, for anyone who doesn't think to hold.
        if (onLongPress != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLongPress),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Pull-to-refresh for the tab feeds, with the usual circular puck suppressed.
 *
 * The feeds sit under a frosted bar that already occupies the top 96dp, so a
 * puck dropping into that space would be blurred out by the glass it lands
 * behind. The drag feedback is the loader line along the bottom edge of the
 * bar instead — which is why [state] is hoisted: the bar lives beside this
 * content, not inside it, and has to follow the same drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {},
    ) {
        content()
    }
}

/** Slim dismissible-looking prompt shown atop Home while signed out. */
@Composable
fun SignInBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onSignIn)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.banner_signin_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.banner_signin_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onSignIn) { Text(stringResource(R.string.banner_signin_button)) }
    }
}

@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER + 12.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
