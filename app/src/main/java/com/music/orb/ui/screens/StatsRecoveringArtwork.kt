package com.music.orb.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class StatsArtworkKind { ARTIST, SONG, ALBUM, PLAYLIST }

/**
 * Self-healing artwork for Stats cards and ranking rows.
 * - First uses the original recorded image, with the requested size hint.
 * - If the CDN rejects the resized URL, retries the original untouched URL.
 * - If the original URL is missing or stale, resolves a fresh image by verified
 *   title + artist. Private playlist art is never guessed from public search.
 * - The fallback icon remains visible during loading and after an error.
 *
 * Lookup is only performed for missing/broken images, never per recomposition.
 */
@Composable
fun StatsRecoveringArtwork(
    url: String?,
    title: String?,
    subtitle: String? = null,
    kind: StatsArtworkKind = StatsArtworkKind.SONG,
    modifier: Modifier = Modifier,
    contentDescription: String? = title,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    requestedPx: Int = 480,
    fallbackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val rawUrl = url?.trim()?.takeIf { it.isNotEmpty() }
    val identity = remember(title, subtitle, kind) {
        "${kind.name}:${title.orEmpty().trim().lowercase(Locale.ROOT)}:${subtitle.orEmpty().trim().lowercase(Locale.ROOT)}"
    }
    var useOriginal by remember(rawUrl) { mutableStateOf(false) }
    var recoveredUrl by remember(rawUrl, identity) { mutableStateOf<String?>(null) }
    var lookupRequested by remember(rawUrl, identity) { mutableStateOf(rawUrl == null) }
    var lookupAttempted by remember(rawUrl, identity) { mutableStateOf(false) }
    var imageReady by remember(rawUrl, identity) { mutableStateOf(false) }

    LaunchedEffect(identity, rawUrl, lookupRequested) {
        if (!lookupRequested || lookupAttempted || title.isNullOrBlank()) return@LaunchedEffect
        lookupAttempted = true
        val replacement = StatsArtworkRecovery.find(kind, title, subtitle)
        if (!replacement.isNullOrBlank() && replacement != rawUrl) {
            recoveredUrl = replacement
            imageReady = false
        }
    }

    val active = recoveredUrl ?: when {
        rawUrl == null -> null
        useOriginal -> rawUrl
        else -> rawUrl.artworkAt(requestedPx)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!imageReady) {
            Icon(
                imageVector = if (kind == StatsArtworkKind.ARTIST) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = fallbackColor,
                modifier = Modifier.fillMaxSize(0.38f),
            )
        }
        if (active != null) {
            AsyncImage(
                model = active,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { imageReady = true },
                onError = {
                    imageReady = false
                    when {
                        recoveredUrl != null -> Unit // Already retried a newly resolved URL.
                        !useOriginal && active != rawUrl -> useOriginal = true
                        else -> lookupRequested = true
                    }
                },
            )
        }
    }
}

private object StatsArtworkRecovery {
    private val positives = ConcurrentHashMap<String, String>()

    suspend fun find(kind: StatsArtworkKind, title: String, subtitle: String?): String? {
        if (kind == StatsArtworkKind.PLAYLIST) return null // Never show an unrelated private playlist.
        val key = "${kind.name}:${title.trim().lowercase(Locale.ROOT)}:${subtitle.orEmpty().trim().lowercase(Locale.ROOT)}"
        positives[key]?.let { return it }
        val resolved = withTimeoutOrNull(6_000L) {
            withContext(Dispatchers.IO) {
                when (kind) {
                    StatsArtworkKind.ARTIST -> ArtistArtworkRepository.resolve(title)
                    StatsArtworkKind.SONG -> {
                        YtMusicRepository.search(listOfNotNull(title, subtitle).joinToString(" "), SearchFilter.SONGS)
                            .getOrNull()?.filterIsInstance<SearchResult.Track>()
                            ?.map { it.song }
                            ?.firstOrNull { song ->
                                song.title.trim().equals(title.trim(), ignoreCase = true) &&
                                    (subtitle.isNullOrBlank() || sameArtist(song.artist, subtitle))
                            }?.thumbnailUrl
                    }
                    StatsArtworkKind.ALBUM -> {
                        YtMusicRepository.search(listOfNotNull(title, subtitle).joinToString(" "), SearchFilter.ALBUMS)
                            .getOrNull()?.filterIsInstance<SearchResult.Browse>()
                            ?.map { it.item }
                            ?.firstOrNull { item ->
                                item.type == BrowseType.ALBUM &&
                                    item.title.trim().equals(title.trim(), ignoreCase = true) &&
                                    (subtitle.isNullOrBlank() || sameArtist(item.subtitle, subtitle))
                            }?.thumbnailUrl
                    }
                    StatsArtworkKind.PLAYLIST -> null
                }
            }
        }
        if (!resolved.isNullOrBlank()) positives[key] = resolved
        return resolved
    }

    private fun sameArtist(candidate: String, wanted: String): Boolean {
        val a = candidate.lowercase(Locale.ROOT).trim()
        val b = wanted.lowercase(Locale.ROOT).trim()
        return a == b || (b.length >= 4 && a.contains(b)) || (a.length >= 4 && b.contains(a))
    }
}
