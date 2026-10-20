package com.music.orb.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.music.orb.data.DebugLog as Log
import com.music.orb.data.LocalAudioQuality
import com.music.orb.data.NerdStats
import com.music.orb.data.lyrics.LyricsOfflineStore
import com.music.orb.data.lyrics.LyricsRepository
import com.music.orb.data.settings.AppSettings
import androidx.core.content.ContextCompat
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.innertube.StreamResolver
import com.music.orb.data.model.Song
import com.music.orb.data.sources.DownloadQualityTarget
import com.music.orb.data.sources.LosslessTier
import com.music.orb.data.sources.SourceResolver
import com.music.orb.data.sources.SourceStream
import com.music.orb.data.sources.StreamFormat
import com.music.orb.data.sources.TrackMatcher
import com.music.orb.playback.PlaybackStreamStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.OutputStream

/** User-selected offline rendition; independent of the streaming network quality. */
enum class OfflineDownloadQuality { MEDIUM, HIGH, MAXIMUM }

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    /** Looking up the selected audio format before downloading any bytes. */
    data object Resolving : DownloadState

    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * The download queue, and the record of what came out of it.
 *
 * Split deliberately into two pieces of state that look similar and behave
 * nothing alike:
 *
 *  - [active] is what is happening now — queued, running, just failed. It lives
 *    in memory, is driven by [DownloadService], and is empty on a cold start
 *    because a download interrupted by the process dying did not happen.
 *  - [saved] is what exists on disk, keyed by videoId and remembered across
 *    launches. It is the only way the app can answer "do I already have this?"
 *    without a media-store query per row, and the only way it knows *which*
 *    file a track corresponds to when asked to delete it.
 *
 * [saved] points at Orb-managed offline files. Older installs may still carry
 * MediaStore URIs from the former public Music/BitChord location, so every read
 * still goes through [savedUri] and verifies the file before answering.
 */
object Downloads {

    private const val TAG = "Orb"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"

    private lateinit var prefs: SharedPreferences
    private lateinit var applicationContext: Context

    /** Local cover art is used even for a queue item originally opened online. */
    fun offlineArtworkUri(videoId: String): String? =
        if (::applicationContext.isInitialized && videoId in _saved.value) {
            OfflineArtworkStore.get(applicationContext, videoId)
        } else null

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())

    /** videoId to the uri of the file saved for it. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())

    @Volatile private var artworkUpgradeRunning = false

    /** Repairs old low-resolution artwork once, in the background, on validated Wi-Fi. */
    private fun refreshOldArtworkOnWifi() {
        if (!AppSettings.internetAvailable.value || AppSettings.wifiConnection.value != true) return
        synchronized(lock) {
            if (artworkUpgradeRunning) return
            artworkUpgradeRunning = true
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                _savedMetadata.value.values.asSequence()
                    .filter { meta -> !meta.thumbnailUrl.isNullOrBlank() }
                    .filter { meta -> OfflineArtworkStore.needsRefresh(applicationContext, meta.videoId) }
                    .take(15)
                    .forEach { meta ->
                        if (!AppSettings.internetAvailable.value || AppSettings.wifiConnection.value != true)
                            return@launch
                        runCatching {
                            OfflineArtworkStore.save(applicationContext,
                                listOf(meta.videoId), listOfNotNull(meta.thumbnailUrl))
                        }
                    }
            } finally { artworkUpgradeRunning = false }
        }
    }


    /**
     * Quality metadata recorded for an offline file. This is intentionally the
     * rendition that was actually stored, not the quality setting that was
     * requested, so offline badges can remain honest when a resolver had to
     * descend from Hi-Res to Lossless or from Lossless to AAC.
     */
    fun savedQuality(videoId: String): StreamFormat? {
        val meta = _savedMetadata.value[videoId] ?: return null
        val tier = meta.qualityLosslessTier
            ?.let { name -> runCatching { LosslessTier.valueOf(name) }.getOrNull() }
        if (meta.qualityCodec == null &&
            meta.qualityKbps == null &&
            meta.qualitySampleRateHz == null &&
            meta.qualityBitDepth == null &&
            tier == null
        ) return null
        return StreamFormat(
            codec = meta.qualityCodec,
            kbps = meta.qualityKbps,
            sampleRateHz = meta.qualitySampleRateHz,
            bitDepth = meta.qualityBitDepth,
            declaredLosslessTier = tier,
        )
    }

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Song>()

    /** Target and selected level captured before each download enters the queue. */
    private val pendingQualityTargets = HashMap<String, DownloadQualityTarget>()
    private val pendingQualityLevels = HashMap<String, OfflineDownloadQuality>()
    /** Captured per-track; changing Settings mid-playlist won't rewrite queued choices. */
    private val pendingAllowHighFallback = HashMap<String, Boolean>()


    private val lock = Any()

    @Volatile
    private var runningId: String? = null

    @Volatile
    private var runningJob: Job? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        _saved.value = runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_SAVED, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _savedMetadata.value = runCatching {
            json.decodeFromString(metadataSerializer, prefs.getString(KEY_SAVED_METADATA, null) ?: "{}")
        }.getOrDefault(emptyMap())
        // Older builds saved DASH FLAC as sequential fragments without an MP4
        // seek index. Repair only Orb-owned files on IO, never on the UI thread.
        val savedUris = _saved.value.values.distinct()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            savedUris.forEach { uriText ->
                DownloadStore.ensureSeekableOffline(applicationContext, Uri.parse(uriText))
            }
        }
    }

    // ---- Asking -------------------------------------------------------------

    /**
     * Queue [song], and make sure something is draining the queue.
     *
     * A track already saved, queued or running is left alone rather than
     * doubled — the menu row shows which of those it is, but a second tap
     * before the sheet updates should still be a no-op.
     */
    fun enqueue(
        context: Context,
        song: Song,
        quality: OfflineDownloadQuality = OfflineDownloadQuality.HIGH,
        allowLowerQuality: Boolean = AppSettings.downloadAllowLowerQuality.value,
    ) {
        val id = song.videoId
        // No Premium preview flags can authorize a maximum-quality file.
        if (quality == OfflineDownloadQuality.MAXIMUM && !AppSettings.premiumEntitled.value) {
            fail(id, "Orb Premium is required for maximum download quality")
            return
        }
        val qualityAtTap = if (quality == OfflineDownloadQuality.MAXIMUM && AppSettings.premiumEntitled.value) {
            PlaybackStreamStore.recent(id)?.let { stream ->
                playbackDownloadTarget(stream, listOf(id))
            }
        } else {
            null
        }
        synchronized(lock) {
            if (id in pending || id == runningId) return
            pending[id] = song
            pendingQualityLevels[id] = quality
            // Only the user's explicit preference permits AAC after Maximum.
            pendingAllowHighFallback[id] = allowLowerQuality
            if (qualityAtTap != null) pendingQualityTargets[id] = qualityAtTap
        }
        _active.value = _active.value + (id to DownloadState.Queued)

        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            // Refused only when the app has no window and no exemption, which
            // means the queue has nothing to drain it and would sit there
            // looking accepted forever.
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) {
                pending.remove(id)
                pendingQualityTargets.remove(id)
                pendingQualityLevels.remove(id)
                pendingAllowHighFallback.remove(id)
            }
            fail(id, "Downloads can't start right now")
        }
    }

    /**
     * Drop [videoId] from the queue, or stop it if it is the one running.
     *
     * Clearing [runningId] is what makes this safe in the gap between a track
     * being dequeued and its job existing: a cancel landing in that window
     * finds no job to stop, but [onRunning] then finds the id it was told to
     * run is no longer the one wanted, and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            pendingQualityTargets.remove(videoId)
            pendingQualityLevels.remove(videoId)
            pendingAllowHighFallback.remove(videoId)
            if (videoId != runningId) return@synchronized null
            runningId = null
            runningJob.also { runningJob = null }
        }
        job?.cancel()
        clear(videoId)
    }

    // ---- The record ---------------------------------------------------------

    /**
     * The file saved for [videoId], or null — pruning the record if the file
     * has been deleted from under it.
     *
     * Touches the filesystem, so call it off the main thread.
     */
    suspend fun savedUri(context: Context, videoId: String): Uri? = withContext(Dispatchers.IO) {
        val recorded = _saved.value[videoId] ?: return@withContext null
        val uri = recorded.toUri()
        if (DownloadStore.exists(context, uri) && DownloadStore.isPlayableAudio(context, uri)) {
            DownloadStore.ensureSeekableOffline(context, uri)
            return@withContext uri
        }
        Log.w(TAG, "$videoId points to a missing or invalid download; removing the stale file/record")
        DownloadStore.delete(context, uri)
        forgetUri(recorded)
        null
    }

    /** Delete the file saved for [videoId] and forget every alias that points to it. */
    suspend fun delete(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        val uriText = _saved.value[videoId] ?: return@withContext false
        val deleted = deleteBestEffort(context, uriText.toUri())
        if (deleted) forgetUri(uriText)
        deleted
    }

    /**
     * Delete a downloaded-library row even when it predates Orb's saved map.
     * The scanned [Song.localUri] is used only by the Downloads page host;
     * callers elsewhere never expose this operation for arbitrary local music.
     */
    suspend fun delete(context: Context, song: Song): Boolean = withContext(Dispatchers.IO) {
        val candidateUris = linkedSetOf<String>()
        _saved.value[song.videoId]?.let(candidateUris::add)
        song.localUri?.takeIf { it.isNotBlank() }?.let(candidateUris::add)
        song.localPath?.takeIf { it.isNotBlank() }?.let { candidateUris.add(Uri.fromFile(java.io.File(it)).toString()) }
        _savedMetadata.value.values
            .asSequence()
            .filter { meta ->
                meta.uri.isNotBlank() && (
                    meta.videoId == song.videoId ||
                    meta.uri == song.localUri ||
                    (meta.title.equals(song.title, ignoreCase = true) &&
                        meta.artist.equals(song.artist, ignoreCase = true))
                )
            }
            .map { it.uri }
            .forEach(candidateUris::add)

        val deletedUri = candidateUris.firstOrNull { uriText ->
            deleteBestEffort(context, uriText.toUri())
        } ?: return@withContext false

        candidateUris.forEach(::forgetUri)
        if (_saved.value.containsKey(song.videoId)) forget(song.videoId)
        deletedUri.let { removed ->
            val cleanedMeta = _savedMetadata.value.filterValues { meta ->
                meta.uri != removed && !(meta.title.equals(song.title, ignoreCase = true) &&
                    meta.artist.equals(song.artist, ignoreCase = true))
            }
            if (cleanedMeta.size != _savedMetadata.value.size) {
                record(_saved.value, cleanedMeta)
            }
        }
        true
    }

    /**
     * Cancels the whole queue and removes every completed Orb download we can
     * identify, then sweeps both the current app-managed folder and the legacy
     * public Music/BitChord folder for orphan files that are not in prefs.
     *
     * Saved state is cleared even when a stale URI is already gone, so the UI
     * cannot remain stuck showing a download that no longer exists.
     */
    suspend fun deleteAll(context: Context): Int = withContext(Dispatchers.IO) {
        val running = synchronized(lock) {
            pending.clear()
            pendingQualityTargets.clear()
            pendingQualityLevels.clear()
            pendingAllowHighFallback.clear()
            runningId = null
            runningJob.also { runningJob = null }
        }
        running?.cancel()
        _active.value = emptyMap()

        val recordedUris = (_saved.value.values + _savedMetadata.value.values.map { it.uri })
            .filter { it.isNotBlank() }
            .distinct()
        var removed = 0
        recordedUris.forEach { uriText ->
            if (deleteBestEffort(context, uriText.toUri())) removed++
        }

        // Sweep after the recorded URIs: this catches interrupted/orphaned new
        // downloads and all files left by the old public-storage implementation.
        removed += DownloadStore.deleteAllManaged(context)
        removed += DownloadStore.deleteAllLegacyPublic(context)

        record(emptyMap(), emptyMap())
        OfflinePlaylistStore.clear(context)
        OfflineAlbumStore.clear(context)
        OfflineArtworkStore.clear(context)
        LyricsOfflineStore.clear(context)
        removed
    }

    private fun deleteBestEffort(context: Context, uri: Uri): Boolean {
        if (DownloadStore.delete(context, uri)) return true

        if (uri.scheme == "file") {
            return uri.path?.let { java.io.File(it).delete() } == true
        }

        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use false
                    val name = cursor.getString(0) ?: return@use false
                    val relativePath = cursor.getString(1) ?: ""
                    context.contentResolver.query(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.Audio.Media._ID),
                        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(name, relativePath),
                        null,
                    )?.use { matches ->
                        while (matches.moveToNext()) {
                            val id = matches.getLong(0)
                            val candidate = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                id,
                            )
                            if (DownloadStore.delete(context, candidate)) return@use true
                        }
                        false
                    } == true
                } ?: false
            }.onFailure {
                Log.w(TAG, "best-effort delete fallback failed for $uri: ${it.message}")
            }.getOrDefault(false).let { if (it) return true }
        }

        return false
    }

    private fun forget(videoId: String) {
        record(_saved.value - videoId, _savedMetadata.value - videoId)
    }

    private fun forgetUri(uriText: String) {
        val orphanedIds = _saved.value.filterValues { it == uriText }.keys
        if (::applicationContext.isInitialized) {
            orphanedIds.forEach { id ->
                OfflineArtworkStore.delete(applicationContext, id)
                com.music.orb.data.lyrics.LyricsOfflineStore.delete(applicationContext, id)
            }
        }
        val saved = _saved.value.filterValues { it != uriText }
        val metadata = _savedMetadata.value.filterValues { it.uri != uriText }
        record(saved, metadata)
    }

    /**
     * Record one file under every id it could be asked about.
     *
     * [asked] is the row the user tapped and [fetched] is what was actually
     * downloaded, and for a music video those are two different tracks. Filing
     * it under both is what lets the same song, found later through search,
     * still know it is already on the device. A stale id costs nothing: the
     * verification in [savedUri] prunes whichever one stops resolving.
     */
    private fun remember(
        asked: Song,
        fetched: Song,
        uri: Uri,
        quality: StreamFormat? = null,
    ) {
        val ids = setOf(asked.videoId, fetched.videoId)
        val newSaved = _saved.value + ids.associateWith { uri.toString() }
        // Either row may be the one that knew the release: a music video is
        // swapped for the catalogue track before this, and it is the catalogue
        // row that usually carries the album — but a search hit tapped directly
        // is both, and an album page's rows are neither.
        val album = asked.albumName?.takeIf { it.isNotBlank() }
            ?: fetched.albumName?.takeIf { it.isNotBlank() }
        val metaAsked = SavedSongMetadata(
            videoId = asked.videoId,
            title = asked.title,
            artist = asked.artist,
            thumbnailUrl = asked.thumbnailUrl,
            durationText = asked.durationText,
            albumName = album,
            uri = uri.toString(),
            qualityCodec = quality?.codec,
            qualityKbps = quality?.kbps,
            qualitySampleRateHz = quality?.sampleRateHz,
            qualityBitDepth = quality?.bitDepth,
            qualityLosslessTier = quality?.losslessTier?.name,
        )
        val metaFetched = SavedSongMetadata(
            videoId = fetched.videoId,
            title = fetched.title,
            artist = fetched.artist,
            thumbnailUrl = fetched.thumbnailUrl,
            durationText = fetched.durationText,
            albumName = album,
            uri = uri.toString(),
            qualityCodec = quality?.codec,
            qualityKbps = quality?.kbps,
            qualitySampleRateHz = quality?.sampleRateHz,
            qualityBitDepth = quality?.bitDepth,
            qualityLosslessTier = quality?.losslessTier?.name,
        )
        val newMeta = _savedMetadata.value + mapOf(
            asked.videoId to metaAsked,
            fetched.videoId to metaFetched,
        )
        record(newSaved, newMeta)
    }

    private fun record(savedMap: Map<String, String>, metaMap: Map<String, SavedSongMetadata>) {
        // Publish metadata first: Home observes the saved-ID map to update badges.
        // Publishing saved first could cause a composition with stale quality info.
        _savedMetadata.value = metaMap
        _saved.value = savedMap
        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
                .putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
                .apply()
        }
    }

    /** Returns all downloaded songs whose files still exist on disk. */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        refreshOldArtworkOnWifi()
        val metaMap = _savedMetadata.value
        val result = mutableListOf<Song>()
        val seenUris = mutableSetOf<String>()

        for ((videoId, meta) in metaMap) {
            val uri = meta.uri.toUri()
            val valid = DownloadStore.exists(context, uri) && DownloadStore.isPlayableAudio(context, uri)
            if (valid) {
                if (seenUris.add(meta.uri)) {
                    result.add(
                        Song(
                            videoId = meta.videoId,
                            title = meta.title,
                            artist = meta.artist,
                            thumbnailUrl = offlineArtworkUri(meta.videoId) ?: meta.thumbnailUrl,
                            durationText = meta.durationText,
                            albumName = meta.albumName,
                            localUri = meta.uri,
                        )
                    )
                }
            } else {
                Log.w(TAG, "discarding invalid offline file for $videoId")
                DownloadStore.delete(context, uri)
                forgetUri(meta.uri)
            }
        }
        result
    }

    private fun String?.toDurationMs(): Long {
        val parts = this?.trim()?.split(":") ?: return 0L
        if (parts.size !in 2..3) return 0L
        val values = parts.map { it.toLongOrNull() ?: return 0L }
        val seconds = if (parts.size == 2) values[0] * 60L + values[1]
        else values[0] * 3600L + values[1] * 60L + values[2]
        return seconds * 1000L
    }

    private fun String.toUri(): Uri = Uri.parse(this)

    // ---- Driven by DownloadService -----------------------------------------

    /**
     * The next track to fetch, or null when the queue is empty.
     *
     * Claims it as running under the same lock that removed it, so there is no
     * instant where a track is in neither the queue nor the running slot and a
     * [cancel] for it would quietly do nothing.
     */
    internal fun takeNext(): Song? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        runningId = entry.key
        entry.value
    }

    /** Attach the job fetching [videoId], unless it has been cancelled meanwhile. */
    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (runningId != videoId) return@synchronized true
            runningJob = job
            false
        }
        if (cancelled) job.cancel()
    }

    internal fun onIdle() {
        synchronized(lock) {
            runningId = null
            runningJob = null
        }
    }

    /**
     * Fetch one track, start to finish.
     *
     * Everything that can go wrong past the point of reserving a destination
     * has to unreserve it — a cancelled or failed download must not leave a
     * partial file behind pretending to be a whole one, which is what
     * [DownloadStore.Pending] exists to make hard to get wrong.
     *
     * Pinned to [Dispatchers.IO] here rather than trusted to arrive on it.
     * Resolving a stream blocks on HTTP and runs YouTube's player JavaScript
     * through Rhino, and [DownloadService] drives this from a main-thread scope
     * so its notification work stays where it belongs — inheriting that would
     * put every network call in the resolve on the main thread, where they
     * don't fail loudly so much as fail *uniformly*: `NetworkOnMainThreadException`
     * is caught by the same per-client `runCatching` that exists to tolerate a
     * client being turned away, so every client appears to be refused and the
     * whole thing reads as a network outage.
     */
    private data class DownloadAttempt(
        val quality: OfflineDownloadQuality,
        /** The two Lossless searches are independent; neither permits AAC. */
        val losslessPreference: LosslessTier = LosslessTier.HI_RES_LOSSLESS,
        val usePlaybackStream: Boolean = false,
    )

    /**
     * Resolve a requested rendition before fetching bytes. If lookup, transfer or
     * native-file verification fails, refresh the source and descend one tier.
     * This is also the path used by every song inside albums/playlists.
     */
    internal suspend fun run(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val id = song.videoId
        val (qualityAtTap, requestedLevel, allowHighFallback) = synchronized(lock) {
            Triple(
                pendingQualityTargets.remove(id),
                pendingQualityLevels.remove(id) ?: OfflineDownloadQuality.HIGH,
                pendingAllowHighFallback.remove(id) ?: AppSettings.downloadAllowLowerQuality.value,
            )
        }

        val track = try {
            YtMusicRepository.resolveAudio(song)
        } catch (e: CancellationException) {
            clear(id)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "catalogue metadata lookup failed for $id: ${e.message}")
            song
        }
        // Playback can provide the same verified FLAC that is already audible.
        // Only retry Maximum with a fresh source if that playback-specific route
        // actually existed; otherwise the first lookup would be repeated.
        val hasVerifiedHiResPlayback = sequenceOf(song.videoId, track.videoId)
            .distinct()
            .mapNotNull(PlaybackStreamStore::recent)
            .any { it.format.losslessTier == LosslessTier.HI_RES_LOSSLESS && it.losslessVerified }
        // The preference is captured when the user taps Download. A Maximum
        // download accepts BOTH native lossless tiers, in order, but never
        // permits AAC without the separate, explicit fallback switch.
        val attempts = DownloadQualityPolicy.attempts(
            requestedLevel = requestedLevel,
            allowLowerQuality = allowHighFallback,
            hasVerifiedHiResPlayback = hasVerifiedHiResPlayback,
        ).map { step ->
            DownloadAttempt(step.quality, step.losslessPreference, step.usePlaybackStream)
        }
        var lastError: Exception? = null
        for ((attemptIndex, attempt) in attempts.withIndex()) {
            _active.value = _active.value + (id to DownloadState.Resolving)
            var pending: DownloadStore.Pending? = null
            try {
                val route = routeFor(
                    track = track,
                    askedVideoId = id,
                    qualityAtTap = if (attempt.usePlaybackStream) qualityAtTap else null,
                    requestedLevel = attempt.quality,
                    // Each attempt is a single explicit tier. The worker, not
                    // the resolver, controls fallback ordering and permission.
                    allowLowerQuality = false,
                    allowPlaybackStream = attempt.usePlaybackStream,
                    losslessPreference = attempt.losslessPreference,
                )
                Log.d(TAG, "downloading $id as .${route.extension} (${route.describe})")
                _active.value = _active.value + (id to DownloadState.Running(0f))

                val name = DownloadStore.fileNameFor(track, route.extension)
                // Already there from a previous run the record lost track of —
                // adopt it rather than writing a second copy beside it.
                val alreadyThere = DownloadStore.existing(context, name)
                if (alreadyThere != null) {
                    DownloadStore.ensureSeekableOffline(context, alreadyThere)
                    val nativeExisting = LocalAudioQuality.inspect(
                        context = context,
                        uriString = alreadyThere.toString(),
                        localPath = null,
                    )
                    if (existingFileSatisfies(route.format, nativeExisting)) {
                        Log.d(TAG, "$name is already in Orb offline storage; adopting it")
                        runCatching {
                            OfflineArtworkStore.save(
                                context, listOf(song.videoId, track.videoId),
                                listOfNotNull(track.thumbnailUrl, song.thumbnailUrl),
                            )
                        }
                        remember(song, track, alreadyThere, nativeExisting ?: route.format)
                        clear(id)
                        return@withContext
                    }
                    // Same filename can legitimately represent different lossless tiers. A 16/44.1
                    // FLAC left by an older download must not block a Premium 24/96 re-download.
                    Log.d(
                        TAG,
                        "$name exists below requested ${route.format.summary}; replacing it",
                    )
                    // Keep the previous file until the new one has been fully
                    // downloaded and verified; Pending will back it up.
                }

                val destination = DownloadStore.begin(context, name, route.mimeType)
                pending = destination
                destination.openStream().use { sink ->
                    route.write(sink) { written, total ->
                        _active.value = _active.value +
                            (id to DownloadState.Running(written.toFloat() / total))
                    }
                }
                if (route.extension == "m4a" && route.format.isLossless == true) {
                    // Streaming FLAC fragments are not automatically seekable once
                    // concatenated. Publish only a complete file with an sidx.
                    check(destination.addFragmentIndex()) {
                        "Offline Lossless MP4 has no usable seek index"
                    }
                }
                val savedUri = destination.commit()
                // Keep pending until verification has finished: abort() can
                // restore an earlier good download if the new bytes are bad.

                // Never publish a file merely because every byte arrived. The
                // playback stream and the offline container are separate concerns:
                // a transport can be perfectly playable while streaming and still
                // not be a standalone file Android can reopen. Verify the raw file,
                // tag it, then verify once more because metadata rewriting must not
                // be allowed to turn a good download into a broken one.
                if (!DownloadStore.isPlayableAudio(context, savedUri)) {
                    DownloadStore.delete(context, savedUri)
                    error("The downloaded audio container is invalid — try again")
                }
                // Fragmented MP4 contains native FLAC: generic MP4 tag rewriting can
                // corrupt its fragment offsets. Title/artist/art are already kept
                // in Orb's offline metadata and artwork sidecar.
                if (!(route.extension == "m4a" && route.format.isLossless == true)) {
                    MediaTagger.embed(context, savedUri, track, route.extension)
                }
                // Durable sidecar cover: the metadata URL cannot be loaded without Internet.
                runCatching {
                    OfflineArtworkStore.save(
                        context, listOf(song.videoId, track.videoId),
                        listOfNotNull(track.thumbnailUrl, song.thumbnailUrl),
                    )
                }.onFailure { Log.w(TAG, "could not store offline cover for $id: ${it.message}") }

                // Premium downloads also persist the exact lyrics winner selected
                // by the same global resolver used by the online player.
                if (AppSettings.premiumEntitled.value) {
                    runCatching {
                        val durationMs = track.durationText.toDurationMs()
                            .takeIf { it > 0L }
                            ?: song.durationText.toDurationMs().takeIf { it > 0L }
                            ?: runCatching {
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(context, savedUri)
                                    retriever.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                                    )?.toLongOrNull()
                                } finally { retriever.release() }
                            }.getOrNull()
                            ?: 0L
                        if (durationMs > 0L) {
                            LyricsRepository.synchronizedLyrics(
                                videoId = track.videoId,
                                title = track.title,
                                artist = track.artist,
                                durationMs = durationMs,
                                album = track.albumName,
                            )?.let { lyrics ->
                                LyricsOfflineStore.put(context, track.videoId, lyrics)
                                if (song.videoId != track.videoId) {
                                    LyricsOfflineStore.put(context, song.videoId, lyrics)
                                }
                            }
                        }
                    }.onFailure {
                        Log.w(TAG, "could not save offline lyrics for ${track.videoId}: ${it.message}")
                    }
                }
                if (!DownloadStore.isPlayableAudio(context, savedUri, force = true)) {
                    DownloadStore.delete(context, savedUri)
                    error("The downloaded file could not be verified — try again")
                }

                // For Lossless/Hi-Res, verify the native file header after the bytes and tags are
                // final. A source declaration is useful for choosing a candidate, but the offline
                // badge/metadata must describe the file actually stored on disk. If a source said
                // 24/96 and delivered 16/44.1, reject it rather than persisting a false Hi-Res copy.
                val verifiedOfflineQuality = if (route.format.isLossless == true) {
                    LocalAudioQuality.inspect(
                        context = context,
                        uriString = savedUri.toString(),
                        localPath = null,
                    )
                } else {
                    null
                }
                if (route.format.isLossless == true) {
                    Log.d(TAG, "offline FLAC verification $id: source=${route.format.summary} " +
                        "native=${verifiedOfflineQuality?.summary ?: "unknown"} " +
                        "nativeTier=${verifiedOfflineQuality?.losslessTier ?: "unknown"} " +
                        "extension=${route.extension}")
                    // A file the platform recognises as native FLAC is Lossless
                    // even when MediaExtractor omits bits-per-sample. Reject an
                    // unverified codec, never a verified FLAC merely because
                    // optional sample-rate/depth fields are absent or lower than
                    // the provider's advertised Hi-Res value. The persisted badge
                    // uses only native values, so there is no false Hi-Res label.
                    if (!OfflineLosslessVerification.accepts(route.format, verifiedOfflineQuality)) {
                        DownloadStore.delete(context, savedUri)
                        error(applicationContext.getString(com.music.orb.R.string.orb_download_lossless_verify_failed))
                    }
                    // A successful Hi-Res search must deliver *native* Hi-Res.
                    // A provider can advertise 24-bit yet send 16/44.1; do not
                    // finish the queue here, because another instance may still
                    // offer true Hi-Res. Abort/restore this file and let the
                    // explicit Lossless attempt run only after Hi-Res fails.
                    if (attempt.quality == OfflineDownloadQuality.MAXIMUM &&
                        attempt.losslessPreference == LosslessTier.HI_RES_LOSSLESS &&
                        verifiedOfflineQuality?.losslessTier != LosslessTier.HI_RES_LOSSLESS
                    ) {
                        Log.w(TAG, "Maximum download $id: Hi-Res attempt produced " +
                            "${verifiedOfflineQuality?.summary ?: "unverified FLAC"}; trying next source/tier")
                        error("Hi-Res not verified in downloaded audio; retrying Lossless")
                    }
                    if (!existingFileSatisfies(route.format, verifiedOfflineQuality)) {
                        Log.w(TAG, "offline FLAC source quality differs from native file for $id: " +
                            "source=${route.format.summary}; native=${verifiedOfflineQuality?.summary ?: "unknown"}; " +
                            "keeping actual native tier ${verifiedOfflineQuality?.losslessTier ?: "unknown"}")
                    }
                }

                destination.finish()
                pending = null
                remember(song, track, savedUri, verifiedOfflineQuality ?: route.format)
                clear(id)
                Log.d(TAG, "saved $name")
                // Stop after the first valid rendition. In particular, never
                // overwrite a successful Lossless file with a lower tier.
                return@withContext
            } catch (e: CancellationException) {
                pending?.abort()
                clear(id)
                throw e
            } catch (e: Exception) {
                pending?.abort()
                lastError = e
                Log.w(TAG,
                    "offline attempt ${attemptIndex + 1}/${attempts.size} " +
                        "(${attempt.quality}, playback=${attempt.usePlaybackStream}) failed for $id: ${e.message}", e)
            }
        }
        fail(id, lastError?.friendly() ?: "Nenhuma versão disponível para download")
    }

    /**
     * One resolved download: what to call the file, what to tell the store it
     * is, and how to fill it.
     *
     * Exists so [run] has one linear body rather than two nearly-identical
     * ones. Everything after the bytes are chosen — the duplicate check, the
     * pending row, the commit, the tagging, the abort on failure — is the same
     * work whichever server the audio came from; the route carries container,
     * verified quality, provenance and the writer as one decision.
     */
    private class Route(
        val extension: String,
        val mimeType: String,
        /** Actual rendition selected for the offline file. */
        val format: StreamFormat,
        /** For the log line, so a download's provenance is on the record. */
        val describe: String,
        val write: suspend (OutputStream, (written: Long, total: Long) -> Unit) -> Unit,
    )

    /**
     * Where this download's bytes are coming from.
     *
     * A configured source gets asked first, and YouTube is what happens when
     * none of them can serve it — see [SourceResolver.forDownload] for what
     * "can" means, which is narrower here than it is for playback.
     */
    private suspend fun routeFor(
        track: Song,
        askedVideoId: String,
        qualityAtTap: DownloadQualityTarget? = null,
        requestedLevel: OfflineDownloadQuality = OfflineDownloadQuality.HIGH,
        allowLowerQuality: Boolean = true,
        allowPlaybackStream: Boolean = true,
        losslessPreference: LosslessTier = LosslessTier.HI_RES_LOSSLESS,
    ): Route {
        if (requestedLevel != OfflineDownloadQuality.MAXIMUM) {
            val targetKbps = if (requestedLevel == OfflineDownloadQuality.MEDIUM) 128 else 320
            val target = DownloadQualityTarget(
                codec = "aac", kbps = targetKbps, losslessTier = LosslessTier.NONE,
            )
            // Only search for the rendition that the listener selected. When a
            // lower rendition is permitted, decide that explicitly below rather
            // than letting the AAC resolver silently accept a different tier.
            val lowestSelectedKbps = if (requestedLevel == OfflineDownloadQuality.HIGH) 192 else 96
            bestSourceDownload(track, target)?.takeIf { (stream, _) ->
                val bitrate = stream.format.kbps ?: 0
                stream.format.isLossless != true &&
                    bitrate in lowestSelectedKbps..(targetKbps + 32)
            }?.let { (stream, storable) ->
                return directSourceRoute(stream, storable,
                    "${stream.format.summary} (selected ${requestedLevel.name})")
            }
            val selected = try {
                StreamResolver.resolveForDownload(track.videoId, targetKbps)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "selected download source lookup failed: ${failure.message}")
                null
            }
            if (selected != null && selected.kbps in lowestSelectedKbps..(targetKbps + 32)) {
                return youtubeRoute(track.videoId, selected, targetKbps,
                    targetDescription = requestedLevel.name)
            }
            // No recursive downgrade. The queue's explicit policy decides if
            // a different quality can be attempted after this tier fails.
            // Even a resolver that returns a playable 128 kbps AAC must not
            // silently satisfy a request for High (>=192 kbps).
            error("The requested download quality is unavailable for this track")
        }
        check(AppSettings.premiumEntitled.value) {
            "Orb Premium is required for maximum download quality"
        }
        val playbackIds = sequenceOf(askedVideoId, track.videoId)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val playback = if (allowPlaybackStream) {
            playbackIds.asSequence().mapNotNull(PlaybackStreamStore::recent).firstOrNull()
        } else null

        // Premium follows the rendition the listener actually reached. The target is captured
        // from the remembered stream and, when this is the currently playing item, augmented by
        // the decoder/native-quality evidence behind Stats for Nerds. Crucially, this does not
        // call SourceResolver.requestForNow(): Wi-Fi/mobile settings are a playback ceiling, not
        // permission to silently change an already-chosen download tier.
        val premiumTarget = if (AppSettings.premiumEntitled.value) {
            // "Maximum" always searches the best native lossless rendition;
            // it never copies the (possibly lower) playback quality setting.
            DownloadQualityTarget(codec = "flac", losslessTier = losslessPreference)
        } else {
            null
        }
        if (premiumTarget != null) {
            val wanted = premiumTarget
            if (wanted.isLossless) {
                if (allowPlaybackStream) Log.d(TAG,
                    "Maximum playback candidate: nativeTarget=${wanted.summary}, " +
                        "capturedAtTap=${qualityAtTap?.summary ?: "unknown"}")
                // When the listener is already hearing a verified lossless/hi-res
                // rendition, prefer saving that exact copy first. This avoids
                // making the user search again for bytes Orb has already proved.
                playback?.takeIf { stream ->
                    stream.format.isLossless == true && stream.losslessVerified &&
                        // A previous 16-bit Lossless playback is NOT the best
                        // Maximum rendition when a Hi-Res search is possible.
                        (wanted.losslessTier != LosslessTier.HI_RES_LOSSLESS ||
                            stream.format.losslessTier == LosslessTier.HI_RES_LOSSLESS) &&
                        (stream.isDash || stream.format.codec.equals("flac", ignoreCase = true))
                }?.let { stream ->
                    DownloadStore.storable(stream.format.codec)?.let { storable ->
                        val fallback = when {
                            wanted.losslessTier == LosslessTier.HI_RES_LOSSLESS &&
                                stream.format.losslessTier != LosslessTier.HI_RES_LOSSLESS ->
                                " · fallback Lossless"
                            else -> ""
                        }
                        Log.d(TAG, "Maximum download: reusing verified playback stream for ${track.videoId}")
                        return directSourceRoute(
                            stream = stream,
                            storable = storable,
                            description = "${stream.format.summary} (verified playback source$fallback)",
                        )
                    }
                }

                // Otherwise resolve the selected quality independently when Download is
                // confirmed, so songs that were never played can still find a real FLAC.
                Log.d(TAG, "Maximum download: resolving FLAC independently for ${track.videoId}")
                bestSourceDownload(track, wanted)
                    ?.takeIf { (stream, _) ->
                        stream.format.isLossless == true &&
                            (wanted.losslessTier != LosslessTier.HI_RES_LOSSLESS ||
                                stream.format.losslessTier == LosslessTier.HI_RES_LOSSLESS)
                    }
                    ?.let { (stream, storable) ->
                    val fallback = when {
                        wanted.losslessTier == LosslessTier.HI_RES_LOSSLESS &&
                            stream.format.losslessTier != LosslessTier.HI_RES_LOSSLESS ->
                            " · fallback Lossless"
                        else -> ""
                    }
                    return directSourceRoute(
                        stream = stream,
                        storable = storable,
                        description = "${stream.format.summary} (target ${wanted.summary}$fallback)",
                    )
                }

                // No codec downgrade here. Maximum remains Lossless even when
                // all source lookups fail; only the caller may try High when
                // the user explicitly enabled that preference.
                Log.w(TAG, "Maximum download: no verified ${wanted.losslessTier} source for ${track.videoId}")
                error(applicationContext.getString(com.music.orb.R.string.orb_download_maximum_unavailable))
            }

            // `playback` can legitimately be null when the Premium target was
            // captured at tap time and the recent stream entry has already expired.
            // In that case keep the explicit target; if it has no bitrate either,
            // let StreamResolver choose its normal downloadable fallback.
            val targetKbps = wanted.kbps ?: playback?.format?.kbps
            bestSourceDownload(track, wanted)?.let { (stream, storable) ->
                return directSourceRoute(
                    stream = stream,
                    storable = storable,
                    description = "${stream.format.summary} (target ${wanted.summary} playback quality)",
                )
            }
            val stream = StreamResolver.resolveForDownload(track.videoId, targetKbps)
            return youtubeRoute(
                videoId = track.videoId,
                stream = stream,
                targetKbps = targetKbps,
                targetDescription = "playback quality",
            )
        }

        // Non-Premium behaviour stays backwards-compatible. Preserve the quality hint already
        // proved by playback, but resolve a fresh standalone file rather than copying a possibly
        // fragmented/signed playback transport.
        if (playback != null) {
            if (playback.format.isLossless == true) {
                bestSourceDownload(track)?.let { (stream, storable) ->
                    return directSourceRoute(stream, storable, "${stream.format.summary} (playback quality)")
                }
            }

            val targetKbps = playback.format.kbps
            if (targetKbps != null || playback.format.codec != null) {
                val stream = StreamResolver.resolveForDownload(track.videoId, targetKbps)
                return youtubeRoute(track.videoId, stream, targetKbps)
            }
        }

        // No trustworthy playback quality is known (for example, a download
        // requested before the first stream resolved). Keep the established
        // behaviour: prefer a downloadable lossless copy, then YouTube AAC.
        bestSourceDownload(track)?.let { (stream, storable) ->
            return directSourceRoute(stream, storable, stream.format.summary)
        }
        val stream = StreamResolver.resolveForDownload(track.videoId)
        return youtubeRoute(track.videoId, stream, targetKbps = null)
    }

    private fun playbackDownloadTarget(
        playback: SourceStream,
        playbackIds: Collection<String>,
    ): DownloadQualityTarget {
        val snapshot = NerdStats.current.value?.takeIf { snap ->
            snap.mediaId != null && snap.mediaId in playbackIds
        }
        val streamFormat = playback.format

        if (snapshot?.isLossless == true || streamFormat.isLossless == true) {
            val native = snapshot?.nativeFormat?.takeIf { it.isLossless == true } ?: streamFormat
            val tier = when {
                snapshot?.isHiRes == true -> LosslessTier.HI_RES_LOSSLESS
                native.losslessTier.isLossless -> native.losslessTier
                else -> LosslessTier.LOSSLESS
            }
            return DownloadQualityTarget(
                codec = native.codec ?: streamFormat.codec,
                sampleRateHz = native.sampleRateHz ?: streamFormat.sampleRateHz,
                bitDepth = native.bitDepth ?: streamFormat.bitDepth,
                losslessTier = tier,
            )
        }

        return DownloadQualityTarget(
            codec = streamFormat.codec ?: snapshot?.mimeType?.toDownloadCodec(),
            kbps = snapshot?.verifiedBitrateKbps
                ?: snapshot?.bitrateKbps
                ?: streamFormat.kbps,
            sampleRateHz = streamFormat.sampleRateHz,
            bitDepth = streamFormat.bitDepth,
            losslessTier = LosslessTier.NONE,
        )
    }

    private fun String.toDownloadCodec(): String? {
        val mime = lowercase()
        return when {
            "flac" in mime -> "flac"
            "alac" in mime -> "alac"
            "mp4a" in mime || "aac" in mime -> "aac"
            "opus" in mime -> "opus"
            "mpeg" in mime || "mp3" in mime -> "mp3"
            else -> null
        }
    }

    /**
     * A pre-existing file can be reused only when it really satisfies the route we just chose.
     * This matters for FLAC because 16/44.1 and 24/96 share the same filename extension.
     */
    private fun existingFileSatisfies(requested: StreamFormat, existing: StreamFormat?): Boolean {
        if (requested.isLossless != true) {
            // Do not adopt a low-bitrate file as a requested High/Medium copy.
            requested.kbps?.let { wanted ->
                if (existing?.kbps == null || existing.kbps + 32 < wanted) return false
            }
            return true
        }
        if (existing?.isLossless != true) return false
        if (requested.losslessTier == LosslessTier.HI_RES_LOSSLESS &&
            existing.losslessTier != LosslessTier.HI_RES_LOSSLESS
        ) return false
        requested.bitDepth?.let { wanted ->
            if ((existing.bitDepth ?: 0) < wanted) return false
        }
        requested.sampleRateHz?.let { wanted ->
            if ((existing.sampleRateHz ?: 0) < wanted) return false
        }
        return true
    }

    private fun directSourceRoute(
        stream: SourceStream,
        storable: DownloadStore.Storable,
        description: String,
    ): Route = Route(
        // DASH wraps FLAC frames in fragmented MP4; these bytes are never a
        // native .flac file, even though the audio codec remains lossless FLAC.
        extension = if (stream.isDash) "m4a" else storable.extension,
        mimeType = if (stream.isDash) "audio/mp4" else storable.mimeType,
        format = stream.format,
        describe = description,
        write = { sink, onProgress ->
            if (stream.isDash && stream.losslessVerified && stream.format.isLossless == true) {
                DashFlacDownloader.fetch(stream.url, stream.headers, sink, onProgress)
            } else {
                Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
            }
        },
    )

    private fun youtubeRoute(
        videoId: String,
        stream: StreamResolver.Stream,
        targetKbps: Int?,
        targetDescription: String? = null,
    ): Route = Route(
        extension = stream.downloadExtension,
        mimeType = stream.downloadMimeType,
        format = StreamFormat(
            codec = "aac",
            kbps = stream.kbps,
        ),
        describe = buildString {
            append("${stream.kbps}kbps ${stream.mimeType}")
            if (targetKbps != null) {
                append(" (target ${targetKbps}kbps")
                targetDescription?.let { append(" · $it") }
                append(")")
            } else if (targetDescription != null) {
                append(" ($targetDescription)")
            }
        },
        write = { sink, onProgress -> Downloader.fetch(videoId, stream, sink, onProgress) },
    )

    /**
     * The best cross-source standalone stream to keep for [track], with how to
     * file it — or null, which is not a failure, just YouTube's turn. With an
     * explicit Premium [qualityTarget], the source resolver preserves that tier
     * independently of the current network ceiling.
     *
     * Bounded, because a module search waits on every backend it has (see
     * `ModuleSource.SEARCH_PATIENT_MS`) and does that once per query the matcher
     * offers. For a track no module holds, that is the whole queue stopped for
     * the better part of a minute on the way to a download that was always
     * going to be YouTube's. `PlaybackService.SUBSTITUTE_TIMEOUT_MS` bounds the
     * same search for the same reason.
     *
     * The [DownloadStore.storable] check belongs here rather than inside the
     * resolver: the resolver's job is finding the best audio, and whether this
     * device will keep a file of that codec is a question about Android.
     */
    private suspend fun bestSourceDownload(
        track: Song,
        qualityTarget: DownloadQualityTarget? = null,
    ): Pair<SourceStream, DownloadStore.Storable>? {
        val lookupMs = if (qualityTarget?.isLossless == false) {
            HI_QUALITY_LOOKUP_MS
        } else {
            LOSSLESS_LOOKUP_MS
        }
        var target = TrackMatcher.targetOf(track)
        if (qualityTarget?.isLossless == true) {
            // Watch/list and compact cards can provide just an id, without an
            // artist or canonical song title. Streaming enriches that metadata
            // on play; downloads must perform the equivalent work on their own.
            if (target.title.isBlank() || target.artist.isBlank() ||
                target.durationSec == null || target.albumName.isNullOrBlank()
            ) {
                val detailed = withTimeoutOrNull(12_000L) {
                    YtMusicRepository.trackLinks(track.videoId).getOrNull()
                }
                if (detailed != null) {
                    target = target.copy(
                        title = target.title.ifBlank { detailed.title },
                        artist = target.artist.ifBlank { detailed.artist },
                        durationSec = target.durationSec ?: TrackMatcher.secondsOf(detailed.durationText),
                        albumName = target.albumName?.takeIf(String::isNotBlank) ?: detailed.albumName,
                        releaseYear = target.releaseYear ?: detailed.releaseYear,
                    )
                }
            }
            Log.d(TAG, "Maximum search prepared for ${track.videoId}: " +
                "titlePresent=${target.title.isNotBlank()}, " +
                "artistPresent=${target.artist.isNotBlank()}, " +
                "durationPresent=${target.durationSec != null}")
        }
        val stream = withTimeoutOrNull(lookupMs) {
            try {
                SourceResolver.forDownload(
                    target = target,
                    qualityTarget = qualityTarget,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source download lookup failed for ${track.videoId}: ${e.message}")
                null
            }
        } ?: return null

        val path = runCatching { Uri.parse(stream.url).path.orEmpty() }.getOrDefault("")
        if ((stream.isDash || path.endsWith(".mpd", ignoreCase = true)) &&
            !(stream.isDash && stream.losslessVerified && stream.format.isLossless == true &&
                stream.format.codec.equals("flac", ignoreCase = true)) ||
            path.endsWith(".m3u8", ignoreCase = true)
        ) {
            Log.d(TAG, "unverified/adaptive source is not an offline FLAC for ${track.videoId}")
            return null
        }

        val storable = DownloadStore.storable(stream.format.codec)
        if (storable == null) {
            Log.d(TAG, "nothing to file a '${stream.format.codec}' as; taking YouTube for ${track.videoId}")
            return null
        }
        return stream to storable
    }

    /** Back to "not downloaded" — used for success, where [saved] takes over, and for cancellation. */
    private fun clear(videoId: String) {
        _active.value = _active.value - videoId
    }

    private fun fail(videoId: String, reason: String) {
        _active.value = _active.value + (videoId to DownloadState.Failed(reason))
    }

    /**
     * A failure a user can read. The message on an [error] raised in this
     * package is already written for them; anything else is a network fault
     * with a class name for a message.
     *
     * [IllegalArgumentException] is in here because of one that wasn't: the
     * media store throws it for a MIME type it won't accept, and for a while
     * every download on this device failed that way and reported itself as a
     * connection problem. The message it carries is not written for a user, but
     * `Unsupported MIME type audio/webm` at least sends someone looking in the
     * right direction.
     */
    private fun Exception.friendly(): String = when {
        (this is IllegalStateException || this is IllegalArgumentException) &&
            !message.isNullOrBlank() -> message!!
        else -> "Download failed — check your connection"
    }

    /** Premium Maximum is allowed a longer bounded search for a standalone FLAC.
     * If no verified lossless source exists, the download fails instead of
     * silently storing AAC at a quality the listener did not choose.
     */
    // Downloads are patient background jobs, unlike latency-sensitive first-note
    // playback. Let the shared upgrade matcher check all sources and release IDs.
    private const val LOSSLESS_LOOKUP_MS = 180_000L
    private const val HI_QUALITY_LOOKUP_MS = 8_000L
    private const val PREMIUM_LOSSLESS_FALLBACK_KBPS = 320

    /** Dropped when the sheet is reopened; a failure is worth showing once. */
    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

    private const val KEY_SAVED = "downloaded_tracks"
}

@kotlinx.serialization.Serializable
internal data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    /**
     * What release this track is off, when the row it was downloaded from knew.
     *
     * Added after the fact and defaulted, so a record written before it existed
     * still decodes — those entries come back with a null album and are filled
     * in from the file's own tags instead, see LocalMediaRepository.
     */
    val albumName: String? = null,
    val uri: String,
    /** Actual offline rendition, persisted so a future UI never has to guess from a badge. */
    val qualityCodec: String? = null,
    val qualityKbps: Int? = null,
    val qualitySampleRateHz: Int? = null,
    val qualityBitDepth: Int? = null,
    val qualityLosslessTier: String? = null,
)
