package com.music.orb.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.orb.data.model.NOTIFICATION_ART_PX
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.SourceResolver
import com.music.orb.data.sources.TrackMatcher
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Future tracks whose position was explicitly chosen by a queue drag.
 *
 * PlaybackService and the Compose controller share the app process, so this
 * process-local set lets a single MediaController.moveMediaItem command carry
 * the user's intent without a second metadata-replacement command racing the
 * session timeline. A media-id pin is deliberately conservative for duplicate
 * copies of the same recording: it may protect one extra copy, but it can never
 * move the copy the listener explicitly placed.
 */
internal object ManualQueuePins {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun pin(mediaId: String) {
        if (mediaId.isNotBlank()) ids.add(mediaId)
    }

    fun isPinned(mediaId: String): Boolean = mediaId in ids

    /** Once the pinned item is current, its future position no longer exists. */
    fun consume(mediaId: String) {
        ids.remove(mediaId)
    }

    fun clear() {
        ids.clear()
    }
}



/**
 * Compatibility shim for Orb call sites retained around BitChord v1.5 playback.
 * BitChord v1.5 has no opening-set launch gate: playback begins normally and
 * Automix plans only the current A -> B transition.
 */
internal object AutomixQueueLaunch {
    fun arm() = Unit
    fun isPendingFor(player: Player): Boolean = false
    fun elapsedMs(player: Player): Long? = null
    fun openingFinalized(): Boolean = true
    fun finalizeOpening(selectedMediaId: String) = Unit
    fun selectedMediaId(): String? = null
    fun tryStartQualityPrime(mediaId: String): Boolean = false
    fun qualityPrimeFinished(): Boolean = true
    fun finishQualityPrime(mediaId: String) = Unit
    fun consume(player: Player): Boolean = false
    fun cancel() = Unit
}


/** Snapshot of playback state, driven by the MediaController. */
data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    /** True while ExoPlayer is buffering — including our own stream-URL resolution. */
    val isLoading: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    val albumSequential: Boolean = false,
    /**
     * Whether the queue has somewhere to go either side of the current track.
     * Taken from the player rather than [queueIndex], so the wrap-around of
     * repeat-all is already accounted for.
     */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** Binds to [PlaybackService] for the lifetime of the composition. */
@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

/** Mirrors the controller into Compose state, polling position while playing. */
@Composable
fun rememberPlayerState(controller: MediaController?): PlayerState {
    var state by remember { mutableStateOf(PlayerState()) }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}

        fun sync(error: String? = null) {
            val item = player.currentMediaItem
            state = state.copy(
                song = item?.toSong(),
                isPlaying = player.isPlaying,
                // Sync position here too, so seeking while paused or buffering
                // still moves the scrubber (the poll loop only runs on play).
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L),
                error = error,
                isLoading = player.playbackState == Player.STATE_BUFFERING,
                repeatMode = player.repeatMode,
                queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
                queueIndex = player.currentMediaItemIndex,
                albumSequential = player.currentMediaItem?.albumSequential == true && !QueueShuffle.enabled.value,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
            )
        }

        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = sync(state.error)
            override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
                sync(error?.let { "Playback failed: ${it.errorCodeName}" })
            }
        }
        player.addListener(listener)
        sync()
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(controller, state.isPlaying) {
        while (controller != null && state.isPlaying) {
            state = state.copy(
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = controller.duration.coerceAtLeast(0L),
            )
            delay(500)
        }
    }
    return state
}

/**
 * The inverse of [toMediaItem], as far as a MediaItem can carry a [Song].
 *
 * It has to round-trip losslessly for everything [LastPlayed] stores, because
 * the queue it saves is read back out of the *player* — so a field dropped here
 * is a field that does not survive a restart, however carefully it is
 * persisted. That is what happened to [Song.durationText]: stored, restored,
 * and always null, because this function never carried it back off the item in
 * the first place.
 */
fun MediaItem.toSong() = Song(
    videoId = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    thumbnailUrl = mediaMetadata.artworkUri?.toString(),
    durationText = mediaMetadata.extras?.getString(EXTRA_DURATION),
    albumId = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
    albumName = mediaMetadata.extras?.getString(EXTRA_ALBUM_NAME),
    isExplicit = mediaMetadata.extras?.getBoolean(EXTRA_EXPLICIT) == true,
    releaseYear = mediaMetadata.extras?.getInt(EXTRA_RELEASE_YEAR)?.takeIf { it > 0 },
    fromAutoplay = this.fromAutoplay,
    queuePinned = this.queuePinned,
    localUri = mediaMetadata.extras?.getString(EXTRA_LOCAL_URI),
    localPath = mediaMetadata.extras?.getString(EXTRA_LOCAL_PATH),
    sourcePlaylistId = mediaMetadata.extras?.getString(EXTRA_SOURCE_PLAYLIST_ID),
    sourcePlaylistTitle = mediaMetadata.extras?.getString(EXTRA_SOURCE_PLAYLIST_TITLE),
    sourcePlaylistArtworkUrl = mediaMetadata.extras?.getString(EXTRA_SOURCE_PLAYLIST_ARTWORK),
)

/** @see Song.fromAutoplay */
val MediaItem.fromAutoplay: Boolean
    get() = mediaMetadata.extras?.getBoolean(EXTRA_FROM_AUTOPLAY) == true

/** Whether this future queue entry was placed explicitly by the listener. */
val MediaItem.queuePinned: Boolean
    get() = mediaMetadata.extras?.getBoolean(EXTRA_QUEUE_PINNED) == true


/** True only for tracks launched as an album's original, non-shuffled sequence. */
val MediaItem.albumSequential: Boolean
    get() = mediaMetadata.extras?.getBoolean(EXTRA_ALBUM_SEQUENTIAL) == true


/**
 * Marks a queue entry as AutoPlay's rather than the user's. Carried on the
 * MediaItem so it survives the trip through the session — the queue belongs to
 * the player, and the UI only ever sees it back through a MediaController.
 */
private const val EXTRA_FROM_AUTOPLAY = "orb.fromAutoplay"

/** Position chosen explicitly by the listener; see [MediaItem.queuePinned]. */
private const val EXTRA_QUEUE_PINNED = "orb.queuePinned"


/** Album identity carried into the player so playback can preserve album continuity. */
private const val EXTRA_ALBUM_ID = "orb.albumId"
private const val EXTRA_ALBUM_NAME = "orb.albumName"

/** Local-only playlist source context used by Stats; never sent as audio/social data. */
private const val EXTRA_SOURCE_PLAYLIST_ID = "orb.sourcePlaylistId"
private const val EXTRA_SOURCE_PLAYLIST_TITLE = "orb.sourcePlaylistTitle"
private const val EXTRA_SOURCE_PLAYLIST_ARTWORK = "orb.sourcePlaylistArtwork"

/** Queue item belongs to an album launched in its original (non-shuffled) order. */
private const val EXTRA_ALBUM_SEQUENTIAL = "orb.albumSequential"

/** @see Song.localUri */
private const val EXTRA_LOCAL_URI = "orb.localUri"

/** @see Song.localPath */
private const val EXTRA_LOCAL_PATH = "orb.localPath"

/**
 * How long the track runs, as the row that queued it said.
 *
 * On the item rather than left to [MediaMetadata.durationMs] because that field
 * is the *player's* to state, and the player takes its own figure from the
 * decoder. This one is the claim a cross-source match is made on — see
 * [TrackMatcher] — and the two disagree often enough that overwriting either
 * with the other loses information. Carried so that [toSong] can give it back,
 * which is what [LastPlayed] saves and what puts `&d=` on a restored track's
 * playback URI.
 */
private const val EXTRA_DURATION = "orb.durationText"

/** Whether this queue entry is the catalogue's explicit master. */
private const val EXTRA_EXPLICIT = "orb.explicit"

/** Release year carried through Media3 so Now Playing/credits do not lose it. */
private const val EXTRA_RELEASE_YEAR = "orb.releaseYear"

/**
 * Where AutoPlay's section of the queue begins, and so where a track queued by
 * hand belongs — above the mix, below everything the user picked.
 *
 * Read as "the first of AutoPlay's tracks still to come", which is what keeps
 * it below the playing track even when the mix itself is what's playing: the
 * tracks of it already behind you count as played, and the section starts
 * again below the needle. Tracks put in by hand there — "Play next" while the
 * mix runs — stay above it too, for the same reason.
 *
 * The queue panel draws its AutoPlay heading at this same index.
 */
fun autoplaySectionStart(fromAutoplay: List<Boolean>, currentIndex: Int): Int {
    val after = (currentIndex + 1).coerceIn(0, fromAutoplay.size)
    return (after until fromAutoplay.size).firstOrNull { fromAutoplay[it] }
        ?: fromAutoplay.size
}

fun MediaController.autoplaySectionStart(): Int = autoplaySectionStart(
    fromAutoplay = (0 until mediaItemCount).map { getMediaItemAt(it).fromAutoplay },
    currentIndex = currentMediaItemIndex,
)



/**
 * Takes back what AutoPlay queued and hasn't played yet — what switching
 * AutoPlay off means for a queue it has already been extending. Removed from
 * the bottom up so the indexes ahead of each removal still hold.
 */
fun MediaController.dropAutoplayTracks() {
    for (i in mediaItemCount - 1 downTo currentMediaItemIndex + 1) {
        if (getMediaItemAt(i).fromAutoplay) removeMediaItem(i)
    }
}

/**
 * Custom scheme; PlaybackService resolves the real stream URL at play time.
 *
 * A video-tagged [Song] is expected to already have been swapped for its
 * catalogue audio release by [com.music.orb.data.YtMusicRepository.resolveAudio]
 * before this is called — the queue, history and the notification should
 * never see the video upload's id or title, only whatever the audio match
 * resolved to (or the video's own audio, as the deliberate fallback when no
 * match was found).
 */
/**
 * MP4-family containers (m4a/aac/amr/wma/...) store their header or trailing
 * metadata in a way that needs backward seeking to parse, which the
 * content:// route (ContentDataSource) doesn't reliably support — the same
 * bytes read fine as a plain file. Formats like flac/mp3/ogg/webm already
 * seek correctly through content:// and are left alone.
 */
private val DIRECT_FILE_URI_EXTENSIONS = setOf(
    "m4a", "m4b", "m4p", "mp4", "aac", "3ga", "3gp", "3gpp",
    "alac", "amr", "awb", "wma", "aif", "aiff", "ac3", "dts",
)

private fun resolvePlaybackUri(uriString: String, localPath: String?): String {
    if (localPath.isNullOrBlank() || !uriString.startsWith("content://")) return uriString
    val ext = localPath.substringAfterLast('.', "").lowercase()
    if (ext !in DIRECT_FILE_URI_EXTENSIONS) return uriString
    val file = File(localPath)
    return if (file.exists() && file.canRead()) Uri.fromFile(file).toString() else uriString
}

/**
 * The identity tail every playback URI carries: title, artist, runtime,
 * explicit state, release identity, and (for YouTube) the authoritative source
 * video id. [com.music.orb.data.sources.TrackMatcher] consumes these fields
 * when another catalogue offers replacement audio.
 *
 * Keeping album/year here is especially important because the resolver runs on
 * ExoPlayer's loader thread with only this URI available; without them a cover
 * can look deceptively valid when title, artist text and duration happen to
 * agree.
 */
private fun Song.matchQuery(): String = buildString {
    append("&n=").append(Uri.encode(title))
    append("&a=").append(Uri.encode(artist))
    TrackMatcher.secondsOf(durationText)?.let { append("&d=").append(it) }
    // Cross-source resolution happens from this URI on ExoPlayer's loader
    // thread, so all identity metadata needed to reject covers/re-recordings
    // has to travel with the title. Album/year used to be lost here, which
    // meant an external source could match only title + artist + runtime and
    // then play a different release under the original Orb metadata.
    albumName?.takeIf { it.isNotBlank() }?.let { append("&al=").append(Uri.encode(it)) }
    releaseYear?.let { append("&y=").append(it) }
    append("&e=").append(if (isExplicit) 1 else 0)
    // A bare YouTube id identifies an official-carrier request. SourceResolver
    // uses this only to turn on the stricter substitution gate; source-backed
    // tracks keep their own catalogue semantics.
    if (SourceRegistry.parseTrackKey(videoId) == null) {
        append("&sv=").append(Uri.encode(videoId))
    }
}

fun Song.toMediaItem(albumSequential: Boolean = false): MediaItem {
    val sourceTrack = SourceRegistry.parseTrackKey(videoId)
    // DefaultMediaSourceFactory chooses Progressive vs DASH before the custom
    // resolving data source sees the final URL. A queue-prepared TIDAL FLAC
    // manifest therefore has to advertise DASH on the MediaItem itself.
    val preparedDash = localUri == null && SourceResolver.preparedLosslessIsDash(this)
    val uriString = localUri ?: when {
        videoId.startsWith("content://") || videoId.startsWith("file://") -> videoId
        // Title, artist and runtime ride along in the URI because they are what
        // a cross-source match is made on, and the resolver runs on ExoPlayer's
        // loader thread with nothing but a DataSpec in hand — see
        // [SourceResolver.resolve]. Read-ahead resolves tracks that aren't the
        // current item, so reaching back for the session's metadata isn't an
        // option either.
        sourceTrack != null -> SourceRegistry.trackUri(sourceTrack.first, sourceTrack.second)
            .let { "$it${if (preparedDash) "&pl=1" else ""}${matchQuery()}" }
        // The same three fields, for the same reason, on the YouTube path: a
        // source ranked above YouTube gets offered this track before YouTube
        // resolves it — see [SourceResolver.substituteForYouTube] — and that
        // match is made on them, which the loader thread has no other way to
        // reach.
        else -> "orb://watch?v=$videoId${if (preparedDash) "&pl=1" else ""}${matchQuery()}"
    }
    return MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(resolvePlaybackUri(uriString, localPath))
        .apply {
            if (preparedDash) setMimeType(MimeTypes.APPLICATION_MPD)
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                // Sized here rather than left as stored: this is what the lock
                // screen, the notification and Android Auto draw, all of them
                // large, and none of them go back for a better copy later.
                .setArtworkUri(artworkAt(NOTIFICATION_ART_PX)?.toUri())
                // System media surfaces (One UI's Now Bar, Android Auto, Assistant)
                // classify a session by its media type; untyped sessions get treated
                // as generic audio and lose the music-specific card.
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                // What a queue entry has to carry about itself: which section of
                // the queue it belongs to, whether it is playing off the device,
                // and how long the row that queued it said it runs. The uri two
                // lines up answers the second question but does not survive the
                // trip back out — Media3 leaves a MediaItem's localConfiguration
                // out of the bundle it sends to a MediaController — so without this
                // a track playing from a file reaches the UI looking like any other
                // YouTube track, and the player's menu offers to rate, download and
                // share it.
                //
                // Set for every track rather than only the local and AutoPlay ones,
                // because the runtime applies to all of them: gated on those two, a
                // plain YouTube track carried no extras at all, so [toSong] read
                // back a null duration, [LastPlayed] stored a null, and the restored
                // queue lost the `&d=` its matching depends on.
                .apply {
                    if (
                        fromAutoplay || queuePinned || localUri != null ||
                        durationText != null || isExplicit || releaseYear != null || albumId != null ||
                        albumName != null || albumSequential || sourcePlaylistId != null ||
                        sourcePlaylistTitle != null || sourcePlaylistArtworkUrl != null
                    ) {
                        setExtras(
                            bundleOf(
                                EXTRA_FROM_AUTOPLAY to fromAutoplay,
                                EXTRA_QUEUE_PINNED to queuePinned,
                                EXTRA_ALBUM_ID to albumId,
                                EXTRA_ALBUM_NAME to albumName,
                                EXTRA_SOURCE_PLAYLIST_ID to sourcePlaylistId,
                                EXTRA_SOURCE_PLAYLIST_TITLE to sourcePlaylistTitle,
                                EXTRA_SOURCE_PLAYLIST_ARTWORK to sourcePlaylistArtworkUrl,
                                EXTRA_ALBUM_SEQUENTIAL to albumSequential,
                                EXTRA_LOCAL_URI to localUri,
                                EXTRA_LOCAL_PATH to localPath,
                                EXTRA_DURATION to durationText,
                                EXTRA_EXPLICIT to isExplicit,
                                EXTRA_RELEASE_YEAR to (releaseYear ?: 0),
                            ),
                        )
                    }
                }
                .build(),
        )
        .build()
}

/**
 * Manual queue ordering is allowed while the current session is playing in its
 * explicit queue order. Shuffle is the ownership boundary: once the listener
 * enables Shuffle (either at launch or from Now Playing), the shuffled session
 * owns the future order and drag gestures are ignored at the controller
 * boundary as a second line of defence behind the UI.
 */
fun MediaController.moveMediaItemByUser(fromIndex: Int, toIndex: Int) {
    if (QueueShuffle.enabled.value) return
    if (fromIndex !in 0 until mediaItemCount || toIndex !in 0 until mediaItemCount) return
    if (fromIndex == toIndex) return
    if (fromIndex <= currentMediaItemIndex) return
    ManualQueuePins.pin(getMediaItemAt(fromIndex).mediaId)
    moveMediaItem(fromIndex, toIndex)
}

/**
 * Removes both playback history still present in Media3's timeline and every
 * future entry, preserving only the item that is actually current.
 */
fun MediaController.clearQueueKeepingCurrent() {
    val current = currentMediaItemIndex
    if (current !in 0 until mediaItemCount) return
    if (mediaItemCount > current + 1) removeMediaItems(current + 1, mediaItemCount)
    if (current > 0) removeMediaItems(0, current)
    ManualQueuePins.clear()
    QueueShuffle.onQueueCleared()
}

/**
 * Ends playback and removes the complete timeline, including the current item.
 *
 * This is intentionally separate from [clearQueueKeepingCurrent]: the latter is
 * the queue-sheet action, while this destructive variant is reserved for the
 * MiniPlayer's explicit horizontal dismissal gesture. Removing the current
 * item also emits a playlist-change event, which makes an in-flight Automix
 * bail and tear down its standby deck instead of leaving a transition audible.
 */
fun MediaController.clearPlaybackQueue() {
    stop()
    clearMediaItems()
    ManualQueuePins.clear()
    QueueShuffle.onQueueCleared()
}

/**
 * Which track a playback URI is for, as a media id — the inverse of the URI
 * [toMediaItem] builds, as far as the identity goes.
 *
 * Needed because most of what this app does to a track happens somewhere that
 * has only the URI: the resolver runs on ExoPlayer's loader thread with a
 * DataSpec in hand, and read-ahead means the track being fetched is usually not
 * the one playing. That is what makes it the answer to "whose log line is this"
 * — see [com.music.orb.data.TrackLog.about].
 *
 * Deliberately not the cache key, which looks similar and is not the same
 * thing: that one splits a track's renditions apart on purpose and spells a
 * source-backed track differently again, so filing lines under it would scatter
 * one song's story across several names.
 */
fun mediaIdIn(uri: Uri): String? = if (uri.authority == "source") {
    val configId = uri.getQueryParameter("s")
    val trackId = uri.getQueryParameter("t")
    if (configId != null && trackId != null) SourceRegistry.trackKey(configId, trackId) else null
} else {
    uri.getQueryParameter("v")
}

private fun List<Song>.isOrderedAlbumQueue(): Boolean {
    if (size < 2) return false
    val ids = map { it.albumId?.trim().orEmpty() }
    // Album pages stamp their own browse id onto every row before play. Requiring
    // that explicit shared id avoids mistaking a playlist containing several
    // songs from one album for an authored album session.
    return ids.all { it.isNotEmpty() } && ids.distinct().size == 1
}

fun MediaController.playSongs(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    ManualQueuePins.clear()
    // Shuffle for a replacement queue is a one-shot launch intent, not an
    // inherited property of the queue that happened to be playing before it.
    // Normal Play therefore resets Shuffle; only an album/playlist Shuffle
    // button (enableForNextQueue) makes this new queue start shuffled.
    val shuffled = QueueShuffle.consumeForNewQueue()
    val queue = if (shuffled) QueueShuffle.startingOrder(songs, startIndex) else songs
    val orderedAlbum = !shuffled && queue.isOrderedAlbumQueue()
    setMediaItems(
        queue.map { it.toMediaItem(albumSequential = orderedAlbum) },
        if (shuffled) 0 else startIndex,
        0L,
    )
    if (shuffled) QueueShuffle.confirmActiveQueueShuffled()
    prepare()
    play()
}
