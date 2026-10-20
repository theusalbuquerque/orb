package com.music.orb.playback

import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.music.orb.ui.lockscreen.OrbLockscreenActivity
import com.music.orb.data.settings.OrbLockDisplayMode
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.orb.MainActivity
import com.music.orb.R
import com.music.orb.data.Http
import com.music.orb.data.LocalAudioQuality
import com.music.orb.data.NerdStats
import com.music.orb.data.TrackLog
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.discord.DiscordRPC
import com.music.orb.data.innertube.PlaybackTracker
import com.music.orb.data.innertube.PlayerClient
import com.music.orb.data.innertube.StreamResolver
import com.music.orb.data.model.Song
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.data.settings.ArtistPreferenceStore
import com.music.orb.data.settings.ArtistPreference
import com.music.orb.data.settings.AlbumExclusionStore
import com.music.orb.data.settings.LikeStatusStore
import com.music.orb.data.model.LikeStatus
import java.text.Normalizer
import java.util.Locale
import com.music.orb.data.scrobbling.LastfmScrobbleQueue
import com.music.orb.data.scrobbling.ListenBrainzManager
import com.music.orb.data.scrobbling.ScrobbleManager
import com.music.orb.data.social.SocialPlaybackReporter
import com.music.orb.data.social.SocialRepository
import com.music.orb.handoff.OrbTouchTransfer
import com.music.orb.ui.components.OrbHaptics
import com.music.orb.handoff.TouchPlaybackSnapshot
import com.music.orb.handoff.toTouchSong
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AutomixVersion
import com.music.orb.data.sources.LosslessTier
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.SourceResolver
import com.music.orb.data.sources.SourceStream
import com.music.orb.data.sources.hasPlayableHttpUrl
import com.music.orb.data.sources.isPlayableHttpStreamUrl
import com.music.orb.data.sources.streamOriginForLog
import com.music.orb.data.sources.StreamFormat
import com.music.orb.data.sources.StreamRequest
import com.music.orb.data.sources.TrackMatcher
import com.music.orb.playback.smart.RemoteAutomixClient
import com.music.orb.playback.smart.automixQueueCompatibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException

/** Past this point in a track, back restarts it instead of skipping to the previous one. */
const val BACK_RESTARTS_AFTER_MS = 10_000L

/**
 * Background playback via Media3. A [MediaSessionService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry a `orb://watch?v=<videoId>` URI. The actual stream
 * URL is resolved lazily by [ResolvingDataSource] the moment ExoPlayer opens
 * the item — stream URLs expire after a few hours, so resolving at play time
 * (on Media3's loader thread, hence runBlocking is safe) keeps queues valid.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
private const val LIBRARY_ROOT_ID = "orb:media:library:queue"

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var preserveLastQueueSnapshotOnDestroy = false

    /** The watch obtains album art from the phone's MediaSession, not Orb's audio backend. */
    private val watchArtworkCache by lazy { WatchArtworkCache(applicationContext) }
    private var watchArtworkJob: Job? = null
    private var watchArtworkPrefetchJob: Job? = null
    private var watchArtworkMetadataSwapId: String? = null
    private var watchArtworkMetadataSwapSource: Uri? = null

    /**
     * Bind small, locally available cover bytes to the session metadata. Wear OS and other
     * remote controllers can receive the bitmap through the phone without making their own
     * network request. Failures never clear the original artworkUri or the previous cache.
     * Network requests start after first audio so the cover cannot delay a song on mobile data.
     */
    private fun refreshWatchArtwork(item: MediaItem?) {
        watchArtworkJob?.cancel()
        watchArtworkPrefetchJob?.cancel()
        watchArtworkJob = null
        watchArtworkPrefetchJob = null
        if (item?.mediaMetadata?.artworkUri == null || item.mediaMetadata.artworkData != null) return

        val mediaId = item.mediaId
        val artworkUri = item.mediaMetadata.artworkUri ?: return
        watchArtworkJob = scope.launch {
            // Disk cache is checked immediately; it remains usable when mobile data drops.
            val cached = watchArtworkCache.cached(artworkUri)
            if (cached != null) {
                publishWatchArtwork(mediaId, artworkUri, cached)
            } else {
                // Do not compete with the initial stream download on a weak connection.
                var waited = 0L
                while (isActive && waited < 20_000L && !currentTrackHasBeenAudible) {
                    if (player?.currentMediaItem?.mediaId != mediaId) return@launch
                    delay(250L)
                    waited += 250L
                }
                // If playback has not started, defer artwork downloads until it does.
                if (!currentTrackHasBeenAudible) return@launch
                if (AppSettings.meteredConnection.value == true) {
                    var waitedForAudio = 0L
                    while (isActive && waitedForAudio < 20_000L &&
                        player?.currentMediaItem?.mediaId == mediaId &&
                        ((player?.bufferedPosition ?: 0L) - (player?.currentPosition ?: 0L)) <
                            METERED_ARTWORK_MIN_HEADROOM_MS
                    ) {
                        delay(500L)
                        waitedForAudio += 500L
                    }
                    if (player?.currentMediaItem?.mediaId != mediaId ||
                        ((player?.bufferedPosition ?: 0L) - (player?.currentPosition ?: 0L)) <
                            METERED_ARTWORK_MIN_HEADROOM_MS
                    ) return@launch
                }
                var retryDelayMs = 2_000L
                while (isActive && player?.currentMediaItem?.mediaId == mediaId) {
                    val bytes = watchArtworkCache.load(artworkUri)
                    if (bytes != null) {
                        publishWatchArtwork(mediaId, artworkUri, bytes)
                        break
                    }
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                }
            }
            // The very next track's cover can be ready before the watch receives its title.
            if (player?.currentMediaItem?.mediaId == mediaId) {
                watchArtworkPrefetchJob = scope.launch {
                    delay(4_000L)
                    val current = player ?: return@launch
                    if (current.currentMediaItem?.mediaId != mediaId || !current.isPlaying) return@launch
                    if (AppSettings.meteredConnection.value == true &&
                        current.bufferedPercentage < 100 &&
                        current.bufferedPosition - current.currentPosition < METERED_ARTWORK_MIN_HEADROOM_MS
                    ) return@launch
                    val nextIndex = current.currentMediaItemIndex + 1
                    if (nextIndex in 0 until current.mediaItemCount) {
                        current.getMediaItemAt(nextIndex).mediaMetadata.artworkUri?.let { next ->
                            watchArtworkCache.load(next)
                        }
                    }
                }
            }
        }
    }

    private fun publishWatchArtwork(mediaId: String, uri: Uri, data: ByteArray) {
        val live = player ?: return
        val index = live.currentMediaItemIndex
        if (index !in 0 until live.mediaItemCount) return
        val current = live.getMediaItemAt(index)
        if (current.mediaId != mediaId || current.mediaMetadata.artworkUri != uri) return
        if (current.mediaMetadata.artworkData != null) return
        val updatedMetadata = current.mediaMetadata.buildUpon()
            .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        // Same mediaId + exact same localConfiguration: metadata-only replacement does not
        // restart ExoPlayer's stream. In particular, don't call prepare() or seekTo().
        watchArtworkMetadataSwapId = mediaId
        watchArtworkMetadataSwapSource = current.localConfiguration?.uri
        live.replaceMediaItem(index, current.buildUpon().setMediaMetadata(updatedMetadata).build())
        scope.launch {
            delay(1_000L)
            if (watchArtworkMetadataSwapId == mediaId) {
                watchArtworkMetadataSwapId = null
                watchArtworkMetadataSwapSource = null
            }
        }
    }
    private var castBridge: com.music.orb.cast.OrbCastBridge? = null

    /**
     * The player the session is on. Swaps with [spare] at every crossfade — see
     * [adoptPlayer] — so anything reading it must read it *now* rather than
     * capturing it.
     */
    private var player: ExoPlayer? = null

    /**
     * The idle player. Between transitions it holds nothing; to arm one,
     * [CrossfadeController] loads it with the queue positioned on the incoming
     * track.
     */
    private var spare: ExoPlayer? = null

    private var crossfade: CrossfadeController? = null

    /** AudioTrack precision is immutable for a renderer, so changing it rebuilds both decks. */
    private var configuredFloatOutput = false
    private var outputReconfigureJob: Job? = null

    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            requestOutputReconfiguration()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            requestOutputReconfiguration()
        }
    }

    /**
     * Short-lived guard for the player that has just taken ownership after an
     * Automix handoff. B is already decoded and buffered before ownership moves,
     * so sitting in BUFFERING/IDLE for several seconds immediately afterwards
     * is never a normal "still preparing" state — it means the prepared route
     * or a post-handoff source swap got wedged and must be restarted.
     */
    private var automixHandoffRecoveryJob: Job? = null

    /**
     * Defers all non-essential network work for the track that has just won an
     * Automix handoff. The incoming deck already proved that it can play; the
     * worst thing we can do on the landing frame is make it compete with C
     * prefetch, a quality search and an upgrade audition at the same time.
     */
    private var automixPostHandoffWorkJob: Job? = null

    /**
     * Protects the first audible seconds of a newly selected track.
     *
     * Until the first sample is actually coming out of the speaker, the current track owns the
     * network exclusively: no quality hunt, no successor preflight and no queue read-ahead may
     * compete with it. After first audio we still keep a short stabilization window before optional
     * background work is released. This makes fallback-first a hard playback invariant rather than
     * a best-effort convention.
     */
    private var firstAudioBackgroundWorkJob: Job? = null

    /**
     * Mid-track transport guard.
     *
     * ExoPlayer can sit in BUFFERING forever without raising onPlayerError
     * when an upstream range request wedges. In that state the UI shows a play
     * button because isPlaying=false, but another play() only repeats the same
     * playWhenReady=true intent and therefore changes nothing. This watchdog
     * turns that silent deadlock into an automatic loader restart.
     */
    private var playbackStallRecoveryJob: Job? = null
    /** Keeps Orb Touch Transfer alive while audio continues with the Activity backgrounded. */
    private var touchTransferStateJob: Job? = null
    /** Source-side fallback when no Activity is alive to finish a touch handoff. */
    private var touchTransferEventJob: Job? = null
    private var currentTrackHasBeenAudible = false
    /** Monotonic timestamp of the first real audible frame for the current item. */
    private var currentTrackFirstAudibleAtMs: Long? = null
    private var playbackStallRecoveryCount = 0
    /** Keep speculative mobile reads behind the buffer of the audible deck. */
    private val mobileReadAheadGuard = MobileReadAheadGuard()
    /** Avoid rebuilding/serializing the resumable queue every five-second progress tick. */
    private var lastPeriodicQueueSaveAtMs = 0L

    /**
     * Monotonic owner token for transport reconstruction. The Automix handoff watchdog, the
     * generic mid-track watchdog and onPlayerError can all discover the same dead loader within
     * a few hundred milliseconds. Without a token, two delayed coroutines can both replace/seek/
     * prepare the current item and the second one tears down audio that the first one just fixed.
     * Every new recovery invalidates older delayed work; successful audible playback invalidates
     * all recovery work that was still waiting.
     */
    private var transportRecoveryEpoch = 0L

    /**
     * First-note watchdog. A newly selected track has not earned the same grace as a mid-song
     * rebuffer: if Play has been requested and no sample has become audible quickly, the selected
     * carrier is abandoned and the item is reopened so the fast Opus/AAC resolver can choose a
     * fresh route. This is intentionally one-shot per selection; it fixes a dead first URL without
     * turning a genuinely bad connection into a retry loop.
     */
    private var firstAudioRecoveryJob: Job? = null
    private var firstAudioRecoveryCount = 0

    /**
     * One audio-processor set per player, because both carry per-sink state — a
     * delay line, filter memory — that two sinks cannot share.
     *
     * The `A`/`B` pair is fixed to the players that own them; [activeFilter] and
     * [spareFilter] are the *roles*, and they trade places at every handoff
     * along with the players. Everything downstream talks in roles.
     */
    private val spatialAudioProcessorA = SpatialAudioProcessor()
    private val spatialAudioProcessorB = SpatialAudioProcessor()
    private val transitionFilterA = TransitionFilterProcessor()
    private val transitionFilterB = TransitionFilterProcessor()
    private val transitionEqA = TransitionDjEqProcessor()
    private val transitionEqB = TransitionDjEqProcessor()
    private val spatialAudioProcessorStem = SpatialAudioProcessor()
    private val transitionFilterStem = TransitionFilterProcessor()
    private val transitionEqStem = TransitionDjEqProcessor()
    private var transitionStemDeck: TransitionStemDeck? = null

    private var activeFilter: TransitionFilterProcessor = transitionFilterA
    private var spareFilter: TransitionFilterProcessor = transitionFilterB
    private var activeEq: TransitionDjEqProcessor = transitionEqA
    private var spareEq: TransitionDjEqProcessor = transitionEqB

    /** Automix's DSP analyzer — see [com.music.orb.playback.smart.TrackAnalyzer]. */
    private val trackAnalyzer = com.music.orb.playback.smart.TrackAnalyzer(this, AudioCache)

    /** Service-owned refill: keeps the station alive after the Activity stops observing state. */
    private var autoplayFillJob: Job? = null
    private var autoplayNextAttemptAt = 0L
    private var autoplayLastGeneration = -1L
    private val autoplayRefillLookahead = 2
    private val autoplayRefillCount = 3
    private val autoplayRetryDelayMs = 12_000L

    /** Read the account's persisted exclusions without holding an Activity/ViewModel alive. */
    private fun autoplayAccountKey(): String =
        OrbGoogleAuth.storedAccount()?.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: SocialRepository.currentUserId() ?: "local-device"

    private fun autoplayExcludeByPreference(
        song: Song,
        accountKey: String,
        blockedArtists: Set<String>,
        dislikes: Set<String>,
    ): Boolean {
        if (AlbumExclusionStore.isBlocked(accountKey, song)) return true
        val credits = QueueBuilder.artistSet(song.artist)
        if (credits.any { ArtistPreferenceStore.artistKey(it) in blockedArtists } ||
            ArtistPreferenceStore.artistKey(song.artist) in blockedArtists
        ) return true
        val tokenTitle = Normalizer.normalize(song.title, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        val tokenArtist = Normalizer.normalize(song.artist, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        val prefix = "rec:v1:$tokenTitle|$tokenArtist|"
        return song.videoId in dislikes || dislikes.any { it.startsWith(prefix) }
    }

    private fun scheduleAutoplayRefill() {
        val live = player ?: return
        AutoplaySession.ensureAccount(autoplayAccountKey())
        if (!AppSettings.autoplay.value || AppSettings.meteredConnection.value == null ||
            AutoplaySession.pausedByUser ||
            com.music.orb.data.rooms.RoomRepository.playbackRoom.value != null ||
            live.repeatMode != Player.REPEAT_MODE_OFF ||
            (!live.playWhenReady && !live.isPlaying)
        ) return
        val index = live.currentMediaItemIndex
        if (index !in 0 until live.mediaItemCount) return
        if (live.mediaItemCount - index - 1 > autoplayRefillLookahead) return
        if (autoplayLastGeneration != AutoplaySession.generation) {
            autoplayFillJob?.cancel()
            autoplayFillJob = null
            autoplayNextAttemptAt = 0L
            autoplayLastGeneration = AutoplaySession.generation
        }
        if (autoplayFillJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now < autoplayNextAttemptAt) return
        // Cooldown applies to failed/partial refills too; a noisy timeline cannot
        // cause dozens of identical next() requests on mobile data.
        autoplayNextAttemptAt = now + autoplayRetryDelayMs
        autoplayFillJob = scope.launch {
            val generation = AutoplaySession.generation
            val anchor = live.currentMediaItem?.mediaId ?: return@launch
            val existing = (0 until live.mediaItemCount).map { live.getMediaItemAt(it).toSong() }
            val history = AutoplaySession.history()
            val accountKey = autoplayAccountKey()
            val blockedArtists = ArtistPreferenceStore.load(accountKey)
                .filterValues { it == ArtistPreference.BLOCK }.keys
            val dislikes = LikeStatusStore.load(accountKey)
                .filterValues { it == LikeStatus.DISLIKE }.keys
            val seeds = (existing.asReversed() + history.asReversed())
                .distinctBy { it.videoId }
                .distinctBy { QueueBuilder.artistSet(it.artist).minOrNull() ?: it.artist.lowercase() }
                .take(5)
            val pool = mutableListOf<Song>()
            try {
                // Station discovery must not steal mobile bandwidth from a song
                // that is just establishing its first few seconds of buffer.
                delay(2_500L)
                var waited = 0L
                while (isActive && AppSettings.meteredConnection.value == true && waited < 20_000L) {
                    val current = player ?: return@launch
                    val aheadMs = (current.bufferedPosition - current.currentPosition).coerceAtLeast(0L)
                    val closeToEnd = current.duration > 0L &&
                        current.duration != C.TIME_UNSET &&
                        current.duration - current.currentPosition < 25_000L
                    if (aheadMs >= 10_000L || closeToEnd) break
                    delay(1_000L)
                    waited += 1_000L
                }
                for ((seedIndex, owner) in seeds.withIndex()) {
                    if (!isActive || generation != AutoplaySession.generation) return@launch
                    val seedId = if (com.music.orb.data.sources.SourceRegistry.parseTrackKey(owner.videoId) == null) {
                        owner.videoId
                    } else {
                        val target = TrackMatcher.targetOf(owner)
                        val query = TrackMatcher.queries(target).firstOrNull() ?: continue
                        YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                            ?.filterIsInstance<SearchResult.Track>()
                            ?.map { it.song }
                            ?.let { TrackMatcher.best(it, target) }?.videoId ?: continue
                    }
                    val related = withTimeoutOrNull(10_000L) {
                        YtMusicRepository.radio(seedId).getOrNull()
                    } ?: continue
                    val unseen = related.filterNot(AutoplaySession::excluded)
                    pool += withContext(Dispatchers.Default) {
                        unseen.filterNot { autoplayExcludeByPreference(it, accountKey, blockedArtists, dislikes) }
                    }
                    // One radio seed tends to recommend its owner's discography.
                    // Always consult at least two different seed artists before
                    // deciding the station has enough variety.
                    if (seedIndex >= 1 && pool.distinctBy { it.videoId }.size >= 30) break
                }
                if (generation != AutoplaySession.generation || AutoplaySession.pausedByUser ||
                    !AppSettings.autoplay.value) return@launch
                val candidates = withContext(Dispatchers.Default) {
                    QueueBuilder.extendDiverse(existing, pool, history, autoplayRefillCount)
                }
                val ready = candidates.map { candidate ->
                    // Radio normally returns audio-only items; avoid an unnecessary
                    // extra search on that fast path.
                    if (candidate.isVideo) withTimeoutOrNull(2_500L) {
                        YtMusicRepository.resolveAudio(candidate)
                    } ?: candidate else candidate
                }.filterNot { AutoplaySession.excluded(it) ||
                    autoplayExcludeByPreference(it, accountKey, blockedArtists, dislikes)
                }

                val active = player ?: return@launch
                if (generation != AutoplaySession.generation || AutoplaySession.pausedByUser ||
                    !AppSettings.autoplay.value ||
                    com.music.orb.data.rooms.RoomRepository.playbackRoom.value != null ||
                    active.repeatMode != Player.REPEAT_MODE_OFF ||
                    // A user may have started another playlist while radio was loading.
                    (0 until active.mediaItemCount).none { active.getMediaItemAt(it).mediaId == anchor }
                ) return@launch
                val liveSongs = (0 until active.mediaItemCount).map { active.getMediaItemAt(it).toSong() }
                val add = ready.filter { candidate ->
                    liveSongs.none { QueueBuilder.isSameRecording(it, candidate) } &&
                        !AutoplaySession.excluded(candidate) &&
                        !autoplayExcludeByPreference(candidate, accountKey, blockedArtists, dislikes)
                }
                if (add.isNotEmpty()) {
                    val wasEnded = active.playbackState == Player.STATE_ENDED && active.playWhenReady
                    val startAt = active.mediaItemCount
                    AutoplaySession.rememberSuggested(add)
                    active.addMediaItems(add.map { it.copy(fromAutoplay = true).toMediaItem() })
                    // When a short last track ended during the radio request, merely
                    // appending does not guarantee Media3 leaves STATE_ENDED.
                    if (wasEnded && active.playbackState == Player.STATE_ENDED) {
                        active.seekToDefaultPosition(startAt)
                        active.prepare()
                        active.play()
                    }
                    TrackLog.d("Orb", "AutoPlay appended ${add.size} new tracks from ${seeds.size} diverse seeds")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TrackLog.w("Orb", "AutoPlay refill failed: ${error.message}")
            } finally {
                // Retry even without timeline updates (e.g. temporary radio failure),
                // while the same playing station still needs more suggestions.
                scope.launch {
                    delay(autoplayRetryDelayMs)
                    if (player?.playWhenReady == true && AppSettings.autoplay.value) scheduleAutoplayRefill()
                }
            }
        }
    }

    /** AutoPlay's future tail may be reordered once, never while a transition is audible. */
    private var autoplayOrderJob: Job? = null
    private var autoplayOrderMembership: String = ""
    private var autoplayQueueReordering = false

    /**
     * Automix analysis deliberately follows the audible queue one track at a time.
     * While A is current, A is analysed first and B is not allowed to start until A's full
     * structural pass has finished. Only then may B be analysed. C remains ineligible until B has
     * actually become audible/current at the handoff. Besides preserving A -> B -> C ordering,
     * this prevents two analysis downloads/uploads from competing for bandwidth and prevents the
     * backend from receiving A and B analysis jobs together.
     */

    /** Shared with the crossfade's tail player, so both read the same disk cache. */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    /** Last sampled position of the playing track, in seconds. */
    private var lastPositionSeconds = 0L

    /** When the current track was chosen, for the time-to-first-audio log. */
    private var trackSelectedAt: Long? = null

    private var scrobbleManager: ScrobbleManager? = null
    private val socialPlaybackReporter = SocialPlaybackReporter()
    private var listenBrainzSong: Song? = null

    private var listenBrainzStartMs: Long = 0L

    private var listenBrainzDurationMs: Long? = null

    /**
     * The gateway connection publishing what's playing to Discord, or null when
     * the feature is off or no account is connected. See [DiscordRPC].
     */
    private var discordRpc: DiscordRPC? = null

    /**
     * The in-flight presence push. Held so the next one can cancel it: the
     * pushes hit the network — the artwork has to be mirrored onto Discord's CDN
     * before the activity can name it — and a skipped-through queue would
     * otherwise land its presences in whatever order the requests happened to
     * finish in, leaving the profile on a track the listener passed seconds ago.
     */
    private var discordUpdateJob: Job? = null

    /**
     * Whether a presence has been published and not yet taken down.
     *
     * Tracked because [KizzyRPC.close] — which is what clears the card — opens a
     * gateway connection first if one isn't already up. Clearing unconditionally
     * would therefore dial Discord for the sole purpose of sending it nothing,
     * every time playback paused without a presence ever having been set.
     */
    private var discordPresenceUp = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** One bounded best-quality lookup for the immediate successor of the live queue. */
    private var nextQualityPrimeJob: Job? = null
    private var nextQualityPrimeKey: String? = null
    private var nextQualityPrimeSucceeded: Boolean? = null

    /** Extra cache fill for B when A enters the transition approach. */
    private var nextTransitionWarmJob: Job? = null
    private var nextTransitionWarmKey: String? = null

    /** Whether the current track's mandatory pre-start quality attempt missed. */
    private var openingQualityMissFor: String? = null
    private var openingQualityMissed = false

    /**
     * Lifetime of the user-facing “looking for better quality” hint. The actual
     * upgrade may keep working after this expires; the text never stays pinned
     * under the seek bar for a whole song.
     */
    private var qualitySearchHintJob: Job? = null

    /**
     * Concrete decision made while the previous track is still playing.
     *
     * A miss is a real decision, not an invitation to spend another 20 seconds
     * looking when the next MediaSource opens. [opusFallback] is resolved in
     * parallel with the Lossless search so both the normal player and Automix's
     * standby have something immediate to open at the handoff.
     */
    private data class QueuedQualityPrime(
        /** Best candidate actually found for B: Lossless/Hi-Res first on Maximum, otherwise Hi-Q. */
        val preferredStream: SourceStream?,
        /** Cheap official carrier that is always allowed to start B if the preferred path is not ready. */
        val opusFallback: SourceStream?,
        val decidedAtMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val losslessPrepared: Boolean get() = preferredStream?.format?.isLossless == true
        val hiQualityPrepared: Boolean
            get() = preferredStream != null && preferredStream.format.isLossless != true
        val betterPrepared: Boolean get() = preferredStream != null
    }

    private val queuedQualityPrimes = ConcurrentHashMap<String, QueuedQualityPrime>()

    /**
     * Everything the service books against the player it is currently on.
     *
     * A field rather than an anonymous object registered once, because the
     * session moves between two players at every crossfade — see [adoptPlayer]
     * — and this has to move with it. It is attached to exactly one player at a
     * time: the one [player] names.
     */
    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            val firstAudibleFrame = isPlaying && !currentTrackHasBeenAudible
            if (isPlaying) {
                currentTrackHasBeenAudible = true
                if (firstAudibleFrame) {
                    currentTrackFirstAudibleAtMs = SystemClock.elapsedRealtime()
                }
                // Any delayed repair that was scheduled for the previous stalled state is stale
                // the instant samples are audible again.
                transportRecoveryEpoch += 1L
                firstAudioRecoveryJob?.cancel()
                firstAudioRecoveryJob = null
                firstAudioRecoveryCount = 0
                playbackStallRecoveryJob?.cancel()
                playbackStallRecoveryJob = null
                playbackStallRecoveryCount = 0
            }
            // Requested quality is deliberately NOT a startup gate. First audio wins;
            // higher-quality discovery is released only after the fallback/current carrier
            // has become audible and cleared its protected stabilization window.
            // The only number that describes what a listener actually
            // waits through. Every other timing in this app measures one
            // leg of getting a track started — a resolve, a client walk, an
            // extraction — and a leg being fast has repeatedly turned out
            // to say nothing about whether sound arrived quickly, because
            // the legs that were measured were the ones running in the
            // background for tracks nobody was waiting on.
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "Orb",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
            }
            if (isPlaying) {
                registerCurrentPlay()
                scheduleAutoplayRefill()
            }
            // A weak network can make the first note arrive after the initial artwork wait.
            // Give its cover another chance as soon as the stream has actually started.
            if (firstAudibleFrame && exoPlayer.currentMediaItem?.mediaMetadata?.artworkData == null) {
                refreshWatchArtwork(exoPlayer.currentMediaItem)
            }
            // First note owns the network. A manual/new play must never compete with queue
            // read-ahead, TIDAL/HiFi discovery or successor quality preparation while its own
            // opening buffer is still being established. Once audio is real, release optional
            // work in stages. Resume/rebuffer events for an already-audible track can use the
            // normal eager path because they are no longer part of tap-to-first-audio latency.
            if (isPlaying) {
                if (firstAudibleFrame) {
                    schedulePostFirstAudioWork(exoPlayer)
                } else {
                    prefetchAround(exoPlayer)
                    lookForBetterCopy(exoPlayer)
                    primeImmediateSuccessorQuality(exoPlayer)
                    scheduleEarlyQualityProbes(exoPlayer)
                }
            } else {
                AudioCache.cancel()
                firstAudioBackgroundWorkJob?.cancel()
                firstAudioBackgroundWorkJob = null
                earlyUpgradeProbeJob?.cancel()
                earlyUpgradeProbeJob = null
            }
            saveQueue()

            val song = exoPlayer.currentMediaItem?.toSong()
            val durationMs = exoPlayer.duration.takeIf { it > 0 }
            scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)
            socialPlaybackReporter.onPlayerStateChanged(
                isPlaying = isPlaying,
                song = song,
                durationMs = durationMs,
                positionMs = exoPlayer.currentPosition,
            )

            // ListenBrainz: "now playing" on play/resume too, not just on
            // transition — a track started from idle or resumed from pause
            // otherwise stays silent on the site.
            if (isPlaying && song != null) {
                if (listenBrainzSong == null) {
                    listenBrainzSong = song
                    listenBrainzStartMs = System.currentTimeMillis()
                    listenBrainzDurationMs = durationMs
                }
                submitListenBrainzPlayingNow(song, exoPlayer.currentPosition, durationMs)
            }

            // Discord: a pause has to clear the presence, not just stop
            // refreshing it. Discord's countdown runs on its own clock from the
            // timestamps it was given, so a presence left up while paused goes
            // on advancing through a song that has stopped — and finishes it.
            if (isPlaying) {
                pushDiscordPresence(exoPlayer)
            } else {
                clearDiscordPresence()
            }
        }

        /**
         * Do not spend network/CPU on future tracks while the song the listener
         * just selected is still buffering. Read-ahead starts from
         * onIsPlayingChanged(true), after the first audible frame.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val exoPlayer = player ?: return
            if (!playWhenReady) {
                playbackStallRecoveryJob?.cancel()
                playbackStallRecoveryJob = null
                return
            }
            TrackLog.d(
                "Orb",
                "play requested; queue quality/read-ahead deferred until first audio",
                about = exoPlayer.currentMediaItem?.mediaId,
            )
            if (!currentTrackHasBeenAudible) {
                armFirstAudioRecovery(exoPlayer)
            }
            // A user tapping Play while the current item is already BUFFERING
            // must do more than re-assert playWhenReady=true. If this track has
            // already produced audio, arm the mid-track transport guard now.
            if (!exoPlayer.isPlaying && currentTrackHasBeenAudible) {
                armPlaybackStallRecovery(exoPlayer, userRequested = true)
            }
        }

        /**
         * A seek is the one change to a playing track that no other callback
         * reports, and the only one Discord cannot work out for itself: its bar
         * is drawn from two absolute instants, so moving the playhead without
         * sending new ones leaves the profile counting down from where the
         * listener no longer is.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK && exoPlayer.isPlaying) {
                pushDiscordPresence(exoPlayer)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem != null &&
                mediaItem.mediaId == watchArtworkMetadataSwapId &&
                mediaItem.localConfiguration?.uri == watchArtworkMetadataSwapSource &&
                mediaItem.mediaMetadata.artworkData != null
            ) {
                // Adding compressed artwork is NOT a new listen/queue step/Automix handoff.
                watchArtworkMetadataSwapId = null
                watchArtworkMetadataSwapSource = null
                return
            }
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // A quality swap replaces the playing item, which Media3
            // reports here as a playlist change — indistinguishable, from
            // this callback's point of view, from the queue moving on. It
            // is not the queue moving on: it is the same song, at the same
            // position, from a better source. Letting the bookkeeping below
            // run for it scrobbled the track twice, wrote a second history
            // entry, resubmitted it to ListenBrainz and closed out its
            // play count mid-play — all of which happened, and all of which
            // are invisible until someone reads their listening history.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem?.mediaId != null &&
                mediaItem.mediaId == swappingMediaId
            ) {
                swappingMediaId = null
                return
            }

            // No crossfade case to allow for here any more. A blended advance
            // never reaches this callback — the incoming track starts as the
            // *first* item of the other player — so it is booked by
            // [adoptPlayer] instead, and what is left arriving here is only ever
            // ExoPlayer moving the queue on by itself, a repeat, or a skip.
            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
        }

        /**
         * A failed stream is not a failed track: nothing else in this
         * service ever calls [Player.prepare] again, so before this
         * existed a single read error left the player in `STATE_IDLE` for
         * good. The notification kept the song on it, the play button kept
         * being pressed, and nothing happened — which is exactly what a
         * broken app looks like from the outside.
         */
        override fun onPlayerError(error: PlaybackException) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            recoverFrom(error, exoPlayer)
        }

        // Nothing follows the last track, so there is no transition to
        // pause on — the queue simply runs out and the timer is spent.
        override fun onPlaybackStateChanged(state: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            val stateName = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> state.toString()
            }
            TrackLog.d(
                "Orb",
                "PLAYBACK_STATE $stateName id=${exoPlayer.currentMediaItem?.mediaId} " +
                    "pos=${exoPlayer.currentPosition}ms buffered=${exoPlayer.bufferedPosition}ms " +
                    "pwr=${exoPlayer.playWhenReady} playing=${exoPlayer.isPlaying} " +
                    "suppression=${exoPlayer.playbackSuppressionReason}",
                about = exoPlayer.currentMediaItem?.mediaId,
            )

            when (state) {
                Player.STATE_BUFFERING -> {
                    if (exoPlayer.playWhenReady && !currentTrackHasBeenAudible) {
                        armFirstAudioRecovery(exoPlayer)
                    } else if (exoPlayer.playWhenReady && currentTrackHasBeenAudible) {
                        armPlaybackStallRecovery(exoPlayer, userRequested = false)
                    }
                }
                Player.STATE_READY -> {
                    scrobbleManager?.onDurationAvailable(exoPlayer.duration)
                    if (exoPlayer.isPlaying) {
                        playbackStallRecoveryJob?.cancel()
                        playbackStallRecoveryJob = null
                    }
                }
                Player.STATE_ENDED -> {
                    playbackStallRecoveryJob?.cancel()
                    playbackStallRecoveryJob = null
                }
            }

            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                // The last track finished with nothing after it, so no
                // transition will ever close it out. Scrobble it now.
                val lastSong = listenBrainzSong
                if (lastSong != null) {
                    val lastStart = listenBrainzStartMs
                    val lastDuration = listenBrainzDurationMs
                        ?: exoPlayer.duration.takeIf { it > 0 }
                    submitListenBrainzFinished(lastSong, lastStart, lastDuration)
                    listenBrainzSong = null
                }
                socialPlaybackReporter.onPlaybackEnded(
                    song = exoPlayer.currentMediaItem?.toSong(),
                    positionMs = exoPlayer.currentPosition,
                    durationMs = exoPlayer.duration.takeIf { it > 0 },
                )
            }
        }

        /**
         * AutoPlay appends to the queue after the transition that ran it
         * dry, so the track to read ahead for often only exists once the
         * timeline has changed.
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // Queue edits are durable state too. In particular, removing the
            // current/last item must erase the previous resume snapshot now,
            // not wait for a later playback callback that may never arrive
            // before Android kills the process.
            saveQueue()
            val preparingUpcoming = exoPlayer.isPlaying || exoPlayer.playWhenReady ||
                    AutomixQueueLaunch.isPendingFor(exoPlayer)
            if (preparingUpcoming) {
                prefetchAround(exoPlayer)
                if (exoPlayer.isPlaying) primeImmediateSuccessorQuality(exoPlayer)
            }
            if (!autoplayQueueReordering) scheduleAutoplayMusicalOrdering(exoPlayer)
            scheduleAutoplayRefill()
        }
    }

    /** Registered alongside [playbackListener], and moved with it. */
    private val formatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Taken off the event's own window rather than off the player,
            // so it names the track this format arrived for even if the
            // queue has moved on again since. See [audioFormatFor].
            audioFormatFor = eventTime.mediaId()
            // Ground truth for a real-device listening test: this is the
            // renderer's own Format, straight off the decoder with none of
            // the app's caching/upgrade logic in between, so it's the one
            // line that can prove a "hi-res" session never quietly slid
            // onto a lower-rate stream mid-track. `adb logcat -s DECODE:I`.
            val khz = format.sampleRate.takeIf { it != Format.NO_VALUE }
                ?.let { "%.1fkHz".format(it / 1000.0) } ?: "?kHz"
            val kbps = format.bitrate.takeIf { it != Format.NO_VALUE }
                ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
            val depth = bitDepthOf(format.pcmEncoding)?.let { "${it}-bit" } ?: "?-bit"
            TrackLog.i(
                "DECODE",
                "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth ${format.channelCount}ch",
                about = audioFormatFor,
            )
            publishNerdStats()
            syncLosslessKnowledgeFromDecoder(audioFormatFor, format)
            audioFormatFor?.let { mediaId ->
                if (NerdStats.isLosslessMime(format.sampleMimeType)) {
                    hideQualitySearchHint(mediaId)
                } else {
                    maybeShowQualitySearchHint(mediaId)
                }
            }
        }

        /**
         * The seam, measured rather than described. This fires when the
         * audio track starts putting samples out again after the sink was
         * flushed, which for a quality swap is the exact instant the music
         * comes back — and the gap between it and the swap is the only
         * number that says whether any of the work above paid off. Every
         * other timing here brackets a fetch, and a fetch being fast has
         * repeatedly said nothing about whether the listener heard a hole.
         */
        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            swapCutAt = null
            TrackLog.d(
                "Orb",
                "swap seam: ${SystemClock.elapsedRealtime() - cutAt}ms of silence",
                about = eventTime.mediaId(),
            )
        }

        /**
         * The three legs the seam breaks into, logged separately because
         * they have entirely different fixes: getting the new source
         * loaded and past the load control's gate, standing a decoder up,
         * and opening an audio track. Only the first is ours to shorten.
         */
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            val cutAt = swapCutAt ?: return
            if (state == Player.STATE_READY) {
                TrackLog.d(
                    "Orb",
                    "swap leg: ready ${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                    about = eventTime.mediaId(),
                )
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            TrackLog.d(
                "Orb",
                "swap leg: $decoderName stood up in ${initializationDurationMs}ms, " +
                        "${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                about = eventTime.mediaId(),
            )
        }
    }

    /**
     * Keep catalogue badges subordinate to what the decoder really received.
     *
     * A real Lossless input confirms the preflight and makes the fact durable.
     * A lossy input withdraws a claim only when the selected source explicitly
     * promised lossless. Ordinary YouTube fallback is not evidence that the
     * matching TIDAL recording stopped existing.
     */
    private fun syncLosslessKnowledgeFromDecoder(mediaId: String?, format: Format) {
        val id = mediaId ?: return
        // During Automix B may already be decoded on the standby deck before it
        // becomes the MediaSession player. Resolve the media item from either
        // deck/queue instead of requiring it to be current on [player], otherwise
        // only A (often the first row the user played) ever persists its badge.
        val item = sequenceOf(player, spare)
            .filterNotNull()
            .firstNotNullOfOrNull { deck ->
                (0 until deck.mediaItemCount)
                    .asSequence()
                    .map(deck::getMediaItemAt)
                    .firstOrNull { it.mediaId == id }
            } ?: return
        val song = item.toSong()
        if (song.localUri != null) return
        val mime = format.sampleMimeType ?: return

        if (NerdStats.isLosslessMime(mime)) {
            val declaredTier = NerdStats.declaredFormat(id)
                ?.losslessTier
                ?.takeIf { it.isLossless }
            SourceResolver.confirmPlaybackLossless(
                song = song,
                tier = declaredTier ?: LosslessTier.LOSSLESS,
            )
        } else if (NerdStats.declaredFormat(id)?.isLossless == true) {
            SourceResolver.invalidateTrackLossless(song)
        }
    }

    /**
     * Which track an analytics event is about, taken off the event's own window.
     *
     * The player has moved on by the time some of these arrive — a format
     * change for the outgoing track lands after the transition — so its
     * `currentMediaItem` names the wrong one. The event carries the timeline it
     * was raised against, which does not.
     */
    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId

    // Registered only while the MediaSessionService lives: when Orb is playing
    // in another app, Android still sends screen-off to this process.
    private var orbLockscreenReceiverRegistered = false
    // A successful unlock is not guaranteed to dispatch ACTION_USER_PRESENT
    // (notably with some biometric and OEM keyguards). Never keep a sticky
    // "launched this lock" flag across screen-off cycles: it prevents every
    // subsequent lock from showing Orb until the service restarts.
    private var orbLockscreenLastLaunchAttemptMs = 0L
    private val orbLockscreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_SCREEN_OFF && action != Intent.ACTION_SCREEN_ON) return

            val mode = AppSettings.orbLockscreenMode.value
            // In ON_WAKE mode we prepare the dedicated surface while going dark,
            // and try again *after* a real wake if the OEM blocked the first attempt.
            // We never wake the screen in response to notifications from other apps.
            if (action == Intent.ACTION_SCREEN_ON && mode != OrbLockDisplayMode.ON_WAKE) return
            if (!AppSettings.orbLockscreenEnabled.value ||
                mode == OrbLockDisplayMode.OFF ||
                !(AppSettings.premiumEntitled.value || AppSettings.isOwnerAccount.value) ||
                player?.isPlaying != true
            ) return

            // Don't show an independent task after the keyguard is gone.
            if (action == Intent.ACTION_SCREEN_ON) {
                val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                if (!keyguard.isKeyguardLocked) return
            }
            // Special overlay access is retained solely for Orb Lock background
            // launches; it is not a notification-reading privilege.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this@PlaybackService)
            ) return
            if (OrbLockscreenActivity.isActive()) return

            val now = SystemClock.elapsedRealtime()
            if (orbLockscreenLastLaunchAttemptMs != 0L &&
                now - orbLockscreenLastLaunchAttemptMs < 400L
            ) return
            orbLockscreenLastLaunchAttemptMs = now
            try {
                Log.d("OrbLockscreen", "$action: requesting dedicated lockscreen Activity")
                startActivity(Intent(this@PlaybackService, OrbLockscreenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            } catch (error: Exception) {
                // Android may still reject starts from background on some devices.
                Log.w("OrbLockscreen", "Android restricted $action lockscreen launch", error)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val lockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(orbLockscreenReceiver, lockFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(orbLockscreenReceiver, lockFilter)
        }
        orbLockscreenReceiverRegistered = true

        // First, because everything below assumes it is standing up fresh and
        // one of the two ways this service starts does not give it that.
        //
        // A cold start runs in a new process, where the session-scoped state
        // these two hold is empty anyway. A *warm* one doesn't: closing the app
        // destroys the service while Android keeps the process to reuse, so
        // without this a second service inherits the first one's idea of what
        // was playing and what has already been asked about. That cost the
        // reported bug all three of its symptoms — a badge reading "Lossless"
        // over a player holding no bytes, and a track that had been upgraded to
        // FLAC playing its cached Opus with no second look, permanently, because
        // its id was still recorded as answered. Both are documented where the
        // state lives.
        NerdStats.forgetLastSession()
        QualityUpgrade.forgetLastSession()
        AutomixQueueLaunch.cancel()

        // Remote Automix is opt-in by capability rather than assumption. Probe off the
        // playback thread; until the backend positively reports ready, the mature local
        // analyzer/planner remains authoritative and playback never waits on this request.
        scope.launch(Dispatchers.IO) {
            // Render may still be waking up on the first probe. Retry off the playback thread;
            // RemoteAutomixClient has its own endpoint/circuit cooldown, so a backend that has not
            // been upgraded yet costs no repeated network traffic.
            while (isActive && !com.music.orb.playback.smart.RemoteAutomixClient.probe()) {
                delay(REMOTE_AUTOMIX_PROBE_RETRY_MS)
            }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        // No user agent on the factory: the right one depends on which client
        // minted the URL, so it is set per request below. Setting it here as
        // well would not override that — OkHttpDataSource *appends* the
        // factory's agent after the request's, and the fetch would go out
        // carrying two contradictory User-Agent headers.
        // The URI that enters the resolver is usually orb://, but what comes OUT can be
        // either a network carrier (https://...) or a saved local download (content:// /
        // file://). ResolvingDataSource opens its resolved URI on the factory supplied here.
        // Feeding it a raw OkHttpDataSource meant every downloaded track was validated as a
        // perfectly good local audio file and then handed to OkHttp, which rejects non-HTTP
        // schemes as "Malformed URL". Keep chunked OkHttp as the network leg, but dispatch the
        // resolved URI once more through DefaultDataSource so local downloads stay local.
        val resolvedNetworkFactory = ChunkedDataSource.Factory(
            OkHttpDataSource.Factory(Http.client),
            STREAM_CHUNK_BYTES,
        )
        val resolvedDataSourceFactory = DefaultDataSource.Factory(this, resolvedNetworkFactory)
        val resolvingFactory = ResolvingDataSource.Factory(
            resolvedDataSourceFactory,
        ) { dataSpec ->
            // Which track everything below is for, said once, because none of
            // it would otherwise know: this runs on ExoPlayer's loader thread
            // with a DataSpec and nothing else, and the work it starts — the
            // source ladder, the module sandbox, a client walk — logs from
            // places several layers deep that have no idea whose bytes they
            // are fetching. Read-ahead means the track being resolved here is
            // usually *not* the one playing, which is exactly why the lines
            // have to say. See [TrackLog.about].
            val requestMediaId = mediaIdIn(dataSpec.uri)
            val about = TrackLog.about(requestMediaId)
            // Local-first applies to regular playback, read-ahead/analysis, the
            // upgrade route and the Automix standby deck. A verified offline
            // rendition should not consume another stream merely because the
            // device has network access or an old remote prime was cached.
            val offline = requestMediaId?.takeIf {
                shouldUseSavedFileForStreaming(
                    online = AppSettings.internetAvailable.value,
                    preferDownloadsOnline = AppSettings.useDownloadsWhileOnline.value,
                )
            }?.let { id ->
                runBlocking(about) { com.music.orb.download.Downloads.savedUri(this@PlaybackService, id) }
            }?.takeIf { it.scheme?.lowercase() in setOf("file", "content") }
            if (offline != null) {
                TrackLog.d("Orb", "local-first playback $requestMediaId", about = requestMediaId)
                return@Factory dataSpec.buildUpon()
                    .setUri(offline)
                    .setHttpRequestHeaders(emptyMap())
                    .build()
            }
            // A source-backed track is resolved by whichever source can serve
            // it, which is not necessarily the one it was queued from — see
            // [SourceResolver.resolve]. Handled ahead of the YouTube path
            // because these carry no `v` parameter and would otherwise fall
            // straight through unresolved.
            if (dataSpec.uri.authority == "source") {
                val sourceMediaId = mediaIdIn(dataSpec.uri)
                val target = SourceResolver.targetIn(dataSpec.uri)
                // First-note policy: never make a Play tap wait for catalogue-quality selection.
                // Race the pinned source against a YouTube Music Opus/AAC fallback for a very
                // small budget. Only if both cheap paths miss do we fall back to the old complete
                // resolver for reliability. Once audio is audible QualityUpgrade is re-armed below
                // and can climb to Hi-Q/Lossless without extending tap-to-audio latency.
                val stream = runBlocking(about) {
                    SourceResolver.resolveImmediate(dataSpec.uri, FIRST_AUDIO_SOURCE_BUDGET_MS)
                        ?: withTimeout(RESOLVE_TIMEOUT_MS) { SourceResolver.resolve(dataSpec.uri) }
                } ?: throw java.io.IOException("No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}")
                if (!stream.hasPlayableHttpUrl()) {
                    throw java.io.IOException("Source resolver returned an invalid HTTP stream URL")
                }
                sourceMediaId?.let { mediaId ->
                    NerdStats.onSourceStream(mediaId, stream.format, stream.losslessVerified)
                    QualityUpgrade.settledForLess(
                        mediaId = mediaId,
                        target = target,
                        playing = stream.format,
                    )
                }
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setHttpRequestHeaders(stream.headers)
                    .build()
            }
            val videoId = dataSpec.uri.getQueryParameter("v")
                ?: return@Factory dataSpec

            // Analyzer read-ahead deliberately uses the official YouTube copy.
            // It exists to obtain PCM evidence, not to choose what the listener
            // will hear. Keeping it outside StreamChoice prevents a head fetch
            // from pinning the future track to Opus after queue preflight has
            // already found a better TIDAL/source rendition.
            if (dataSpec.uri.getQueryParameter("analysis") == "1") {
                val carrier = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) {
                            StreamResolver.resolveImmediatePlayback(videoId)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Analysis stream resolution timed out for $videoId", e)
                }
                if (!carrier.hasPlayableHttpUrl()) {
                    StreamResolver.invalidatePlaybackUrl(videoId)
                    throw java.io.IOException("Analysis resolver returned an invalid HTTP stream URL")
                }
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(carrier.url))
                    .setHttpRequestHeaders(carrier.headers)
                    .build()
            }

            // An upgraded item carries a marker and its stream has already
            // been found — see [QualityUpgrade]. Answered before anything
            // else, and without re-resolving: this exact URL is what the
            // player was told it was getting when it agreed to the swap.
            QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
                if (!upgraded.hasPlayableHttpUrl()) {
                    QualityUpgrade.forget(videoId)
                    QualityUpgrade.refuseUpgrades(videoId)
                    TrackLog.w(
                        "Orb",
                        "discarded malformed forced upgrade for $videoId: " +
                            upgraded.url.streamOriginForLog(),
                        about = videoId,
                    )
                    throw java.io.IOException("Prepared upgrade contained an invalid HTTP stream URL")
                }
                // An audition opens this same stream before a note of the one
                // playing has been touched — see [auditionUpgrade] — so what it
                // is about to be handed describes a swap that has not happened
                // and may never. Recording it here would light "Lossless" over
                // the lossy stream still coming out of the speaker. The real
                // open, moments later, records it.
                val proving = QualityUpgrade.isAuditioning(videoId)
                if (!proving) {
                    NerdStats.onSourceStream(videoId, upgraded.format, upgraded.losslessVerified)
                }
                // Logged because the alternative — a swap that silently never
                // reached its stream — is indistinguishable in the logs from
                // one that reached it and got nothing back, and the two have
                // opposite fixes.
                TrackLog.d(
                    "Orb",
                    "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                            "from ${Uri.parse(upgraded.url).host} " +
                            "at ${dataSpec.position} (${upgraded.format.summary})",
                    about = videoId,
                )
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(upgraded.url))
                    .setHttpRequestHeaders(upgraded.headers)
                    .build()
            }
            // Files recorded by Downloads were checked before network and forced upgrades.
            // The previous track may already have completed the quality decision
            // for this exact queue item. Honour that before StreamChoice: old
            // read-ahead/cache choices must never overrule a proved Lossless
            // source, and a 30-second miss must never trigger a second 20-second
            // Lossless wait at the moment playback is supposed to hand off.
            freshQueuedQualityPrime(videoId)?.let { prime ->
                val route = dataSpec.uri.getQueryParameter("qp")
                val preferred = prime.preferredStream
                    ?.takeIf { it.hasPlayableHttpUrl() }
                    ?.takeIf { SourceResolver.preparedQueueCandidateAllowed(it.format) }

                // A route is only attached to the real queue item after its first bytes were
                // successfully warmed. Serving the exact prepared stream here therefore does
                // not repeat catalogue work at handoff and cannot silently downgrade it.
                if ((route == "lossless" || route == "hiq") && preferred != null) {
                    NerdStats.onSourceStream(videoId, preferred.format, preferred.losslessVerified)
                    val preferredHost = Uri.parse(preferred.url).host.orEmpty()
                    StreamChoice.remember(
                        videoId,
                        preferred,
                        substituted = !preferredHost.contains("googlevideo", ignoreCase = true),
                    )
                    return@Factory dataSpec.buildUpon()
                        .setUri(Uri.parse(preferred.url))
                        .setHttpRequestHeaders(preferred.headers)
                        .build()
                }

                if (route == "lossless" || route == "hiq") {
                    // The queue says a preferred rendition was validated, but it is no longer
                    // legal/available (for example Maximum was disabled). Fail only this route;
                    // recovery immediately rebuilds B onto the already resolved fallback.
                    throw java.io.IOException("Prepared queue quality route is no longer available")
                }

                if (route == "opus") {
                    val fallback = prime.opusFallback ?: run {
                        val carrier = try {
                            runBlocking(about) {
                                withTimeout(RESOLVE_TIMEOUT_MS) {
                                    resolveYouTubeCarrierWithRematch(
                                        videoId,
                                        SourceResolver.targetIn(dataSpec.uri),
                                    )
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw java.io.IOException("Prepared fallback resolution timed out for $videoId", e)
                        }
                        carrier
                    }
                    val safeFallback = if (fallback.hasPlayableHttpUrl()) {
                        fallback
                    } else {
                        TrackLog.w(
                            "Orb",
                            "prepared fallback URL was malformed for $videoId; minting a fresh YouTube carrier",
                            about = videoId,
                        )
                        StreamResolver.invalidatePlaybackUrl(videoId)
                        runBlocking(about) {
                            withTimeout(RESOLVE_TIMEOUT_MS) {
                                resolveYouTubeCarrierWithRematch(
                                    videoId,
                                    SourceResolver.targetIn(dataSpec.uri),
                                )
                            }
                        }
                    }
                    StreamChoice.remember(videoId, safeFallback, substituted = false)
                    return@Factory dataSpec.buildUpon()
                        .setUri(Uri.parse(safeFallback.url))
                        .setHttpRequestHeaders(safeFallback.headers)
                        .build()
                }
            }

            // Whoever is already filling this track's cache entry keeps it.
            // Everything below decides between servers holding *different
            // files*, and this method is called again for every re-open of a
            // track — including the continuation fetch when playback runs off
            // the end of the cached bytes. Deciding afresh each time is how
            // the middle of an MP4 ended up appended to a WebM. See
            // [StreamChoice].
            StreamChoice.of(videoId)?.let { serving ->
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
            // A track queued from YouTube may be held by a source the user
            // ranked above it — see [SourceResolver.substituteForYouTube] and
            // [raceYouTubeOrModule]. Only worth the extra lookup when
            // something actually outranks YouTube; otherwise this is the
            // plain resolve every build before this one made.
            if (!SourceResolver.canSubstituteForYouTube()) {
                val carrier = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) {
                            resolveYouTubeCarrierWithRematch(
                                videoId,
                                SourceResolver.targetIn(dataSpec.uri),
                            )
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Immediate stream resolution timed out for $videoId", e)
                }
                if (!carrier.hasPlayableHttpUrl()) {
                    StreamResolver.invalidatePlaybackUrl(videoId)
                    throw java.io.IOException("Immediate resolver returned an invalid HTTP stream URL")
                }
                StreamChoice.remember(videoId, carrier, substituted = false)

                // Register the quality debt without doing quality network work
                // here. lookForBetterCopy() is gated by isPlaying, so the first
                // note is already audible before the upgrade search begins.
                QualityUpgrade.settledForLess(
                    mediaId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                    playing = carrier.format,
                )
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(carrier.url))
                    .setHttpRequestHeaders(carrier.headers)
                    .build()
            }
            val won = runBlocking(about) {
                resolveWithModulePriority(
                    videoId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                    preparedDash = dataSpec.uri.getQueryParameter("pl") == "1",
                )
            }
            when (won) {
                is Resolved.Module -> {
                    if (!won.stream.hasPlayableHttpUrl()) {
                        StreamChoice.refuseSubstitutes(videoId)
                        TrackLog.w(
                            "Orb",
                            "module produced malformed URL for $videoId; falling back to YouTube immediately",
                            about = videoId,
                        )
                        val carrier = runBlocking(about) {
                            withTimeout(RESOLVE_TIMEOUT_MS) {
                                resolveYouTubeCarrierWithRematch(
                                    videoId,
                                    SourceResolver.targetIn(dataSpec.uri),
                                )
                            }
                        }
                        StreamChoice.remember(videoId, carrier, substituted = false)
                        dataSpec.buildUpon()
                            .setUri(Uri.parse(carrier.url))
                            .setHttpRequestHeaders(carrier.headers)
                            .build()
                    } else {
                        NerdStats.onSourceStream(videoId, won.stream.format, won.stream.losslessVerified)
                        StreamChoice.remember(videoId, won.stream, substituted = true)
                        dataSpec.buildUpon()
                            .setUri(Uri.parse(won.stream.url))
                            .setHttpRequestHeaders(won.stream.headers)
                            .build()
                    }
                }
                // A module could have served this and didn't — it missed, its
                // server was slow, or the lookup ran out of budget. The last
                // of those is worth chasing rather than accepting: measured
                // here, a module's stream URL arrived 66ms after the live path
                // gave up on it, and the difference between a FLAC and a
                // YouTube Opus stream came down to that. The second look has
                // no such deadline, so what was nearly in hand is asked for
                // again while the fallback plays.
                is Resolved.YouTube -> {
                    val safeCarrier = won.stream.takeIf { it.hasPlayableHttpUrl() } ?: run {
                        StreamResolver.invalidatePlaybackUrl(videoId)
                        TrackLog.w(
                            "Orb",
                            "resolved carrier has an invalid URL for $videoId; searching again",
                            about = videoId,
                        )
                        runBlocking(about) {
                            withTimeout(RESOLVE_TIMEOUT_MS) {
                                resolveYouTubeCarrierWithRematch(
                                    videoId,
                                    SourceResolver.targetIn(dataSpec.uri),
                                )
                            }
                        }
                    }
                    // Access-denied YouTube IDs can be rescued by another source.
                    // Preserve that source's HTTP headers and identity so a later
                    // failure downgrades to Opus, not the same broken Hi-Q route.
                    val substituted = !Uri.parse(safeCarrier.url).host.orEmpty()
                        .contains("googlevideo", ignoreCase = true)
                    StreamChoice.remember(videoId, safeCarrier, substituted = substituted)
                    NerdStats.onSourceStream(videoId, safeCarrier.format, safeCarrier.losslessVerified)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(safeCarrier.url))
                        .setHttpRequestHeaders(safeCarrier.headers)
                        .build()
                }
            }
        }
        // Read-ahead resolves streams through the same chain the player does.
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, resolvingFactory)
        AudioCache.setUpstream(defaultDataSourceFactory)
        mediaSourceFactory = DefaultMediaSourceFactory(AudioCache.playbackFactory(defaultDataSourceFactory))

        configuredFloatOutput = shouldEnableFloatOutput()
        val exoPlayer = buildPlayer(
            spatialAudioProcessorA, transitionEqA, transitionFilterA, ownsSession = true,
        )
        val sparePlayer = buildPlayer(
            spatialAudioProcessorB, transitionEqB, transitionFilterB, ownsSession = false,
        )
        val stemPlayer = buildStemPlayer(
            spatialAudioProcessorStem, transitionEqStem, transitionFilterStem,
        )
        transitionStemDeck = TransitionStemDeck(
            stemPlayer, transitionEqStem, transitionFilterStem,
        )
        player = exoPlayer
        spare = sparePlayer
        // Both sinks feed the same session id, so the system equalizer and any
        // other effect attached to the app applies to whichever player happens
        // to be audible. Without it a crossfade would audibly change EQ halfway
        // through, and again at every handoff.
        sparePlayer.audioSessionId = exoPlayer.audioSessionId
        stemPlayer.audioSessionId = exoPlayer.audioSessionId

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        applyOutputRoute()
        runCatching { getSystemService(AudioManager::class.java).registerAudioDeviceCallback(outputDeviceCallback, null) }
        observeSettings()
        observeScrobbling()
        observeDiscord()
        watchSleepTimer()
        // Before the listener below is attached, so loading the queue doesn't
        // read as a track change and set the read-ahead going.
        restoreLastQueue(exoPlayer)

        // History pings fire once a track is actually audible — both when
        // playback starts and when the queue moves on while already playing.
        exoPlayer.addListener(playbackListener)

        // Only the analytics listener reports the format the audio renderer was
        // configured with. Treated as a trigger rather than a source: the
        // publisher reads the format off the player, so it can't go stale
        // against the track the bitrate is looked up for.
        exoPlayer.addAnalyticsListener(formatListener)

        // Analysis is strictly A -> B on every network. The callback is emitted before
        // worker cleanup, so wait for A to become idle and re-check the live queue on Main.
        trackAnalyzer.setOnAnalysisUpdated { trackId ->
            scope.launch {
                // Planning is event-driven: as soon as either member of the live A/B
                // pair changes analysis state, let CrossfadeController evaluate it now.
                // In particular, B finishing no longer waits for the next 250 ms heartbeat.
                crossfade?.onAnalysisUpdated(trackId)

                val live = player ?: return@launch

                // Future AutoPlay previews also arrive through this callback. Reorder only
                // when new evidence actually lands — never from the 5 s progress sampler.
                val isFutureAutoplay = ((live.currentMediaItemIndex + 1) until live.mediaItemCount)
                    .any { index ->
                        val item = live.getMediaItemAt(index)
                        item.mediaId == trackId && item.fromAutoplay
                    }
                if (isFutureAutoplay && AppSettings.autoplay.value && AppSettings.smartFadeEnabled.value) {
                    applyAutoplayMusicalOrder()
                }

                // Strict full-analysis order remains A -> B. The callback is emitted before
                // worker cleanup, so wait only when this event belongs to current A.
                val current = live.currentMediaItem ?: return@launch
                if (current.mediaId != trackId) return@launch
                while (!automixOutgoingReadyForIncoming(trackId) &&
                    trackAnalyzer.isAnalysing(trackId)
                ) {
                    delay(AUTOMIX_ANALYSIS_ORDER_POLL_MS)
                }
                if (live.currentMediaItem?.mediaId != trackId ||
                    !automixOutgoingReadyForIncoming(trackId)
                ) {
                    return@launch
                }
                requestCurrentAutomixPairAnalysis(live)
                // B may already have been restored/cached synchronously by that request.
                crossfade?.onAnalysisUpdated(trackId)
            }
        }

        reportProgress()

        val controller = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            incomingAudioReadyFor = ::automixIncomingAudioReady,
            requestAnalysis = { item, durationMs ->
                // The controller may ask about A and B in the same heartbeat, but the service owns
                // the ordering contract. B is ignored until A meets the network-appropriate gate
                // (complete and idle on every network); the analyzer callback above then
                // unlocks B immediately rather than waiting for another heartbeat.
                requestSequentialAutomixAnalysis(item, durationMs)
            },
            // Bind the processors to the *audio streams* when the audible
            // overlap starts. The MediaSession now changes owner halfway through
            // the blend, so reading activeFilter/spareFilter on every filter tick
            // would make the EQ suddenly jump from one song to the other at the
            // handoff. Capturing them here keeps "incoming" on B and "outgoing"
            // on A for the whole transition.
            filters = object : TransitionFilters {
                private var incomingFilterProcessor = spareFilter
                private var outgoingFilterProcessor = activeFilter
                private var incomingEqProcessor = spareEq
                private var outgoingEqProcessor = activeEq

                override fun begin() {
                    // At arm time active=A and spare=B. Capture the concrete processors now;
                    // adoptPlayer() swaps the service fields later, but the DSP must stay on the
                    // same two audio streams for the entire overlap.
                    outgoingFilterProcessor = activeFilter
                    incomingFilterProcessor = spareFilter
                    outgoingEqProcessor = activeEq
                    incomingEqProcessor = spareEq
                }

                override fun incomingRms(): Float? = incomingFilterProcessor.recentRms()
                override fun outgoingRms(): Float? = outgoingFilterProcessor.recentRms()

                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    incomingFilterProcessor.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    outgoingFilterProcessor.setCutoffs(lowPassHz, highPassHz)

                override fun incomingEq(lowDb: Float, midDb: Float, highDb: Float) =
                    incomingEqProcessor.setGains(lowDb, midDb, highDb)

                override fun outgoingEq(lowDb: Float, midDb: Float, highDb: Float) =
                    outgoingEqProcessor.setGains(lowDb, midDb, highDb)
            },
            requestIncomingStems = { item, startMs, endMs ->
                val outgoingId = player?.currentMediaItem?.mediaId
                item.localConfiguration?.uri?.takeIf {
                    outgoingId != null && automixOutgoingReadyForIncoming(outgoingId)
                }?.let { uri ->
                    trackAnalyzer.requestTransitionStems(
                        item.mediaId,
                        uri,
                        startMs / 1000.0,
                        endMs / 1000.0,
                    )
                }
            },
            stemsFor = { item, positionMs ->
                trackAnalyzer.transitionStemsFor(item.mediaId, positionMs)
            },
            stemDeck = { transitionStemDeck },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
            analysisReadyForPlan = { item, incoming ->
                trackAnalyzer.isReadyForPlan(item.mediaId, incoming)
            },
        )
        crossfade = controller
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            SessionPlayer(exoPlayer, controller, ::onTransportPlayRequested),
            mediaLibraryCallback,
        )
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            // Media3's default CacheBitmapLoader can memoize a failed artworkUri fetch;
            // later embedded artworkData with the same URI would then reuse that failure.
            // Keep loading uncached here; WatchArtworkCache owns the bounded disk cache.
            .setBitmapLoader(DataSourceBitmapLoader(this))
            .build()
        refreshWatchArtwork(exoPlayer.currentMediaItem)

        castBridge = runCatching {
            com.music.orb.cast.OrbCastBridge(this, exoPlayer, resolvingFactory)
        }.getOrNull()

        // Orb Touch Transfer is intentionally NOT owned by PlaybackService. Nearby discovery
        // must exist only while MainActivity is resumed, the display is interactive and the
        // device is unlocked. Background/locked playback therefore cannot advertise or scan.
        touchTransferStateJob?.cancel()
        touchTransferStateJob = null
        touchTransferEventJob?.cancel()
        touchTransferEventJob = null
        OrbTouchTransfer.stop(OrbTouchTransfer.Client.PLAYBACK_SERVICE)
        com.music.orb.data.rooms.RoomSession.attach(
            this, { player },
            onStart = { crossfade?.onQueueCleared() },
            isMixing = { crossfade?.isTransitioning() == true },
        )
    }

    /**
     * Both players, built identically. Only [ownsSession] differs, and only at
     * construction — it moves at every handoff, see [setSessionOwner].
     *
     * They share the media source factory, so whichever one is arming reads from
     * the same on-disk cache the other is playing out of rather than
     * re-resolving a stream URL for audio that is already local.
     */
    private fun buildPlayer(
        spatial: SpatialAudioProcessor,
        eq: TransitionDjEqProcessor,
        filter: TransitionFilterProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(spatial, eq, filter))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        // Network wake lock is held by Media3 only while a deck needs to play.
        // Keep the CPU/network active when the display is off (including during buffering).
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        // Back restarts the track once you're this far into it; only a
        // press before that steps to the previous one.
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    /** Local third deck for a prepared accompaniment WAV; it never owns the MediaSession. */
    private fun buildStemPlayer(
        spatial: SpatialAudioProcessor,
        eq: TransitionDjEqProcessor,
        filter: TransitionFilterProcessor,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(spatial, eq, filter))
        // Stem WAVs are local cache files. Bypass playback's stream resolver entirely so a
        // transition-only file can never trigger source discovery or quality upgrade work.
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this)))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ false)
        .setHandleAudioBecomingNoisy(false)
        .build()

    /**
     * Reopens a newly selected item when its first carrier produces no audible sample quickly.
     *
     * The resolver intentionally does not pre-probe first-note URLs because the duplicate request
     * itself adds latency. The cost is that a URL can occasionally look valid and then wedge on
     * its first byte. Waiting ten seconds for that CDN request defeats the whole fallback-first
     * policy, so after a short grace we throw away only that rendition/choice and let the expanded
     * first-note race mint a fresh carrier. Quality discovery remains deferred until audio exists.
     */
    private fun armFirstAudioRecovery(expectedPlayer: ExoPlayer) {
        val currentItem = expectedPlayer.currentMediaItem ?: return
        val mediaId = currentItem.mediaId
        val initialUri = currentItem.localConfiguration?.uri
        // Local/content playback has no network carrier to replace and should never be bounced
        // through the remote first-note watchdog.
        if (initialUri != null && initialUri.scheme != "orb") return
        if (currentTrackHasBeenAudible || !expectedPlayer.playWhenReady) return
        if (firstAudioRecoveryCount >= FIRST_AUDIO_RECOVERY_LIMIT) return
        if (firstAudioRecoveryJob?.isActive == true) return

        firstAudioRecoveryJob = scope.launch(TrackLog.about(mediaId)) {
            // A slow cellular URL/CDN handshake is not a stuck loader. Measure
            // actual buffered-media progress before replacing a viable stream.
            var priorBufferedPosition = expectedPlayer.bufferedPosition
            delay(FIRST_AUDIO_STALL_MS)
            var progressiveChecks = 0
            while (progressiveChecks < FIRST_AUDIO_PROGRESS_MAX_CHECKS) {
                val candidate = player ?: return@launch
                if (candidate !== expectedPlayer ||
                    candidate.currentMediaItem?.mediaId != mediaId ||
                    currentTrackHasBeenAudible || !candidate.playWhenReady || candidate.isPlaying
                ) return@launch
                val addedMs = candidate.bufferedPosition - priorBufferedPosition
                if (addedMs < FIRST_AUDIO_PROGRESS_EPSILON_MS) break
                TrackLog.d(
                    "Orb",
                    "FIRST_AUDIO stream gaining buffered media (+${addedMs}ms); waiting before recovery",
                    about = mediaId,
                )
                priorBufferedPosition = candidate.bufferedPosition
                progressiveChecks++
                delay(FIRST_AUDIO_PROGRESS_GRACE_MS)
            }
            val live = player ?: return@launch
            if (
                live !== expectedPlayer ||
                live.currentMediaItem?.mediaId != mediaId ||
                currentTrackHasBeenAudible ||
                !live.playWhenReady ||
                live.isPlaying
            ) return@launch

            val stalled = live.playbackState == Player.STATE_BUFFERING || live.playbackState == Player.STATE_IDLE
            if (!stalled) return@launch
            val index = live.currentMediaItemIndex
            if (index !in 0 until live.mediaItemCount) return@launch
            val item = live.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri

            firstAudioRecoveryCount += 1
            firstAudioRecoveryJob = null
            TrackLog.w(
                "Orb",
                "FIRST_AUDIO no samples after ${FIRST_AUDIO_STALL_MS}ms; reopening fast fallback " +
                    "state=${live.playbackState} buffered=${live.bufferedPosition}ms attempt=$firstAudioRecoveryCount",
                about = mediaId,
            )

            // Only bare YouTube ids participate in StreamResolver's URL cache. Source-backed ids
            // are still rebuilt below, which cancels their slow source resolve and restarts the
            // pinned-vs-YouTube first-note race.
            if (SourceRegistry.parseTrackKey(mediaId) == null) {
                StreamChoice.forget(mediaId)
                StreamResolver.invalidatePlaybackUrl(mediaId)
            }
            if (uri != null) {
                withContext(Dispatchers.IO) { AudioCache.discardRendition(uri) }
            }

            if (
                player !== live ||
                live.currentMediaItem?.mediaId != mediaId ||
                currentTrackHasBeenAudible
            ) return@launch

            val resumePosition = live.currentPosition.coerceAtLeast(0L)
            swappingMediaId = mediaId
            live.replaceMediaItem(index, item)
            live.seekTo(index, resumePosition)
            live.prepare()
            live.playWhenReady = true
            live.play()
        }
    }

    /**
     * Arms recovery for a song that was already audible and then stopped
     * advancing. This intentionally does not run during first-play startup:
     * a cold stream may legitimately need a moment to open, whereas a song
     * that has already played has proven its source and decoder and should
     * never need repeated taps to get moving again.
     */
    private fun armPlaybackStallRecovery(
        expectedPlayer: ExoPlayer,
        userRequested: Boolean,
    ) {
        val mediaId = expectedPlayer.currentMediaItem?.mediaId ?: return
        if (!currentTrackHasBeenAudible || !expectedPlayer.playWhenReady) return
        if (playbackStallRecoveryJob?.isActive == true) return

        playbackStallRecoveryJob = scope.launch(TrackLog.about(mediaId)) {
            var lastPosition = expectedPlayer.currentPosition
            var lastBuffered = expectedPlayer.bufferedPosition
            delay(
                if (userRequested) PLAYBACK_STALL_AFTER_PLAY_TAP_MS
                else PLAYBACK_STALL_GRACE_MS
            )

            var live = player ?: return@launch
            if (live !== expectedPlayer ||
                live.currentMediaItem?.mediaId != mediaId ||
                !live.playWhenReady ||
                live.isPlaying
            ) return@launch

            val stalledState =
                live.playbackState == Player.STATE_BUFFERING ||
                    (live.playbackState == Player.STATE_READY &&
                        live.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            if (!stalledState) return@launch

            // Mobile downloads can grow in bursts. A single extra second is
            // not enough: only rebuild when consecutive observations have
            // shown no meaningful progress, not while buffered audio grows.
            var progressChecks = 0
            while (progressChecks < PLAYBACK_STALL_MAX_PROGRESS_CHECKS) {
                val growth = (live.bufferedPosition - lastBuffered).coerceAtLeast(0L)
                val progress = (live.currentPosition - lastPosition).coerceAtLeast(0L)
                if (growth < PLAYBACK_STALL_BUFFER_GROWTH_MS &&
                    progress < PLAYBACK_STALL_PROGRESS_EPSILON_MS
                ) break
                lastBuffered = live.bufferedPosition
                lastPosition = live.currentPosition
                progressChecks++
                delay(PLAYBACK_STALL_GROWTH_GRACE_MS)
                live = player ?: return@launch
                if (live !== expectedPlayer ||
                    live.currentMediaItem?.mediaId != mediaId ||
                    !live.playWhenReady ||
                    live.isPlaying
                ) return@launch
            }

            if (live.playbackState != Player.STATE_BUFFERING &&
                !(live.playbackState == Player.STATE_READY &&
                    live.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            ) return@launch

            playbackStallRecoveryCount += 1
            val aggressive = playbackStallRecoveryCount > 1
            TrackLog.w(
                "Orb",
                "mid-track playback stalled for $mediaId at ${live.currentPosition}ms; " +
                    "reopening transport (attempt=$playbackStallRecoveryCount, aggressive=$aggressive)",
                about = mediaId,
            )
            repairStalledCurrentPlayback(live, mediaId, aggressive = aggressive)
        }
    }

    /**
     * A Play command can arrive while playWhenReady is already true (the
     * normal BUFFERING state), in which case Player.Listener receives no
     * playWhenReady callback at all. SessionPlayer calls this explicitly so a
     * tap on the visible Play button can kick the transport guard immediately.
     */
    private fun onTransportPlayRequested() {
        val live = player ?: return
        if (!live.isPlaying && live.playWhenReady && currentTrackHasBeenAudible) {
            armPlaybackStallRecovery(live, userRequested = true)
        }
    }

    /**
     * Moves the session onto the player the crossfade has just started the
     * incoming track on. This is the whole of the handoff: no seek, no
     * re-buffer, and no audio rendered twice.
     *
     * Order matters in one place — focus is released on the outgoing player
     * *before* the incoming one asks for it, so the app never holds two focus
     * requests at once and never briefly holds none.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        // CrossfadeController only reaches this point while A is actively
        // advancing, so B must inherit that intent even if the audio-focus swap
        // briefly toggles one deck's playWhenReady flag on some devices.
        val shouldContinuePlaying = outgoing.playWhenReady || outgoing.isPlaying || incoming.playWhenReady

        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)
        if (shouldContinuePlaying) {
            if (incoming.playbackState == Player.STATE_IDLE) incoming.prepare()
            incoming.playWhenReady = true
        }

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)
        // The fields move before the listeners are attached, so anything the
        // first callback reads already describes the new arrangement.
        player = incoming
        spare = outgoing
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        val heldEq = activeEq
        activeEq = spareEq
        spareEq = heldEq
        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)
        // B's decoder can have settled while it was the silent standby deck,
        // before [formatListener] was attached. Persist that already-known tier
        // immediately at handoff so every actually played Lossless/Hi-Res track
        // repaints its album/playlist row and contributes to the collection badge.
        incoming.audioFormat?.let { format ->
            syncLosslessKnowledgeFromDecoder(incoming.currentMediaItem?.mediaId, format)
        }

        mediaSession?.player = SessionPlayer(incoming, requireNotNull(crossfade), ::onTransportPlayRequested)
        TrackLog.i(
            "Orb",
            "AUTOMIX_HANDOFF adopted B id=${incoming.currentMediaItem?.mediaId} " +
                "state=${incoming.playbackState} pos=${incoming.currentPosition}ms " +
                "buffered=${incoming.bufferedPosition}ms pwr=${incoming.playWhenReady} " +
                "playing=${incoming.isPlaying}",
            about = incoming.currentMediaItem?.mediaId,
        )

        // The queue moving on used to arrive here as an item transition on the
        // one player that owned the queue. It cannot any more — the incoming
        // track started as its own player's *first* item, which fires on a
        // player nothing was listening to yet — so the bookkeeping that hung off
        // that callback is driven explicitly instead. Without this the crossfade
        // would silently stop scrobbling, stop writing history, stop honouring
        // "sleep after this song" and stop reading ahead.
        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )
        watchAutomixHandoff(incoming, shouldContinuePlaying)
    }

    /**
     * A prepared Automix deck that has already produced audible frames should
     * not become a permanent spinner after the session handoff. Watch only the
     * short post-handoff window and repair only a sustained BUFFERING/IDLE
     * state, never an ordinary pause or a READY player waiting on audio focus.
     */
    private fun watchAutomixHandoff(incoming: ExoPlayer, expectedToPlay: Boolean) {
        val mediaId = incoming.currentMediaItem?.mediaId ?: return
        automixHandoffRecoveryJob?.cancel()
        automixHandoffRecoveryJob = scope.launch(TrackLog.about(mediaId)) {
            val startedAt = SystemClock.elapsedRealtime()
            val deadline = startedAt + AUTOMIX_HANDOFF_WATCHDOG_WINDOW_MS
            TrackLog.i(
                "Orb",
                "AUTOMIX_HANDOFF watchdog armed id=$mediaId expectedToPlay=$expectedToPlay " +
                    "state=${incoming.playbackState} pos=${incoming.currentPosition}ms " +
                    "buffered=${incoming.bufferedPosition}ms",
                about = mediaId,
            )
            delay(AUTOMIX_HANDOFF_WATCHDOG_ARM_MS)
            var stalledSince: Long? = null
            var lastSignature: String? = null

            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                val live = player
                if (live !== incoming || live.currentMediaItem?.mediaId != mediaId) {
                    TrackLog.d("Orb", "AUTOMIX_HANDOFF watchdog released: owner changed", about = mediaId)
                    return@launch
                }
                val signature = "${live.playbackState}|${live.playWhenReady}|${live.isPlaying}|${live.playbackSuppressionReason}"
                if (signature != lastSignature) {
                    lastSignature = signature
                    TrackLog.d(
                        "Orb",
                        "AUTOMIX_HANDOFF state id=$mediaId state=${live.playbackState} " +
                            "pos=${live.currentPosition}ms buffered=${live.bufferedPosition}ms " +
                            "pwr=${live.playWhenReady} playing=${live.isPlaying} " +
                            "suppression=${live.playbackSuppressionReason}",
                        about = mediaId,
                    )
                }

                // A transient ownership/focus handoff must not strand B in a
                // paused-looking state that only a second user tap fixes. During
                // this very small watchdog window the transition itself proves
                // that playback was intended to continue.
                if (
                    expectedToPlay &&
                    !live.playWhenReady &&
                    SystemClock.elapsedRealtime() - startedAt <= AUTOMIX_HANDOFF_INTENT_REPAIR_MS
                ) {
                    live.playWhenReady = true
                }

                when {
                    live.isPlaying -> {
                        stalledSince = null
                    }
                    live.playbackState == Player.STATE_READY && live.playWhenReady -> {
                        val now = SystemClock.elapsedRealtime()
                        val started = stalledSince ?: now.also { stalledSince = it }
                        if (now - started >= AUTOMIX_READY_NOT_PLAYING_MS) {
                            live.play()
                            if (!live.isPlaying && live.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                                TrackLog.w(
                                    "Orb",
                                    "AUTOMIX_HANDOFF READY-but-silent; forcing transport repair at ${live.currentPosition}ms",
                                    about = mediaId,
                                )
                                repairStalledCurrentPlayback(live, mediaId)
                                return@launch
                            }
                        }
                    }
                    live.playbackState == Player.STATE_BUFFERING ||
                        live.playbackState == Player.STATE_IDLE -> {
                        val now = SystemClock.elapsedRealtime()
                        val started = stalledSince ?: now.also { stalledSince = it }
                        if (now - started >= AUTOMIX_HANDOFF_STALL_MS) {
                            TrackLog.w(
                                "Orb",
                                "AUTOMIX_HANDOFF stalled state=${live.playbackState} at ${live.currentPosition}ms " +
                                    "buffered=${live.bufferedPosition}ms; forcing transport repair",
                                about = mediaId,
                            )
                            repairStalledCurrentPlayback(live, mediaId)
                            return@launch
                        }
                    }
                    live.playbackState == Player.STATE_ENDED -> {
                        val expectedDurationMs = TrackMatcher.secondsOf(
                            live.currentMediaItem?.toSong()?.durationText,
                        )?.times(1_000L)
                        val clearlyEarly = expectedDurationMs?.let { expected ->
                            expected - live.currentPosition >= AUTOMIX_EARLY_EOS_GUARD_MS
                        } == true
                        val suspiciousShort = expectedDurationMs == null &&
                            live.currentPosition < AUTOMIX_EARLY_EOS_UNKNOWN_DURATION_MS

                        if (expectedToPlay && (clearlyEarly || suspiciousShort)) {
                            TrackLog.w(
                                "Orb",
                                "AUTOMIX_HANDOFF early EOS id=$mediaId pos=${live.currentPosition}ms " +
                                    "expected=${expectedDurationMs ?: -1L}ms; forcing fresh-route repair",
                                about = mediaId,
                            )
                            repairStalledCurrentPlayback(
                                expectedPlayer = live,
                                mediaId = mediaId,
                                aggressive = true,
                            )
                        } else {
                            TrackLog.d(
                                "Orb",
                                "AUTOMIX_HANDOFF ended normally id=$mediaId pos=${live.currentPosition}ms " +
                                    "expected=${expectedDurationMs ?: -1L}ms",
                                about = mediaId,
                            )
                        }
                        return@launch
                    }
                }

                delay(AUTOMIX_HANDOFF_WATCHDOG_POLL_MS)
            }
        }
    }

    /**
     * Repairs a handoff stall without throwing away the queue or musical plan.
     * Preferred prepared routes are demoted to the already-resolved official
     * fallback first; otherwise the same current item is rebuilt in place to
     * restart a loader that stopped making progress. Position and playWhenReady
     * are preserved.
     */
    private suspend fun repairStalledCurrentPlayback(
        expectedPlayer: ExoPlayer,
        mediaId: String,
        aggressive: Boolean = false,
    ) {
        val currentIndex = expectedPlayer.currentMediaItemIndex
        if (player !== expectedPlayer ||
            expectedPlayer.currentMediaItem?.mediaId != mediaId ||
            currentIndex !in 0 until expectedPlayer.mediaItemCount
        ) return

        val repairEpoch = ++transportRecoveryEpoch
        val item = expectedPlayer.getMediaItemAt(currentIndex)
        val position = expectedPlayer.currentPosition.coerceAtLeast(0L)
        val route = item.localConfiguration?.uri?.getQueryParameter("qp")
        val prime = freshQueuedQualityPrime(mediaId)

        val fallbackReady = if ((route == "lossless" || route == "hiq") && prime != null) {
            withContext(Dispatchers.IO) {
                warmQueuedStartup(
                    item = item,
                    prime = prime,
                    bytes = QUEUED_FALLBACK_WARM_BYTES,
                    forceFallback = true,
                )
            }
        } else {
            false
        }

        if (repairEpoch != transportRecoveryEpoch ||
            player !== expectedPlayer ||
            expectedPlayer.currentMediaItem?.mediaId != mediaId
        ) return

        val currentUri = item.localConfiguration?.uri
        val recoveringUpgrade = currentUri?.getQueryParameter(QualityUpgrade.MARKER) != null

        // A second no-progress recovery means reopening the exact same resolved
        // route did not help. Burn only the current rendition and resolver
        // choice so the next open can obtain a fresh CDN/source without
        // destroying the queue or moving the playhead.
        if (aggressive && !recoveringUpgrade && currentUri != null) {
            currentUri.getQueryParameter("v")?.let(StreamChoice::forget)
            withContext(Dispatchers.IO) {
                AudioCache.discardRendition(currentUri)
            }
        }

        val repairedToFallback = !recoveringUpgrade &&
            fallbackReady && prime != null &&
            applyPreparedQualityHint(
                player = expectedPlayer,
                index = currentIndex,
                prime = prime,
                forceFallback = true,
                markCurrentSwap = true,
            )

        if (recoveringUpgrade && currentUri != null) {
            // If the post-handoff stall came from a live quality swap, reopen
            // the exact pre-upgrade queue route instead of retrying the marked
            // rendition that just stopped making progress. The q= marker is the
            // only difference QualityUpgrade adds to that URI.
            val baseUri = currentUri.buildUpon().clearQuery().apply {
                currentUri.queryParameterNames.forEach { name ->
                    if (name == QualityUpgrade.MARKER) return@forEach
                    currentUri.getQueryParameters(name).forEach { value ->
                        appendQueryParameter(name, value)
                    }
                }
            }.build()
            QualityUpgrade.forget(mediaId)
            QualityUpgrade.refuseUpgrades(mediaId)
            NerdStats.clearDeclared(mediaId)
            swappingMediaId = mediaId
            expectedPlayer.replaceMediaItem(
                currentIndex,
                item.buildUpon().setUri(baseUri).setMimeType(null).build(),
            )
        } else if (!repairedToFallback) {
            // Replacing the item, even with equivalent metadata/URI, forces
            // Media3 to tear down the wedged loader and open a fresh source.
            swappingMediaId = mediaId
            expectedPlayer.replaceMediaItem(currentIndex, item)
        }

        if (repairEpoch != transportRecoveryEpoch ||
            player !== expectedPlayer ||
            expectedPlayer.currentMediaItem?.mediaId != mediaId
        ) return

        expectedPlayer.seekTo(currentIndex, position)
        expectedPlayer.prepare()
        expectedPlayer.playWhenReady = true
        expectedPlayer.play()
        TrackLog.w(
            "Orb",
            "playback stall recovered for $mediaId " +
                "(route=${route ?: "default"}, fallback=$repairedToFallback, " +
                "upgrade=$recoveringUpgrade)",
            about = mediaId,
        )
    }

    /**
     * Only one player may handle audio focus at a time.
     *
     * Two focus-handling players in one process fight each other: the standby
     * taking focus as it starts would have Media3 pause the player that lost it,
     * cutting the outgoing track dead instead of fading it. Focus follows the
     * session, and so does "becoming noisy" — unplugging headphones should pause
     * the song you are listening to, which is whichever one the session is on.
     */
    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    /**
     * Where a tap on the session lands. Media3 uses this both as the media
     * notification's contentIntent and as the session activity handed to the
     * platform MediaSession.
     *
     * This is not cosmetic on One UI: Samsung's Now Bar / Live Notification
     * chip is a launcher for the session, so a session that advertises nowhere
     * to go is skipped and only the plain shade notification survives. Same
     * reason the notification itself was previously un-tappable.
     */
    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
            // MainActivity is singleTask, so this resumes the existing task
            // rather than stacking a second copy of the UI. The custom action
            // tells the Activity that a session/Live Notification tap should
            // land directly on Now Playing instead of the last app page.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun registerCurrentPlay() {
        player?.currentMediaItem?.mediaId?.let(PlaybackTracker::onPlaying)
    }

    /**
     * Everything that has to happen when a different song becomes the one
     * playing: history, scrobbles, ListenBrainz, the sleep timer, read-ahead
     * and the second look for a better copy.
     *
     * Called from two places, and it has to be, because there are now two ways
     * for the current song to change. ExoPlayer's own item transition covers
     * the ordinary ones — the queue advancing, a skip, a repeat. A crossfade
     * covers none of them: the incoming track starts life as the *first* item
     * of the other player, which fires a transition on a player nothing is
     * listening to yet, so [adoptPlayer] calls this by hand at the handoff.
     * Before that split existed this logic lived inside the callback, and
     * moving to two players would have silently stopped every crossfaded track
     * from being scrobbled, recorded, or read ahead for.
     *
     * @param previousEnded whether the song being replaced ran to its end, as
     *   opposed to being skipped past. Only an ended song is a listen.
     * @param alreadyAudible whether the track was already sounding when it
     *   became current, which is only true of a crossfade handoff.
     */
    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        refreshWatchArtwork(mediaItem)
        if (!alreadyAudible) {
            automixHandoffRecoveryJob?.cancel()
            automixHandoffRecoveryJob = null
            automixPostHandoffWorkJob?.cancel()
            automixPostHandoffWorkJob = null
        }
        // A new track is a clean slate for [recoverFrom]. The count
        // exists to stop one broken stream looping, not to hold a
        // grudge against a track for the rest of the session.
        recoveries.clear()
        offlineAutoplayStalledFor = null
        playbackStallRecoveryJob?.cancel()
        playbackStallRecoveryJob = null
        playbackStallRecoveryCount = 0
        firstAudioRecoveryJob?.cancel()
        firstAudioRecoveryJob = null
        firstAudioRecoveryCount = 0
        firstAudioBackgroundWorkJob?.cancel()
        firstAudioBackgroundWorkJob = null
        currentTrackHasBeenAudible = alreadyAudible
        currentTrackFirstAudibleAtMs = if (alreadyAudible) SystemClock.elapsedRealtime() else null

        // Where the wait starts, for the log in onIsPlayingChanged — unless
        // there was no wait. A crossfaded track has been audible for as long as
        // it has been current, so `onIsPlayingChanged` will never fire for it
        // and an armed timer would sit there until some unrelated buffering
        // blip tripped it, reporting a wait of seconds for a track that started
        // instantly. Measured one at 16871ms.
        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "Orb",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem?.mediaId,
            )
        }
        // And the same instant on the wall clock, which is the one
        // logcat stamps its lines with — see [TrackLog].
        mediaItem?.mediaId?.let { mediaId ->
            rememberOpeningQualityDecision(mediaId)
            val route = exoPlayer.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.getQueryParameter("qp")
            val preparedStream = freshQueuedQualityPrime(mediaId)?.preferredStream
            val preparedAtCeiling = when (route) {
                "lossless", "hiq" -> preparedStream?.let { SourceResolver.isAtRequestedQualityCeiling(it.format) } == true
                else -> false
            }
            if ((route != null && !preparedAtCeiling) || (alreadyAudible && route == null)) {
                // If B had to enter on Opus (or on Hi-Q while Maximum still wants
                // Lossless), the handoff is not a final quality verdict. Re-arm the
                // normal live upgrade so playback never waits for quality, yet B can
                // still climb after it becomes current. Crossfaded unprepared tracks
                // also need this because their resolver verdict may have happened while
                // they were still only the standby player.
                QualityUpgrade.rearmAfterHandoff(mediaId)
            }
            TrackLog.onTrackStarted(mediaId)
            ManualQueuePins.consume(mediaId)
        }
        TrackLog.d(
            "Orb",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
        )

        // currentPosition already belongs to the new item by now, so
        // the outgoing track is closed out on the last sampled value.
        PlaybackTracker.onTrackChanged(
            positionSeconds = lastPositionSeconds,
            nextVideoId = mediaItem?.mediaId,
            nextPositionSeconds = (exoPlayer.currentPosition / 1000L).coerceAtLeast(0L),
            previousEnded = previousEnded,
        )
        lastPositionSeconds = 0

        // Scrobbling: stop old song, start new song
        scrobbleManager?.onSongStop()
        val newSong = mediaItem?.toSong()
        newSong?.let(AutoplaySession::rememberPlayed)
        scheduleAutoplayRefill()
        ensureLocalNativeFormat(newSong)
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        if (exoPlayer.isPlaying) scrobbleManager?.onSongStart(newSong, durationMs)
        socialPlaybackReporter.onTrackBecameCurrent(
            song = newSong,
            previousEnded = previousEnded,
            isPlaying = exoPlayer.isPlaying,
            durationMs = durationMs,
            positionMs = exoPlayer.currentPosition,
        )

        // ListenBrainz: submit finished for old song, playing_now for new song.
        // The finished listen only counts when the track actually ended —
        // an auto-advance, a repeat, or a crossfade at the very end. A
        // manual skip (SEEK) means the song wasn't listened to, so it must
        // not be scrobbled.
        val ended = previousEnded
        val prevSong = listenBrainzSong
        val prevStart = listenBrainzStartMs
        if (prevSong != null && ended) {
            submitListenBrainzFinished(prevSong, prevStart, listenBrainzDurationMs)
        }
        listenBrainzSong = newSong
        listenBrainzStartMs = System.currentTimeMillis()
        listenBrainzDurationMs = durationMs
        if (newSong != null) {
            submitListenBrainzPlayingNow(newSong, 0L, durationMs)
        }

        // Discord: the whole of "live updating" for a card whose bar Discord
        // draws itself. Only a track change needs a new presence; the countdown
        // in between is Discord's own arithmetic.
        if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)

        // "Sleep after this song": the queue moving on by itself is the
        // moment the track the user meant has finished. REPEAT counts
        // too, or the timer would never fire with repeat-one on.
        if (ended && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        if (exoPlayer.isPlaying) registerCurrentPlay()

        // A -> B handoff is a latency-sensitive window. B already has enough
        // audio to enter the mix, but on mobile data that runway can be consumed
        // quickly if C analysis/prefetch and B's quality hunt all start on the
        // same frame. Give B a brief exclusive network window first. Ordinary
        // non-Automix track changes keep the existing eager behaviour.
        upgradeJob?.cancel()
        earlyUpgradeProbeJob?.cancel()
        if (alreadyAudible) {
            scheduleAutomixPostHandoffWork(exoPlayer, mediaItem?.mediaId)
        } else if (exoPlayer.isPlaying) {
            // Gapless/non-Automix transitions can remain continuously isPlaying=true and therefore
            // never emit a fresh onIsPlayingChanged(true). Treat this as first audio for the new
            // item, but still give it the same protected runway before optional work starts.
            currentTrackHasBeenAudible = true
            currentTrackFirstAudibleAtMs = SystemClock.elapsedRealtime()
            schedulePostFirstAudioWork(exoPlayer)
        } else {
            // Nothing optional starts here anymore. onTrackBecameCurrent happens before the
            // first sample is guaranteed, so doing queue prefetch/quality work here put exactly
            // the traffic we do not need on the critical Play -> first-note path.
            // onIsPlayingChanged(true) owns the release through schedulePostFirstAudioWork().
            TrackLog.d(
                "Orb",
                "first-note exclusive window; quality/read-ahead held until audio is stable",
                about = mediaItem?.mediaId,
            )
        }
        saveQueue()
        // Cleared rather than re-published. The renderer is still
        // configured for the track that just ended at this point, so
        // reading the format here reports the *previous* song — which
        // is how a lossy track spent its whole resolve showing the
        // "Hi-Res Lossless" badge the track before it had earned.
        // Nothing measured is better than something wrong, and the
        // gap is exactly when "Loading lossless" should be showing
        // instead. The periodic sampler below and
        // onAudioInputFormatChanged both re-publish once the decoder
        // has actually settled on this track, so the same-format case
        // the old call was here to cover is still covered.
        NerdStats.current.value = null
    }

    /**
     * Releases non-essential work only after a freshly selected track is already audible.
     *
     * Stage 1 warms only cheap fallback/read-ahead after a brief first-buffer grace. Stage 2
     * starts quality discovery later. The requested-quality tier is therefore never on the
     * critical path to first audio: Opus/AAC fallback is allowed to start immediately and can
     * be upgraded in-place once playback has a real cushion.
     */
    private fun schedulePostFirstAudioWork(exoPlayer: ExoPlayer) {
        val mediaId = exoPlayer.currentMediaItem?.mediaId ?: return
        firstAudioBackgroundWorkJob?.cancel()
        firstAudioBackgroundWorkJob = scope.launch(TrackLog.about(mediaId)) {
            delay(FIRST_AUDIO_FALLBACK_STABILIZE_MS)
            val firstStage = player ?: return@launch
            if (
                firstStage !== exoPlayer ||
                firstStage.currentMediaItem?.mediaId != mediaId ||
                !firstStage.isPlaying
            ) return@launch

            // Cheap continuity work first. This is deliberately separated from the quality race.
            prefetchAround(firstStage)
            requestCurrentAutomixPairAnalysis(firstStage)

            delay((FIRST_AUDIO_QUALITY_GRACE_MS - FIRST_AUDIO_FALLBACK_STABILIZE_MS).coerceAtLeast(0L))
            val qualityStage = player ?: return@launch
            if (
                qualityStage !== exoPlayer ||
                qualityStage.currentMediaItem?.mediaId != mediaId ||
                !qualityStage.isPlaying
            ) return@launch

            TrackLog.d(
                "Orb",
                "first-note protected; releasing background quality work after ${FIRST_AUDIO_QUALITY_GRACE_MS}ms",
                about = mediaId,
            )
            lookForBetterCopy(qualityStage)
            primeImmediateSuccessorQuality(qualityStage)
            scheduleEarlyQualityProbes(qualityStage)
        }
    }

    /**
     * Starts C analysis, read-ahead and quality work only after B has had a
     * protected post-Automix runway. This preserves the A -> B -> C ordering,
     * but removes a burst of competing requests from B's most fragile seconds.
     */
    private fun scheduleAutomixPostHandoffWork(exoPlayer: ExoPlayer, mediaId: String?) {
        val id = mediaId ?: return
        automixPostHandoffWorkJob?.cancel()
        automixPostHandoffWorkJob = scope.launch(TrackLog.about(id)) {
            // Playback continuity outranks C analysis, read-ahead and quality discovery. Do not
            // release a burst of optional network work merely because a fixed timer expired; wait
            // until B itself is audibly advancing with a real runway. For short tracks the target
            // is clamped to what can actually exist before the end.
            while (isActive) {
                val live = player
                if (live !== exoPlayer || live.currentMediaItem?.mediaId != id) return@launch
                if (!live.playWhenReady) return@launch

                val ahead = (live.bufferedPosition - live.currentPosition).coerceAtLeast(0L)
                val remaining = live.duration
                    .takeIf { it != C.TIME_UNSET && it > 0L }
                    ?.minus(live.currentPosition)
                    ?.coerceAtLeast(0L)
                val safeTarget = remaining
                    ?.let { minOf(AUTOMIX_POST_HANDOFF_SAFE_BUFFER_MS, (it - AUTOMIX_POST_HANDOFF_END_GUARD_MS).coerceAtLeast(AUTOMIX_POST_HANDOFF_MIN_SAFE_BUFFER_MS)) }
                    ?: AUTOMIX_POST_HANDOFF_SAFE_BUFFER_MS

                if (live.isPlaying && ahead >= safeTarget) break
                delay(AUTOMIX_HANDOFF_WATCHDOG_POLL_MS)
            }

            val live = player
            if (live !== exoPlayer || live.currentMediaItem?.mediaId != id || !live.isPlaying) return@launch
            prefetchAround(live)
            requestCurrentAutomixPairAnalysis(live)
            primeImmediateSuccessorQuality(live)
            lookForBetterCopy(live)
            scheduleEarlyQualityProbes(live)
        }
    }

    /**
     * Loads the queue from the last session so the app opens on the track it
     * was left on, rather than with nothing in the mini player.
     *
     * Deliberately no `prepare()`. Preparing would resolve the stream — a
     * NewPipe extraction over the network — on every cold start, for a track
     * that may never be played, and would post a media notification for a
     * session nobody has touched yet (Media3 shows one as soon as the player
     * leaves IDLE with a non-empty queue). Left idle, restoring costs nothing:
     * [MediaSession] routes every play request through
     * `Util.handlePlayButtonAction`, which prepares an idle player first, so
     * the mini player, the notification and Bluetooth all resume from here
     * without knowing the queue was cold.
     */
    private fun restoreLastQueue(player: ExoPlayer) {
        val last = LastPlayed.load() ?: return
        player.setMediaItems(
            last.songs.mapIndexed { index, song ->
                song.toMediaItem(albumSequential = last.albumSequentialFlags.getOrNull(index) == true)
            },
            last.index,
            last.positionMs,
        )
    }

    /** The background hunt for a better copy of whatever is playing. */
    private var upgradeJob: Job? = null

    /**
     * Short first-seconds backstop for streams whose resolver finishes just
     * after the first player callback. The ordinary progress sampler stays at
     * five seconds for battery/thermal reasons; this bounded probe exists only
     * through the opening six-second quality-priority window of a newly playing track.
     */
    private var earlyUpgradeProbeJob: Job? = null

    /** Which track [upgradeJob] is hunting for — see [lookForBetterCopy]. */
    private var upgradeFor: String? = null

    /**
     * How many times each track has been picked up off the floor, so a stream
     * that fails the same way every time stops rather than loops. Reset when
     * the queue genuinely moves on, not when a track merely re-prepares.
     */
    private val recoveries = mutableMapOf<String, Int>()

    /** AutoPlay item that could not be reached offline and should resume when the network returns. */
    private var offlineAutoplayStalledFor: String? = null

    /**
     * Puts a track that died mid-read back on its feet.
     *
     * Two things get thrown away before trying again, because both have been
     * seen to be the actual fault and neither is visible from the exception:
     *
     *  - The cached bytes. An entry filled from two different files reads
     *    fine until playback reaches the seam and then throws forever, and no
     *    number of retries against the same entry will do anything else.
     *  - The choice of who serves the track. If the source that was picked is
     *    the one handing over something unreadable, resolving again from
     *    scratch is the only way to land anywhere else.
     *
     * The position is kept: this should look like a hiccup, not like the song
     * starting over.
     */
    /** Returns the HTTP status buried inside a Media3 source error, if any. */
    private fun PlaybackException.httpResponseCode(): Int? {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    /**
     * Media3 wraps OkHttp's malformed-URL failure as a generic runtime-check
     * playback error, so the HTTP status path cannot see it. Detect it from the
     * cause chain and treat it as a poisoned route rather than retrying the same
     * remembered URL until [MAX_RECOVERIES] is exhausted.
     */
    private fun PlaybackException.isMalformedStreamUrl(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException &&
                cause.message?.contains("Malformed URL", ignoreCase = true) == true
            ) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * A proved quality rendition is also a transport escape hatch.
     *
     * Normal upgrades wait until Automix is completely clear because a source
     * replacement is audible. A dead carrier is different: there is already
     * silence. If the upgrade was auditioned and shelved, switching to it now
     * preserves playback instead of retrying a known-dead signed URL.
     */
    private fun emergencyFailoverToProvedUpgrade(
        live: ExoPlayer,
        item: MediaItem,
        mediaId: String,
        position: Long,
        stream: SourceStream,
    ): Boolean {
        val currentUri = item.localConfiguration?.uri ?: return false
        if (QualityUpgrade.cacheTag(currentUri) != null) return false
        val index = live.currentMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until live.mediaItemCount) return false

        val shouldPlay = live.playWhenReady || currentTrackHasBeenAudible
        val upgradedUri = QualityUpgrade.upgradedUri(currentUri.toString())

        // Stop the ordinary upgrade worker from racing this emergency swap.
        if (upgradeFor == mediaId) {
            upgradeJob?.cancel()
            upgradeJob = null
            upgradeFor = null
        }
        hideQualitySearchHint(mediaId)

        QualityUpgrade.force(mediaId, stream)
        QualityUpgrade.unshelve(mediaId)
        swappingMediaId = mediaId
        swapCutAt = SystemClock.elapsedRealtime()
        live.replaceMediaItem(index, mediaItemForStream(item, upgradedUri, stream))
        live.seekTo(index, position)
        live.prepare()
        live.playWhenReady = shouldPlay
        if (shouldPlay) live.play()
        recoveries.remove(mediaId)
        playbackStallRecoveryCount = 0

        TrackLog.w(
            "Orb",
            "transport failover for $mediaId at ${position}ms -> ${stream.format.summary} " +
                "(${Uri.parse(stream.url).host})",
            about = mediaId,
        )
        scope.launch(Dispatchers.IO + TrackLog.about(mediaId)) {
            // The dead carrier and the proved upgrade use separate cache keys.
            // Drop only the broken base entry after ownership has moved.
            runCatching { AudioCache.discard(currentUri) }
        }
        return true
    }

    private fun Throwable.isSonicAudioProcessorFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.javaClass.name.contains("Sonic", ignoreCase = true) ||
                current.stackTrace.any { frame -> frame.className.contains("Sonic", ignoreCase = true) }
            ) return true
            current = current.cause
        }
        return false
    }

    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val reportedPosition = player.currentPosition.coerceAtLeast(0L)
        // Some source errors reset Media3's currentPosition before onPlayerError arrives.
        // Never turn a mid-track recovery into a restart at 0:00; fall back to the
        // last progress sample when it belongs to this same track.
        val sampledPosition = (lastPositionSeconds * 1000L).coerceAtLeast(0L)
        val position = maxOf(reportedPosition, sampledPosition)

        // An AutoPlay recommendation that is not on disk cannot be repaired by
        // retrying the network while the device is offline. Prefer another
        // already-local AutoPlay item; if none exists, stop cleanly and remember
        // this one so the connectivity watcher can resume it automatically.
        if (AppSettings.meteredConnection.value == null && item.fromAutoplay) {
            fun offlineReady(candidate: MediaItem): Boolean {
                val song = candidate.toSong()
                if (song.localUri != null || song.localPath != null) return true
                val candidateUri = candidate.localConfiguration?.uri ?: return false
                return AudioCache.renditionsOf(candidateUri).any { it.isComplete }
            }

            val nextOffline = ((player.currentMediaItemIndex + 1) until player.mediaItemCount)
                .firstOrNull { index ->
                    val candidate = player.getMediaItemAt(index)
                    candidate.fromAutoplay && offlineReady(candidate)
                }
            if (nextOffline != null) {
                TrackLog.d(
                    "Orb",
                    "offline AutoPlay skipped unavailable $mediaId -> " +
                            player.getMediaItemAt(nextOffline).mediaId,
                    about = mediaId,
                )
                offlineAutoplayStalledFor = null
                player.seekTo(nextOffline, 0L)
                player.prepare()
                player.play()
            } else {
                offlineAutoplayStalledFor = mediaId
                TrackLog.d(
                    "Orb",
                    "offline AutoPlay has no more locally available tracks; waiting for network",
                    about = mediaId,
                )
                player.stop()
            }
            return
        }

        // Sonic is renderer/DSP state, not a transport attempt. Handle it before touching
        // [recoveries]; otherwise two Automix DSP crashes consume the whole transport budget and
        // the *first* later cellular/DNS failure is incorrectly treated as attempt 3/give-up.
        if (error.isSonicAudioProcessorFailure()) {
            TrackLog.w(
                "Orb",
                "Automix Sonic DSP failed for $mediaId at ${position}ms; " +
                    "preserving source and reopening at native pitch",
                error,
                about = mediaId,
            )
            player.setPlaybackParameters(PlaybackParameters(AppSettings.playbackSpeed.value, 1f))
            scope.launch(TrackLog.about(mediaId)) {
                delay(SONIC_RECOVERY_DELAY_MS)
                withContext(Dispatchers.Main) {
                    if (this@PlaybackService.player !== player ||
                        player.currentMediaItem?.mediaId != mediaId
                    ) return@withContext
                    player.seekTo(player.currentMediaItemIndex, position)
                    player.prepare()
                    player.playWhenReady = true
                    player.play()
                }
            }
            return
        }

        val recoveryEpoch = ++transportRecoveryEpoch
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "Orb",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )

        val httpCode = error.httpResponseCode()
        val malformedRoute = error.isMalformedStreamUrl()
        val accessRefusal = StreamResolver.isLoginRequiredFailure(error) ||
            StreamResolver.isPremiumOnlyFailure(error)
        val hardRouteRefusal = httpCode == 401 || httpCode == 403 || malformedRoute || accessRefusal
        val upgradedRouteFailed = uri?.let(QualityUpgrade::cacheTag) != null
        val failedUpgradedLossless = upgradedRouteFailed &&
            (NerdStats.declaredFormat(mediaId)?.isLossless == true ||
                QualityUpgrade.forcedStream(uri!!)?.format?.isLossless == true)
        val videoId = uri?.getQueryParameter("v") ?: mediaId
        val failedSubstitute = StreamChoice.isSubstitute(videoId)
        if (hardRouteRefusal) {
            // A signed carrier that answered 401/403 is not a transient decoder
            // hiccup. Every layer that can hand the same URL back must forget
            // it before prepare() is allowed to retry.
            StreamChoice.forget(videoId)
            PlaybackStreamStore.forget(videoId)
            StreamResolver.invalidatePlaybackUrl(videoId)
            if (failedSubstitute) {
                StreamChoice.refuseSubstitutes(videoId)
                QualityUpgrade.refuseUpgrades(videoId)
            }
            if (malformedRoute) {
                TrackLog.w(
                    "Orb",
                    "malformed stream route discarded for $videoId; next attempt must resolve a fresh carrier",
                    about = mediaId,
                )
            }

            // Only the route that actually failed is poisoned. If a prepared
            // Lossless/Hi-Q route died, keep the Opus fallback: that is exactly
            // what recovery should fall back to. The stale prepared fallback
            // problem applies to qp=opus, where reusing it reproduces the same
            // signed URL and the same 403 at the same chunk boundary.
            if (uri?.getQueryParameter("qp") == "opus") {
                freshQueuedQualityPrime(videoId)?.let { prime ->
                    if (prime.opusFallback != null) {
                        queuedQualityPrimes[videoId] = prime.copy(
                            opusFallback = null,
                            decidedAtMs = SystemClock.elapsedRealtime(),
                        )
                        TrackLog.w(
                            "Orb",
                            "HTTP $httpCode invalidated prepared fallback for $videoId; next retry must mint a fresh carrier",
                            about = mediaId,
                        )
                    }
                }
            }

            // If the quality worker already proved another rendition, transport
            // recovery outranks the normal 'don't swap during crossfade' rule.
            // The old carrier is dead, so there is no blend left to protect.
            val proved = QualityUpgrade.shelvedFor(mediaId)
            if (proved != null && emergencyFailoverToProvedUpgrade(player, item, mediaId, position, proved)) {
                return
            }
        }
        // Giving up on the *retry*, not on everything below it.
        //
        // A track that has exhausted its attempts is not finished with.
        // [recoveries] is cleared the moment any track becomes current, so the
        // listener who presses play again gets a fresh count — and used to get,
        // along with it, the exact URL that had just failed three times.
        // [StreamChoice] outlives this method by [StreamChoice.TTL_MS], and the
        // resolving factory reads it *before* it resolves anything, so the
        // replay failed instantly and in silence: no resolve logged, no lookup
        // attempted, and none of the refusals recorded below ever consulted,
        // because reaching them means getting as far as resolving. Fifteen
        // minutes of a track that cannot be played and does not even try, which
        // to the listener is a track that is permanently broken. Reported as
        // "sometimes songs don't play even if I've played it before", and the
        // 1.4 log of one shows it exactly — a selection, five seconds, a 404,
        // and not one resolver line in between.
        //
        // So everything from here to the discard runs either way, and only the
        // seek-and-prepare at the end is skipped.
        val givingUp = attempts > MAX_RECOVERIES ||
            (accessRefusal && !failedSubstitute && !upgradedRouteFailed &&
                uri?.getQueryParameter("qp") !in setOf("lossless", "hiq"))
        if (givingUp) {
            TrackLog.w("Orb", "$mediaId source access refused or exhausted $attempts attempts; no identical retry", about = mediaId)
        }
        // The upgraded rendition goes with the cache entry it lived in, so the
        // marker on the URI would otherwise point at nothing.
        QualityUpgrade.forget(mediaId)
        // A track that died on an upgraded URI died on the *upgrade*, and it
        // must not be offered that same swap again the moment it recovers.
        // Left unrecorded, the second look starts over on the retry, finds the
        // same FLAC at the same dead URL, cuts the audio for it again, and
        // fails again — twice more before [MAX_RECOVERIES] stops it. Observed
        // on a Tidal URL answering ERROR_CODE_IO_BAD_HTTP_STATUS.
        if (uri?.let(QualityUpgrade::cacheTag) != null) {
            QualityUpgrade.refuseUpgrades(mediaId)
        }
        // Whatever failed took its claimed format with it. The stream that
        // recovers is a different one and has not promised anything yet, so
        // leaving the old claim behind is how a badge earned by a FLAC ends up
        // sitting over the Opus that replaced it.
        NerdStats.clearDeclared(mediaId)
        // A track that died on a substituted stream died on the *substitution*,
        // and the retry must not be free to make the same one again. The lookup
        // behind it is deterministic and, by the second attempt, cached — so it
        // wins the race against YouTube by the same margin it won it the first
        // time and hands back the identical dead URL, until [MAX_RECOVERIES]
        // stops trying. That is a track that never plays at all while a working
        // YouTube URL sits in [StreamResolver]'s cache, resolved and unused.
        // The same reasoning as [QualityUpgrade.refuseUpgrades] above, for the
        // substitution that happens *before* the first note rather than after.
        // Read before the forget below, which is what clears the evidence.
        uri?.getQueryParameter("v")?.takeIf { failedSubstitute }?.let { videoId ->
            StreamChoice.refuseSubstitutes(videoId)
            freshQueuedQualityPrime(videoId)?.let { oldPrime ->
                queuedQualityPrimes[videoId] = oldPrime.copy(
                    preferredStream = null,
                    decidedAtMs = SystemClock.elapsedRealtime(),
                )
            }
            TrackLog.w(
                "Orb",
                "$videoId broke on a substituted stream; trying Hi-Q before the Opus fallback",
                about = mediaId,
            )
            // And no swapping back to it mid-song either: the second look asks
            // the same catalogues the same question and would cut the audio that
            // just recovered to land on the same refusal.
            QualityUpgrade.refuseUpgrades(videoId)
        }
        uri?.getQueryParameter("v")?.let(StreamChoice::forget)
        val failedQueueRoute = uri?.getQueryParameter("qp")
        val queuedPreferredRouteFailed =
            failedQueueRoute in setOf("lossless", "hiq") || upgradedRouteFailed
        val losslessRouteFailed = failedQueueRoute == "lossless" ||
            (failedSubstitute && !queuedPreferredRouteFailed) ||
            failedUpgradedLossless
        scope.launch(TrackLog.about(mediaId)) {
            // A pre-resolved queue fallback uses a *different* cache key, so it
            // does not need the ordinary 350 ms release window or a scorched-
            // earth discard of every rendition. Drop only the failed Lossless
            // entry and hand off to the primed Opus route almost immediately.
            delay(
                if (queuedPreferredRouteFailed || hardRouteRefusal) QUEUED_FALLBACK_RETRY_DELAY_MS
                else RECOVERY_DELAY_MS
            )
            uri?.let {
                withContext(Dispatchers.IO) {
                    if (queuedPreferredRouteFailed) AudioCache.discardRendition(it)
                    else AudioCache.discard(it)
                }
            }
            // The bytes go even when nothing is going to be prepared after
            // them. A half-filled entry whose owner has just been forgotten is
            // the seam this file's [StreamChoice] note is about: the next play
            // resolves freely, lands on a different source, and streams it into
            // the middle of the last one. Releasing the choice without dropping
            // the bytes would trade one stuck track for a corrupt one.
            if (givingUp) return@launch

            // A Lossless route can be a valid URL yet deliver no decodable data.
            // First try real Hi-Q AAC; if that cannot be resolved, descend to
            // the official Opus carrier. Never ask the broken Lossless URL again.
            val hiQualityFallback = if (losslessRouteFailed) {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(LOSSLESS_TO_HIQ_RECOVERY_MS) {
                        SourceResolver.fallbackHiQuality(
                            target = TrackMatcher.targetOf(item.toSong()),
                            videoId = videoId,
                            budgetMs = LOSSLESS_TO_HIQ_RECOVERY_MS,
                        )
                    }
                }
            } else null

            withContext(Dispatchers.Main) {
                if (recoveryEpoch != transportRecoveryEpoch) {
                    TrackLog.d(
                        "Orb",
                        "discarding stale transport recovery for $mediaId (epoch=$recoveryEpoch)",
                        about = mediaId,
                    )
                    return@withContext
                }
                val player = this@PlaybackService.player ?: return@withContext
                val current = player.currentMediaItem ?: return@withContext
                if (current.mediaId != mediaId) return@withContext
                if (queuedPreferredRouteFailed || failedSubstitute) {
                    val queueVideoId = uri?.getQueryParameter("v") ?: mediaId
                    val fallbackPrime = freshQueuedQualityPrime(queueVideoId)?.copy(
                        preferredStream = hiQualityFallback,
                        decidedAtMs = SystemClock.elapsedRealtime(),
                    ) ?: QueuedQualityPrime(
                        preferredStream = hiQualityFallback,
                        opusFallback = null,
                    )
                    queuedQualityPrimes[queueVideoId] = fallbackPrime
                    // A failed Lossless copy is not a failed Hi-Q copy. A failed
                    // Hi-Q copy, however, goes straight to Opus (no repeated AAC).
                    // The URI marker prevents splicing audio from different formats
                    // into the same cache entry and strips a dead upgrade marker.
                    applyPreparedQualityHint(
                        player = player,
                        index = player.currentMediaItemIndex,
                        prime = fallbackPrime,
                        forceFallback = hiQualityFallback == null,
                        markCurrentSwap = true,
                    )
                    TrackLog.w(
                        "Orb",
                        "failed preferred source for $mediaId: recovering via " +
                            (if (hiQualityFallback != null) "Hi-Q AAC" else "Opus fallback"),
                        about = mediaId,
                    )
                } else {
                    TrackLog.d("Orb", "retrying $mediaId from ${position}ms")
                }
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
                if (hardRouteRefusal || queuedPreferredRouteFailed || failedSubstitute) {
                    // Source errors can leave Media3 READY intent intact on
                    // some devices and clear it on others. Recovery owns this
                    // retry, so restore the listener's original intent.
                    player.playWhenReady = true
                    player.play()
                }
            }
        }
    }

    /**
     * The track whose item this service is about to replace under it, so that
     * [Player.Listener.onMediaItemTransition] can tell a quality swap from the
     * queue actually moving on. Cleared by the transition it describes.
     */
    private var swappingMediaId: String? = null

    /**
     * When the audio was last cut for a quality swap, so the analytics listener
     * can say how long it stayed cut. Null except across a swap.
     */
    private var swapCutAt: Long? = null

    /**
     * The track [ExoPlayer.getAudioFormat] is currently describing.
     *
     * `audioFormat` is a property of the *renderer*, not of the queue item, and
     * it keeps naming the outgoing track's codec until the renderer has read a
     * sample of the incoming one. Anything that asks "what is playing right
     * now" in the moments after a transition is therefore told about the track
     * before it, and [adoptCachedTrack] is asked exactly there — a queue
     * advance is one of the places [lookForBetterCopy] runs from.
     *
     * Observed: 'Harleys In Hawaii' came up fifteen milliseconds after the
     * queue moved onto it, twenty seconds after the previous track had been
     * upgraded to FLAC. The renderer still said `audio/flac`, so a WebM Opus
     * stream — verified by the `1A 45 DF A3` on its cache entry — was written
     * off as "already lossless from cache" and, because that verdict is
     * recorded once and for good, never offered an upgrade again for the rest
     * of the session.
     */
    private var audioFormatFor: String? = null

    /**
     * Native container metadata for the local track currently selected.
     *
     * This is deliberately separate from [audioFormatFor]: the latter is the
     * decoder's working format, while this is the file's own FLAC/WAV/ALAC
     * resolution and therefore the only safe source for the Lossless vs
     * Hi-Res Lossless label on device files.
     */
    private var localQualityJob: Job? = null
    private var localQualityFor: String? = null
    private var localNativeFormat: StreamFormat? = null

    private fun ensureLocalNativeFormat(song: Song?) {
        val local = song?.takeIf {
            !it.localUri.isNullOrBlank() || !it.localPath.isNullOrBlank()
        }
        if (local == null) {
            if (localQualityFor != null) {
                localQualityJob?.cancel()
                localQualityJob = null
                localQualityFor = null
                localNativeFormat = null
            }
            return
        }

        val mediaId = local.videoId
        if (localQualityFor == mediaId) return

        localQualityJob?.cancel()
        localQualityFor = mediaId
        localNativeFormat = LocalAudioQuality.cached(local)
        if (localNativeFormat != null) return

        localQualityJob = scope.launch {
            val detected = withContext(Dispatchers.IO) {
                LocalAudioQuality.inspect(this@PlaybackService, local)
            }
            if (this@PlaybackService.player?.currentMediaItem?.mediaId != mediaId) return@launch
            localNativeFormat = detected
            // The decoder may already have published before the file header
            // probe completed. Re-publish now so the badge can settle from
            // generic Lossless to the exact native tier without a track change.
            publishNerdStats()
        }
    }

    /**
     * Starts the second look for the playing track, if it settled for less
     * than was asked for — see [QualityUpgrade].
     *
     * Runs at most once per track: [QualityUpgrade.lookAgain] drops the track
     * from its pending set whatever the answer, so the repeated calls this
     * gets cost nothing after the first. It needs to be called from several
     * places for that reason — a track becomes eligible at a different moment
     * depending on how it was reached. Called only from
     * `onIsPlayingChanged`, it fired for the first track of a session and for
     * nothing after it: the queue advancing while already playing is not a
     * change in `isPlaying`, so every track but the first kept a lookup that
     * had already found its FLAC and was never asked for it.
     *
     * Eligibility has two sources, because being resolved and being played are
     * not the same event. A track the resolver saw is already marked; a track
     * served from the disk cache was never resolved at all and is judged here
     * instead — see [adoptCachedTrack] and [QualityUpgrade.adoptUnresolved].
     */
    private fun scheduleEarlyQualityProbes(player: ExoPlayer) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        earlyUpgradeProbeJob?.cancel()
        earlyUpgradeProbeJob = scope.launch(TrackLog.about(mediaId)) {
            var elapsed = 0L
            for (probeAt in EARLY_UPGRADE_PROBE_MS) {
                delay((probeAt - elapsed).coerceAtLeast(0L))
                elapsed = probeAt
                val live = this@PlaybackService.player ?: return@launch
                if (live.currentMediaItem?.mediaId != mediaId) return@launch
                if (!live.playWhenReady) return@launch
                if (!live.isPlaying) continue

                // A resolver can mark the track pending after onIsPlaying(true)
                // has already fired. Re-check throughout the first six seconds so
                // the upgrade begins immediately instead of waiting for the
                // five-second progress sampler. A crossfaded B receives this same
                // probe sequence from the instant it becomes the session track.
                lookForBetterCopy(live)
                if (upgradeFor == mediaId && upgradeJob?.isActive == true) return@launch
            }
        }
    }

    private fun lookForBetterCopy(player: ExoPlayer) {
        // playWhenReady can still mean BUFFERING. Quality discovery starts only
        // after Media3 reports that this track is actually audible.
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId

        // An offline rendition outranks every network-quality upgrade. The loader
        // already verifies the saved URI; never replace its decoded bytes with a
        // freshly matched cover, AAC stream or another release.
        if (mediaId in com.music.orb.download.Downloads.saved.value ||
            item.toSong().localUri != null || item.toSong().localPath != null
        ) {
            hideQualitySearchHint(mediaId)
            return
        }

        // Hard first-note invariant: requested quality is never allowed to compete with startup.
        // Other callbacks (progress/timeline/Automix) may call this method opportunistically, so
        // enforce the grace here as well as in the staged scheduler.
        val firstAudibleAt = currentTrackFirstAudibleAtMs ?: return
        if (SystemClock.elapsedRealtime() - firstAudibleAt < FIRST_AUDIO_QUALITY_GRACE_MS) return

        // Cellular is continuity-first, but it must not permanently trap a
        // track on the emergency first-note carrier. The normal first-note path
        // intentionally starts fast and upgrades afterward; on mobile data we
        // therefore let that upgrade run only after the *current* stream has
        // banked a large cushion. Search + silent audition may then spend radio
        // time without starving the audio already coming out of the speaker.
        // On a genuinely weak link the cushion never appears, so continuity
        // wins and the current rendition is left untouched for this play.
        if (AppSettings.meteredConnection.value == true) {
            val headroomMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            if (player.bufferedPercentage < 100 && headroomMs < METERED_CURRENT_QUALITY_HEADROOM_MS) {
                hideQualitySearchHint(mediaId)
                return
            }
        }

        // A gets the first few audible seconds to improve itself. Once the playhead has
        // reached that initial quality window and A is also inside B's transition-buffer
        // approach, stop spending radio/CPU on a source search that can no longer land
        // safely. From here the immediate successor owns the foreground preparation
        // budget. This also prevents reportProgress() from restarting A's cancelled
        // lookup every five seconds while B is being warmed.
        val duration = player.duration
        if (duration > 0L && duration != C.TIME_UNSET) {
            val remaining = (duration - player.currentPosition).coerceAtLeast(0L)
            val successorPriorityLeadMs = if (AppSettings.meteredConnection.value == true) {
                METERED_NEXT_TRACK_BUFFER_PRIORITY_LEAD_MS
            } else {
                NEXT_TRACK_BUFFER_PRIORITY_LEAD_MS
            }
            if (player.currentPosition >= CURRENT_TRACK_UPGRADE_PRIORITY_MS &&
                remaining <= successorPriorityLeadMs
            ) {
                if (upgradeFor == mediaId && upgradeJob?.isActive == true) {
                    upgradeJob?.cancel()
                    upgradeJob = null
                    hideQualitySearchHint(mediaId)
                    TrackLog.d(
                        "Orb",
                        "current-track upgrade yielded to successor buffer with ${remaining}ms left",
                        about = mediaId,
                    )
                }
                return
            }
        }
        val preparedRoute = item.localConfiguration?.uri?.getQueryParameter("qp")
        val preparedPrime = freshQueuedQualityPrime(mediaId)
        if (preparedPrime != null && preparedRoute != null) {
            val preparedStream = preparedPrime.preferredStream
            val preparedAtCeiling = when (preparedRoute) {
                "lossless", "hiq" -> preparedStream?.let { SourceResolver.isAtRequestedQualityCeiling(it.format) } == true
                else -> false
            }
            if (preparedAtCeiling) {
                // B already entered on the best tier the current network asks for.
                // Freeze this play so no needless mid-song source cut is introduced.
                QualityUpgrade.settlePreparedQueueTrack(mediaId)
                hideQualitySearchHint(mediaId)
                return
            }
            // `qp=opus`, or Hi-Q under Maximum, is only a safe starting point.
            // Keep the normal second-look path alive so B can continue climbing
            // after the handoff without ever delaying its first audible frame.
        }
        if (nextQualityPrimeJob?.isActive == true) {
            // The successor preflight is deliberately allowed to run beside the
            // current-track upgrade. It may use the same radio/provider pool,
            // but it must never block A from improving for up to 30 seconds.
            TrackLog.d(
                "Orb",
                "current-track upgrade for $mediaId continues in parallel with successor quality preflight",
                about = mediaId,
            )
        }
        val uri = item.localConfiguration?.uri
        val alreadyPending = QualityUpgrade.isPending(mediaId)
        val shelved = QualityUpgrade.shelvedFor(mediaId)
        if (shelved == null && !alreadyPending && !QualityUpgrade.couldStillUpgrade(mediaId, uri)) return
        if (upgradeJob?.isActive == true) {
            // Already hunting for this track. One left over from a track the
            // queue has moved past is a different matter: it can only come
            // back with an answer about a song nobody is listening to, and
            // until it does it holds the slot the current track needs.
            if (upgradeFor == mediaId) return
            upgradeJob?.cancel()
        }
        upgradeFor = mediaId
        if (alreadyPending) {
            TrackLog.d("Orb", "looking again for a better copy of $mediaId", about = mediaId)
        }
        upgradeJob = scope.launch(TrackLog.about(mediaId)) {
            // A previous visit to this track already did all the expensive
            // parts and lost the swap to a skip. Nothing about the answer has
            // gone stale — the stream is still parked and its bytes are still
            // on disk — so this goes straight to the swap and skips the ten
            // seconds of catalogue searching it would otherwise repeat.
            if (shelved != null) {
                TrackLog.d("Orb", "re-offering the upgrade already proved for $mediaId")
                NerdStats.onLosslessRaceStart(mediaId)
                try {
                    swapIn(mediaId, shelved)
                } finally {
                    NerdStats.onLosslessRaceEnd(mediaId)
                }
                return@launch
            }
            // The runtime the decoder reports is the only measured evidence
            // about what is playing, and everything downstream weighs
            // candidates against it — so it is worth a short wait rather than
            // a null. It is genuinely not known yet at some of the moments
            // this is called from: a queue advance runs its transition before
            // the item it moved onto has finished preparing.
            val playingSeconds = withTimeoutOrNull(DURATION_SETTLE_MS) {
                while (true) {
                    val ms = withContext(Dispatchers.Main) {
                        this@PlaybackService.player
                            ?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                            ?.duration
                            ?: 0L
                    }
                    if (ms > 0) return@withTimeoutOrNull (ms / 1000).toInt()
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            // Re-asked rather than carried down from above, because the wait
            // is long enough for the answer to have changed: the resolver runs
            // on the loader thread and marks a track pending as it opens the
            // source, which for a track being fetched is precisely what has to
            // happen before the decoder can report the runtime waited for just
            // above. Reading the flag from before the wait meant a freshly
            // resolved track arrived here looking un-resolved, was handed to
            // the cached-track path, was refused by it for being pending, and
            // lost its upgrade until the next progress sample came round.
            if (!QualityUpgrade.isPending(mediaId) &&
                (uri == null || !adoptCachedTrack(mediaId, uri, playingSeconds))
            ) {
                return@launch
            }
            // The sentence is tied to the real second-look operation, not to
            // generic buffering/racing state. By this point B's successor has
            // already won its own Lossless preflight, so A is finally allowed
            // to spend time on itself.
            maybeShowQualitySearchHint(mediaId)
            val better = withContext(Dispatchers.IO) {
                QualityUpgrade.lookAgain(mediaId, playingSeconds)
            } ?: run {
                hideQualitySearchHint(mediaId)
                return@launch
            }
            try {
                swapIn(mediaId, better)
            } finally {
                // The badge comes down when the upgrade is done, not when the
                // search that found it was — including the deliberate wait in
                // [swapIn] before the audio is allowed to be cut. See
                // [QualityUpgrade.lookAgain]. In `finally` because a queue
                // that moves on cancels this job, and a cancelled swap has to
                // put the badge out as surely as a completed one.
                NerdStats.onLosslessRaceEnd(mediaId)
            }
        }
    }

    /**
     * Decides whether a track nothing resolved is worth a second look, now that
     * the decoder has settled enough to say what it is playing.
     *
     * Two questions that need the player rather than the queue entry:
     *
     *  - **What codec is actually coming out.** A cache entry can already hold
     *    the FLAC a previous session upgraded to, and hunting a lossless copy
     *    of a track that is already lossless buys a break in the audio for
     *    nothing. An unknown codec is not read as "lossy": it means the
     *    renderer has not been configured yet, so the track is left un-adopted
     *    and the progress sampler asks again a few seconds later. A codec the
     *    renderer is reporting for *some other track* gets the same treatment,
     *    and has to, because it is indistinguishable from an answer — see
     *    [audioFormatFor] for what it cost to read one on trust.
     *  - **Whether the listener owns the file.** A downloaded track resolves to
     *    its own copy on disk — see the resolving data source above, which
     *    answers it before the module race is ever reached, so a download has
     *    never been a candidate for substitution. Reproduced here because this
     *    path skips that resolver entirely; without it the second look would
     *    spend data replacing a file the user deliberately saved.
     *
     * @param durationSec the runtime the decoder reports, waited for by the
     *   caller — needed here to turn the size of the cache entry into a
     *   bitrate. See [cachedFloor].
     */
    private suspend fun adoptCachedTrack(mediaId: String, uri: Uri, durationSec: Int?): Boolean {
        val format = withContext(Dispatchers.Main) {
            player
                ?.takeIf { it.currentMediaItem?.mediaId == mediaId && audioFormatFor == mediaId }
                ?.audioFormat
        } ?: return false
        val mime = format.sampleMimeType ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val downloaded = com.music.orb.download.Downloads.savedUri(this, videoId) != null
        if (downloaded && shouldUseSavedFileForStreaming(
                AppSettings.internetAvailable.value,
                AppSettings.useDownloadsWhileOnline.value,
            )) return false
        return QualityUpgrade.adoptUnresolved(
            mediaId = mediaId,
            uri = uri,
            target = SourceResolver.targetIn(uri),
            playingMime = mime,
            playing = withContext(Dispatchers.IO) { cachedFloor(uri, format, durationSec) },
        )
    }

    /**
     * How good the bytes already on disk are, in the only terms a track nothing
     * resolved can be measured in.
     *
     * Two measurements, in order of directness:
     *
     *  - **What the decoder says.** `Format.bitrate` is populated for the
     *    containers that carry the field, which for what Orb plays means
     *    MP4/AAC — the 320kbps copy a module served last session reports itself
     *    exactly.
     *  - **What the cache entry weighs.** Opus in WebM, which is what YouTube
     *    serves and so what most base entries hold, states no bitrate at all;
     *    but the rendition's full length is recorded in the cache index, and
     *    bytes over seconds *is* a bitrate. Slightly high, because container
     *    overhead counts toward the byte total and not toward the audio — which
     *    errs toward leaving the track alone, the right direction for a figure
     *    that decides whether to cut into playing audio.
     *
     * Null only when neither is available: an entry whose content length was
     * never recorded, or a runtime the decoder never reported. That is the old
     * behaviour of this path, and it is now the exception rather than the rule.
     *
     * The codec is deliberately not filled in. [StreamFormat.isLossless] reads
     * it, and a name carried over from the decoder's mime type would have to be
     * translated to be recognised — where being wrong means claiming a cached
     * stream is already lossless and abandoning the upgrade. Only the bitrate
     * is wanted here; [QualityUpgrade.adoptUnresolved] settles the lossless
     * question separately, from the mime type itself.
     */
    private fun cachedFloor(uri: Uri, format: Format, durationSec: Int?): StreamFormat? {
        format.bitrate.takeIf { it != Format.NO_VALUE && it > 0 }?.let {
            return StreamFormat(kbps = it / 1000)
        }
        val seconds = durationSec?.takeIf { it > 0 } ?: return null
        val bytes = AudioCache.contentLengthOf(uri).takeIf { it > 0 } ?: return null
        return StreamFormat(kbps = (bytes * 8 / seconds / 1000).toInt())
    }

    /** Where the playing track stands, read off the player in one hop. */
    private class SwapPoint(
        val item: MediaItem,
        val uri: String,
        val position: Long,
        val duration: Long,
    )

    /**
     * Replaces the playing track's audio with [stream], keeping the position.
     *
     * The break this causes is the whole cost of the feature, so the guards
     * are worth more than the swap is:
     *
     *  - The track must still be the one the search was started for. A skip
     *    during the lookup makes the answer worthless, not merely late.
     *  - There has to be enough of it left to be worth interrupting. Cutting
     *    the last few seconds of a song to improve the last few seconds of a
     *    song is a straight loss.
     *  - **The replacement has to be ready before anything is taken away.**
     *    See [auditionUpgrade]; this is what the break costs, so it is what
     *    the cut is bought against.
     *
     * The mechanism is [MediaItem.buildUpon] with a marked URI rather than a
     * new item: Media3 only rebuilds a media source when the replacement's
     * playback URI differs, so an item rebuilt identically would be accepted
     * and quietly keep playing the old stream.
     */
    private suspend fun swapIn(mediaId: String, stream: SourceStream) {
        // Cast owns the audible rendition. A local source swap must not restart its queue.
        if (com.music.orb.cast.OrbCastBridge.active) {
            QualityUpgrade.shelve(mediaId, stream)
            return
        }
        val at = withContext(Dispatchers.Main) { swapPointFor(mediaId) } ?: return
        if (at.duration > 0 && at.duration - at.position < UPGRADE_MIN_REMAINING_MS) {
            TrackLog.d("Orb", "upgrade abandoned: only ${at.duration - at.position}ms of the track left")
            return
        }

        val upgradedUri = QualityUpgrade.upgradedUri(at.uri)
        // Whether the rendition entry already holds *this* stream's bytes,
        // asked before [force] overwrites the record of what filled it. True
        // only for a shelved upgrade being re-offered, where throwing the entry
        // away would mean paying for the same megabytes twice — and where
        // keeping it is safe for the one reason the discard exists: the file
        // under that key came from this very URL.
        val alreadyFilled = QualityUpgrade.forcedStream(Uri.parse(upgradedUri))?.url == stream.url
        // Parked before the audition rather than at the swap: the silent player
        // reaches its bytes through the same resolving data source the real one
        // does, and that is where a marked URI is turned back into a stream.
        QualityUpgrade.force(mediaId, stream)
        val warmedThrough = auditionUpgrade(mediaId, at, upgradedUri, stream, alreadyFilled)
        if (warmedThrough == null) {
            // Nothing was cut, so there is nothing to put back: the listener
            // keeps the stream they already had and never learns this
            // happened. Which is the point — this is the failure that used to
            // arrive as a break in the audio followed by the same lossy stream
            // returning a few seconds later. Dropping the parked stream stops
            // [QualityUpgrade.forcedStream] serving a URL that has just failed
            // to prove itself.
            QualityUpgrade.forget(mediaId)
            withContext(Dispatchers.IO) { AudioCache.discardRendition(Uri.parse(upgradedUri)) }
            return
        }

        // Immediate quality-upgrade policy: the current Opus/AAC stream keeps
        // playing while the better copy is searched and silently auditioned.
        // Once that replacement is proven ready, there is no extra opening
        // delay before switching to it.
        val settled = withContext(Dispatchers.Main) { player?.currentPosition ?: 0L }
        if (settled < UPGRADE_NOT_BEFORE_MS) {
            delay(UPGRADE_NOT_BEFORE_MS - settled)
        }

        // Never cut into a crossfade in flight. `replaceMediaItem` tears the
        // session player's source down and rebuilds it — CrossfadeController
        // is either syncing its tail player's position against that same
        // source (arming), riding a ~90ms handoff between the two (lapping),
        // or ramping volume off the incoming track's own position (fading),
        // and all three read a session-player discontinuity as either an
        // unrecognised seek (bail, with an audible ramp-out) or a progress
        // calculation reset to whatever position the new source opens at.
        // Either way the blend breaks rather than merely waits.
        //
        // Bounded so a stuck flag can never leave the upgrade waiting forever;
        // past the timeout this falls through to the same check made again,
        // authoritatively, below — so an unusually long-running crossfade
        // still gets one more look rather than being forced through.
        //
        // This loop is only the coarse wait. [crossfade] is read again inside
        // the `withContext` below with no suspension between that read and
        // `replaceMediaItem` — both run on the same single-threaded Main
        // dispatcher [scope] does — so that second check is the one this
        // logic actually depends on for correctness, not this one.
        var waitedForCrossfade = 0L
        while (withContext(Dispatchers.Main) { crossfade?.isTransitioning() } == true &&
            waitedForCrossfade < UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS
        ) {
            delay(UPGRADE_CROSSFADE_POLL_MS)
            waitedForCrossfade += UPGRADE_CROSSFADE_POLL_MS
        }

        // Nor the instant one ends. The loop above releases on the tick the
        // blend completes, and a swap made there lands its cut a few hundred
        // milliseconds after the incoming track finally stood alone: the
        // listener hears the mix land and the music stop, in that order, which
        // reads as the transition having broken rather than as a track quietly
        // getting better. [UPGRADE_NOT_BEFORE_MS] does not cover this — that is
        // measured from the track's own start, and an Automix hands over at a
        // cue point that can be well past it.
        //
        // Keyed off when a transition last ended rather than off whether the
        // loop above actually waited, so the same grace covers an upgrade
        // shelved by the check below and re-offered moments later — the same
        // swap, the same few seconds after the same blend, arriving by a
        // different route. And nothing is held back on a track nowhere near a
        // transition: the reading is then already long past the grace.
        withContext(Dispatchers.Main) { crossfade?.msSinceTransition() }?.let { since ->
            if (since < UPGRADE_AFTER_CROSSFADE_MS) {
                val settle = UPGRADE_AFTER_CROSSFADE_MS - since
                TrackLog.d("Orb", "upgrade for $mediaId holding ${settle}ms; a transition just ended")
                delay(settle)
            }
        }

        withContext(Dispatchers.Main) {
            val now = swapPointFor(mediaId)
            if (crossfade?.isTransitioning() == true) {
                // Caught right before the swap that would have broken it —
                // everything spent proving this stream is still worth keeping
                // for next time rather than throwing away, exactly like the
                // "queue moved on" case just below.
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("Orb", "upgrade for $mediaId shelved: a crossfade was still running")
                return@withContext
            }
            if (now == null) {
                // The queue moved on between the upgrade being proved and the
                // swap being made — a skip, or a track that ran out. Everything
                // this cost is still in hand, so it goes on the shelf rather
                // than in the bin; see [QualityUpgrade.shelve]. Logged because
                // this used to be the one exit here that left no trace at all,
                // and from the logs "found a FLAC, cached it, swapped nothing"
                // was indistinguishable from never having looked.
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("Orb", "upgrade for $mediaId proved but the queue moved on; shelved")
                return@withContext
            }
            val player = player ?: return@withContext
            if (now.duration > 0 && now.duration - now.position < UPGRADE_MIN_REMAINING_MS) {
                TrackLog.d("Orb", "upgrade abandoned: only ${now.duration - now.position}ms of the track left")
                QualityUpgrade.forget(mediaId)
                return@withContext
            }
            // The parked stream can be taken away underneath a swap in flight:
            // a playback failure on the *old* stream runs [recoverFrom], which
            // forgets the pending upgrade along with everything else it clears.
            // Swapping onto a marked URI with nothing parked behind it would
            // send the resolver off to find a stream of its own and write it
            // into the rendition entry the audition just filled — two files,
            // one key, which is the corruption the audition exists to avoid.
            if (QualityUpgrade.forcedStream(Uri.parse(upgradedUri)) == null) {
                TrackLog.d("Orb", "upgrade abandoned: its stream was dropped while it was being proved")
                return@withContext
            }
            // Not fatal, just slower than intended, and worth being able to see
            // in a log: the audition buffers ahead of a moving target and can
            // only lose that race on a connection that is barely keeping up.
            if (now.position > warmedThrough) {
                TrackLog.d(
                    "Orb",
                    "upgrade landing at ${now.position}ms, past the ${warmedThrough}ms warmed for it",
                )
            }

            // Read before the swap overwrites it — see [watchUpgrade]'s
            // NerdStats cleanup for why the pre-upgrade claim has to be
            // captured here rather than looked up again on revert.
            if (com.music.orb.cast.OrbCastBridge.active) {
                QualityUpgrade.shelve(mediaId, stream)
                return@withContext
            }
            val previousFormat = NerdStats.declaredFormat(mediaId)
            val previousLosslessVerified = NerdStats.isNativeLosslessVerified(mediaId)
            swappingMediaId = mediaId
            swapCutAt = SystemClock.elapsedRealtime()
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                mediaItemForStream(now.item, upgradedUri, stream),
            )
            player.seekTo(player.currentMediaItemIndex, now.position)
            player.prepare()
            QualityUpgrade.unshelve(mediaId)
            TrackLog.d("Orb", "upgraded to ${stream.format.summary} at ${now.position}ms")
            watchUpgrade(
                mediaId,
                now.uri,
                now.position,
                now.duration,
                previousFormat,
                previousLosslessVerified,
                stream,
            )
            // The opening again, this time sized for Automix rather than for
            // a container header.
            //
            // An upgraded rendition is only ever fetched from the swap point
            // onward, so its first seconds are the one region nothing downloads
            // on its own — [UPGRADE_HEADER_BYTES] covers the header and stops
            // well short of enough *audio* to measure. A megabyte of lossless is
            // four seconds, against the twelve the analyzer needs, so a track
            // that upgrades early could never be analysed from any rendition:
            // the lossless copy had no audio at its head and the copy it
            // replaced was discarded.
            //
            // After the swap and off the main thread, because nothing waits on
            // it — the upgrade is already audible and this only decides whether
            // the *next* transition can be a real mix.
            if (!stream.isDashManifest()) {
                launch(Dispatchers.IO) {
                    AudioCache.warmRange(Uri.parse(upgradedUri), 0, ANALYSIS_HEAD_BYTES)
                }
            }
        }
    }

    /** Main thread. Null unless [mediaId] is still current and still un-upgraded. */
    private fun swapPointFor(mediaId: String): SwapPoint? {
        val player = player ?: return null
        val item = player.currentMediaItem ?: return null
        if (item.mediaId != mediaId) return null
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        if (uri.contains("${QualityUpgrade.MARKER}=")) return null
        return SwapPoint(item, uri, player.currentPosition, player.duration)
    }

    /**
     * Proves the upgraded stream on a second, silent player before a note of
     * the one playing is touched.
     *
     * This is the difference between a swap that is heard and one that is not.
     * `replaceMediaItem` + `prepare` tears the old source down first and builds
     * the new one from nothing: a connection to the CDN, a container header, a
     * range request for wherever the seek lands, a decoder configured, and only
     * then audio. Measured on this device that ran to about a second of silence
     * every time, and the whole of it was spent doing work that had no reason to
     * wait for the music to stop.
     *
     * So it doesn't. A throwaway player opens the same upgraded URI, seeked to
     * where the listener is, and fills the *same on-disk cache entry* the real
     * player will read from — [QualityUpgrade.MARKER] keys that entry apart from
     * the rendition being replaced, which is what makes this safe. When the swap
     * finally happens the bytes are already local, the container is already
     * known to parse, and what is left is a decoder init. The old stream plays
     * through all of it.
     *
     * The second thing it buys is that a failed upgrade stops costing anything.
     * Every way this can go wrong — a dead URL, a 403, a truncated body, a
     * catalogue that matched the wrong cut of the song, a source that promised
     * FLAC and serves Opus — now happens to a player nobody is listening to, and
     * the answer is simply that no swap occurs. Before, all of them were
     * discovered *after* the audio had been cut, and cost a break, several
     * seconds of silence in `STATE_BUFFERING`, and a second break putting the
     * old stream back. See [watchUpgrade], which is now the backstop for this
     * rather than the first line of defence.
     *
     * Silent by construction rather than by volume: with `playWhenReady` false
     * the renderers are enabled and decode — which is all the proof needed —
     * but nothing is started and no second `AudioTrack` is ever opened. It takes
     * no audio focus and backs no session, so nothing else in the app can see it.
     *
     * @return how far into the track the upgrade is buffered and ready, or null
     *   if it never got there.
     */
    private suspend fun auditionUpgrade(
        mediaId: String,
        at: SwapPoint,
        upgradedUri: String,
        stream: SourceStream,
        renditionAlreadyFilled: Boolean,
    ): Long? {
        QualityUpgrade.beginAudition(mediaId)
        val startedAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            // A clean entry first, because `#hifi` names a *slot* and not a
            // file. Every audition is a fresh candidate — a different catalogue,
            // a different master, a different length — and anything left under
            // that key by an earlier attempt at the same track belongs to a
            // different one of those. Media3 will happily read the two as one
            // stream, which is how a whole contiguous 32MB entry ended up
            // decoding to this:
            //
            // ```
            //   Target buffer size reached with less than 500ms of buffered media
            //   IllegalStateException: Playback stuck buffering and not loading
            // ```
            //
            // — a spliced file that cost the swap, the recovery, and seven
            // seconds of silence. The cost of being wrong the other way is one
            // re-download of a track being upgraded twice in a session, which
            // is why a re-offered upgrade is exempt: there the bytes under the
            // key are known to have come from the URL about to be used again.
            if (!renditionAlreadyFilled) AudioCache.discardRendition(Uri.parse(upgradedUri))
            // Then the opening, on its own, because the audition will not cache
            // it: a progressive source parses the container from byte zero and
            // then *seeks away*, leaving behind only the handful of bytes it
            // read before jumping. The real player has to parse the same header
            // from scratch after the swap, and it was reaching the network to do
            // it — the one read nothing can start without. Ahead of the audition
            // rather than beside it, since Media3 locks a cache entry to a
            // single writer.
            if (!stream.isDashManifest()) {
                AudioCache.warmRange(Uri.parse(upgradedUri), 0, UPGRADE_HEADER_BYTES)
            }
        }
        val audition = withContext(Dispatchers.Main) {
            buildAuditionPlayer().apply {
                setMediaItem(mediaItemForStream(at.item, upgradedUri, stream))
                seekTo(at.position)
                prepare()
            }
        }
        val warmedThrough: Long?
        try {
            warmedThrough = withTimeoutOrNull(UPGRADE_AUDITION_MS) {
                while (true) {
                    val verdict = withContext(Dispatchers.Main) {
                        auditionVerdict(audition, at.duration, stream)
                    }
                    when (verdict) {
                        is Audition.Ready -> return@withTimeoutOrNull verdict.bufferedTo
                        is Audition.Rejected -> {
                            TrackLog.w("Orb", "upgrade dropped before it was heard: ${verdict.why}")
                            return@withTimeoutOrNull null
                        }
                        Audition.Waiting -> delay(UPGRADE_PROVE_STEP_MS)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally {
            // Not optional and not cancellable: a queue that moves on cancels
            // this job, and a player left behind holds an audio decoder and a
            // write lock on a cache entry for the rest of the session.
            withContext(NonCancellable + Dispatchers.Main) { audition.release() }
            QualityUpgrade.endAudition(mediaId)
        }
        val took = SystemClock.elapsedRealtime() - startedAt
        if (warmedThrough == null) {
            TrackLog.d("Orb", "upgrade for $mediaId never proved itself in ${took}ms")
            return null
        }
        TrackLog.d(
            "Orb",
            "upgrade to ${stream.format.summary} proved in ${took}ms, buffered through ${warmedThrough}ms",
        )
        // Media3 locks a cache entry to one writer, and the audition lets go of
        // its hold as the sources are released rather than as `release()`
        // returns. Swapping onto a key still held would have the real player
        // stream bytes it has already paid to cache, or block behind the lock —
        // the stall [AudioCache]'s key factory documents. Free to wait for: the
        // old stream is still playing.
        delay(AUDITION_RELEASE_MS)
        TrackLog.d("Orb", AudioCache.cachedSummary(Uri.parse(upgradedUri)))
        return warmedThrough
    }

    /** How an audition in progress is coming along — see [auditionUpgrade]. */
    private sealed interface Audition {
        data object Waiting : Audition

        /** Good, and buffered through this position in the track. */
        class Ready(val bufferedTo: Long) : Audition

        class Rejected(val why: String) : Audition
    }

    /**
     * Main thread. Everything that has to be true before the audio is cut,
     * asked of the audition player rather than of the catalogue that made the
     * claims.
     */
    private fun auditionVerdict(
        audition: ExoPlayer,
        previousDuration: Long,
        stream: SourceStream,
    ): Audition {
        audition.playerError?.let {
            return Audition.Rejected("${it.errorCodeName} opening ${stream.format.summary}")
        }
        // The failure a mid-track swap cannot survive, and the one that never
        // raises an error: a replacement that came up short does not fail, it
        // reaches the end of what it has and reports the track as over. Caught
        // here it costs nothing at all; caught after the swap it costs the
        // listener their song. See [watchUpgrade].
        if (audition.playbackState == Player.STATE_ENDED) {
            return Audition.Rejected("replacement ended immediately")
        }
        if (audition.playbackState != Player.STATE_READY) return Audition.Waiting
        val length = audition.duration
        if (length <= 0) return Audition.Waiting
        if (previousDuration > 0 && abs(length - previousDuration) > UPGRADE_LENGTH_SLACK_MS) {
            return Audition.Rejected("replacement is ${length}ms against ${previousDuration}ms")
        }
        // What the decoder was actually configured with, against what the
        // source said it was sending. The one failure mode a claim cannot
        // catch, because the claim is the thing that is wrong: a catalogue
        // advertising FLAC and serving a transcode buys a break in the audio
        // for no gain whatsoever.
        val mime = audition.audioFormat?.sampleMimeType
        if (mime != null && stream.format.isLossless == true && !NerdStats.isLosslessMime(mime)) {
            return Audition.Rejected("promised ${stream.format.summary}, decoder was handed $mime")
        }
        val buffered = audition.bufferedPosition
        // Aimed at where the listener will be, not where they were when this
        // started: the audition buffers ahead of a track that is still playing,
        // so the window it has to cover keeps moving. On any connection worth
        // upgrading over, buffering outruns playback and this converges in a
        // couple of seconds; on one where it doesn't, the swap would have
        // stalled anyway and the timeout is the right answer.
        val wantedThrough = (player?.currentPosition ?: 0L) + UPGRADE_PREBUFFER_MS
        // The only reason to settle for less: there is no more track to buffer.
        //
        // `isLoading` was tried here as a second escape — "the loader has
        // stopped of its own accord, so this is as good as it gets" — and it
        // was wrong every single time. [ChunkedDataSource] closes and reopens
        // the upstream every two megabytes, and `isLoading` goes false in the
        // gap between one range finishing and the next being asked for. A poll
        // landing in that gap read it as a full buffer, so every upgrade was
        // declared ready with roughly one chunk in hand and the swap then
        // landed seconds past the end of it, back on the network:
        //
        // ```
        //   upgrade to FLAC proved in 8701ms, buffered through 32496ms
        //   upgrade landing at 39889ms, past the 32496ms warmed for it
        // ```
        if (buffered >= wantedThrough || audition.bufferedPercentage >= 100) {
            return Audition.Ready(buffered)
        }
        return Audition.Waiting
    }

    /**
     * The throwaway player an upgrade is proved on.
     *
     * Shares the media source factory, and therefore the disk cache, with the
     * real one — which is the entire point: what this fetches is what the real
     * player reads a moment later. Deliberately plainer than the two players
     * [buildPlayer] builds, because nothing here is ever heard: stock
     * renderers, no spatial processor, no audio session, no focus, no session.
     *
     * The one thing it does not share is the load control. [farBufferingLoadControl]
     * stops at [FAR_BUFFER_BYTES], which is sized for a player that only has to
     * stay ahead of itself; this one has to buffer past a *moving* target —
     * [UPGRADE_PREBUFFER_MS] beyond wherever the listener has got to by the time
     * it finishes — and eight megabytes is under fifteen seconds of hi-res FLAC,
     * which the drift alone can eat. Held for seconds and then released with the
     * player.
     */
    private fun buildAuditionPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ AUDITION_BUFFER_MS,
                    /* maxBufferMs = */ AUDITION_BUFFER_MS,
                    /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ START_PLAYBACK_MS,
                )
                .setTargetBufferBytes(AUDITION_BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = false
            volume = 0f
        }

    /**
     * Puts the old stream back if the upgraded one turns out to be broken.
     *
     * Learned the hard way: a swapped-in source that comes up short — a
     * truncated body, a CDN that answers a range request with something other
     * than the file — does not raise an error. It reports no duration, plays
     * for a few seconds and hits end-of-stream, and ExoPlayer does the correct
     * thing with a track that has ended, which is to advance to the next one.
     * The listener's song simply vanishes eight seconds in. That is a far worse
     * outcome than the lossy stream this was trying to improve on, so the new
     * source has to prove itself against the length the old one already knew
     * before it is allowed to keep the track.
     */
    private fun watchUpgrade(
        mediaId: String,
        previousUri: String,
        position: Long,
        previousDuration: Long,
        previousFormat: StreamFormat?,
        previousLosslessVerified: Boolean,
        upgradedStream: SourceStream,
    ) {
        if (previousDuration <= 0) return
        scope.launch(TrackLog.about(mediaId)) {
            val agreed = withTimeoutOrNull(UPGRADE_PROVE_MS) {
                while (true) {
                    val current = player?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                        ?: return@withTimeoutOrNull false
                    val now = current.duration
                    if (now > 0) return@withTimeoutOrNull abs(now - previousDuration) <= UPGRADE_LENGTH_SLACK_MS
                    // The failure this whole check exists for, caught when it
                    // happens rather than at the ceiling: a replacement that
                    // came up short does not raise an error, it reaches the
                    // end of what it has and reports the track as over. That
                    // is a decisive no, and waiting out the rest of the window
                    // for it only delays the old stream coming back.
                    if (current.playbackState == Player.STATE_ENDED) return@withTimeoutOrNull false
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (agreed == true) {
                PlaybackStreamStore.remember(mediaId, upgradedStream)
                return@launch
            }
            val player = player ?: return@launch
            val item = player.currentMediaItem ?: return@launch
            if (item.mediaId != mediaId) return@launch
            // State and buffered position alongside the length: a replacement
            // that loaded and disagreed about the track looks identical here
            // to one that never loaded at all, and only the second is a fault
            // in the stream rather than a wrong match.
            TrackLog.w(
                "Orb",
                "upgrade reverted: replacement reports ${player.duration}ms against " +
                        "${previousDuration}ms (state=${player.playbackState}, " +
                        "buffered=${player.bufferedPosition}ms)",
            )
            QualityUpgrade.forget(mediaId)
            // The FLAC/whatever claim recorded when the swap went out is no
            // longer what's playing — restore what was declared before it
            // (or clear it, if nothing was), so "stats for nerds" doesn't
            // keep calling the fallback lossless after the upgrade it
            // borrowed that claim from got reverted.
            if (previousFormat != null) {
                NerdStats.onSourceStream(mediaId, previousFormat, previousLosslessVerified)
            } else {
                NerdStats.clearDeclared(mediaId)
            }
            swappingMediaId = mediaId
            val abandoned = item.localConfiguration?.uri
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon()
                    .setUri(previousUri)
                    .setMimeType(null)
                    .build(),
            )
            player.seekTo(player.currentMediaItemIndex, position)
            player.prepare()
            // Whatever the replacement wrote is a prefix of a file nothing will
            // ever finish, under a key the *next* upgrade of this track would
            // key to as well — see [AudioCache.discardRendition]. Off the main
            // thread and behind the same pause a recovery takes, because the
            // source just released still holds the entry for a moment.
            abandoned?.let {
                launch(Dispatchers.IO) {
                    delay(RECOVERY_DELAY_MS)
                    AudioCache.discardRendition(it)
                }
            }
        }
    }

    /**
     * True when [SourceStream] points at a DASH manifest.
     *
     * The stream URL is resolved behind an orb:// URI, so the final .mpd suffix
     * is invisible to DefaultMediaSourceFactory unless we explicitly attach the
     * DASH MIME type to the replacement MediaItem before prepare().
     */
    private fun SourceStream.isDashManifest(): Boolean {
        if (isDash) return true
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        return path.endsWith(".mpd", ignoreCase = true) ||
                path.contains("/dash/", ignoreCase = true)
    }

    /**
     * Rebuilds [item] for a resolved stream while preserving all of its metadata.
     *
     * Progressive streams keep the old behaviour. DASH streams explicitly carry
     * application/dash+xml so Media3 chooses DashMediaSource before the
     * ResolvingDataSource swaps the orb:// URI for the real hifi-api URL.
     */
    private fun mediaItemForStream(
        item: MediaItem,
        uri: String,
        stream: SourceStream,
    ): MediaItem = item.buildUpon()
        .setUri(uri)
        .apply {
            if (stream.isDashManifest()) {
                setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }
        .build()

    /** What [resolveWithModulePriority] settled on. */
    private sealed interface Resolved {
        data class Module(val stream: SourceStream) : Resolved
        data class YouTube(val stream: SourceStream) : Resolved
    }

    /**
     * Resolves a YouTube-queued track by racing the higher-ranked modules
     * against YouTube itself, and handing whatever the modules are still doing
     * to [QualityUpgrade] if YouTube gets there first.
     *
     * Nobody gets a head start. An earlier version gave the modules six
     * seconds of silence to answer in before the fallback was even *asked*
     * for, on the reasoning that a module answering inside that window plays
     * with no seam in it. What that actually bought, on every track the
     * modules were slow on, was six seconds of nothing followed by a YouTube
     * client walk starting from cold — the wait and the seam, rather than one
     * or the other. Starting both at once removes the first of those: the
     * track begins as soon as *anything* can serve it.
     *
     * The speculative resolve this reinstates was dropped once before, for a
     * real reason — it is several round trips to `youtubei.googleapis.com`
     * competing for the same radio and connection pool as the lookup beside
     * it, and on a track the modules do have, that work is thrown away. What
     * changed is that it is no longer speculative: YouTube is now the expected
     * outcome for anything the modules don't answer quickly, so its walk is on
     * the critical path rather than hedging one. It is also coalesced and
     * cached — see [StreamResolver.resolve] — so even a discarded walk warms
     * the URL this track will want if the upgrade later falls through.
     *
     * A module that wins the race outright still wins the track, which is the
     * one thing worth keeping from the old head start: the lossless copy plays
     * from the first note and there is no swap at all. That is a narrower
     * window than it sounds, and deliberately so — read-ahead warms the
     * YouTube URL for the queue (see [AudioCache.prefetchQueue]), so on a
     * track that was read ahead the fallback answers in milliseconds and
     * almost always wins. The swap is the ordinary path now; playing from the
     * first note is the prize for a module quick enough to beat a cached URL.
     *
     * A lookup that loses is not cancelled. It is handed over still running,
     * because it is not wrong, only late, and the thing it is about to return
     * is exactly the stream that would have played seamlessly had it been
     * quicker. It finishes on its own time and the track swaps up to it
     * mid-song, which is the trade this whole path exists to make: a short
     * break in the audio, in exchange for the listener hearing something now
     * rather than waiting in silence for the good copy.
     */
    /**
     * Resolves the selected YouTube id, but treats a YouTube Music Premium-only
     * carrier as an identity problem rather than a transport retry.
     *
     * The visible queue item keeps its original media id/metadata. Only the
     * underlying audio carrier may be rematched to another YouTube Music song
     * row that [TrackMatcher] proves is the same recording.
     */
    private suspend fun resolveYouTubeCarrierWithRematch(
        videoId: String,
        target: TrackMatcher.Target,
    ): SourceStream {
        var accessRefusalCause: Exception? = null
        try {
            return StreamResolver.resolveImmediatePlayback(videoId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!StreamResolver.isPremiumOnlyFailure(error) &&
                !StreamResolver.isLoginRequiredFailure(error)
            ) throw error
            accessRefusalCause = error
            TrackLog.w(
                "Orb",
                "$videoId refused YouTube playback; searching for an equivalent audio id",
                error,
                about = videoId,
            )
        }

        StreamResolver.invalidatePlaybackUrl(videoId)

        val alternatives = withTimeoutOrNull(PREMIUM_CARRIER_REMATCH_SEARCH_MS) {
            YtMusicRepository.playbackAlternatives(
                target = target,
                excludedVideoIds = setOf(videoId),
                limit = PREMIUM_CARRIER_REMATCH_MAX_CANDIDATES,
            )
        }.orEmpty()

        for (candidate in alternatives) {
            val carrier = try {
                withTimeoutOrNull(PREMIUM_CARRIER_REMATCH_RESOLVE_MS) {
                    StreamResolver.resolveImmediatePlayback(candidate.videoId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (StreamResolver.isPremiumOnlyFailure(error)) {
                    TrackLog.d(
                        "Orb",
                        "alternate ${candidate.videoId} is also Premium-only",
                        about = videoId,
                    )
                } else {
                    TrackLog.d(
                        "Orb",
                        "alternate ${candidate.videoId} could not resolve: ${error.message}",
                        about = videoId,
                    )
                }
                null
            }

            if (carrier != null && carrier.hasPlayableHttpUrl()) {
                TrackLog.w(
                    "Orb",
                    "Premium-only $videoId rematched to playable carrier ${candidate.videoId}",
                    about = videoId,
                )
                return carrier
            }
        }

        // A genuinely denied YouTube ID may still have a valid Lossless copy.
        // Do not retry it if this track's Lossless substitute has already failed.
        if (!StreamChoice.substitutesRefused(videoId)) {
            val alternateSource = withTimeoutOrNull(PREMIUM_CARRIER_SOURCE_FALLBACK_MS) {
                SourceResolver.emergencyForUnavailableYouTube(target)
            }
            if (alternateSource != null && alternateSource.hasPlayableHttpUrl()) {
                TrackLog.w("Orb", "$videoId recovered from alternate catalogue", about = videoId)
                return alternateSource
            }
        }

        // When YouTube denies a carrier, do not immediately re-enter a Lossless
        // source that may have failed for the same track. Prefer usable Hi-Q
        // before declaring the lossy fallback exhausted.
        val hiQuality = withTimeoutOrNull(PREMIUM_CARRIER_SOURCE_FALLBACK_MS) {
            SourceResolver.fallbackHiQuality(
                target, videoId, PREMIUM_CARRIER_SOURCE_FALLBACK_MS,
            )
        }
        if (hiQuality != null && hiQuality.hasPlayableHttpUrl()) {
            TrackLog.w("Orb", "$videoId recovered via Hi-Q AAC after YouTube refusal", about = videoId)
            return hiQuality
        }

        throw java.io.IOException(
            "YouTube refused $videoId; no matching Hi-Q or Opus source is currently available",
            accessRefusalCause,
        )
    }

    private suspend fun resolveWithModulePriority(
        videoId: String,
        target: TrackMatcher.Target,
        preparedDash: Boolean = false,
    ): Resolved {
        // A substitute already broke this track once: normal YouTube playback
        // remains the safe carrier.
        if (StreamChoice.substitutesRefused(videoId)) {
            return Resolved.YouTube(resolveYouTubeCarrierWithRematch(videoId, target))
        }

        NerdStats.onLosslessRaceStart(videoId)

        // A queue item explicitly marked as prepared has already found and
        // validated the higher-quality route. Only this proven case may take
        // the first-note path away from ordinary playback.
        if (preparedDash) {
            val prepared = withContext(Dispatchers.IO + TrackLog.about(videoId)) {
                withTimeoutOrNull(PRIMED_LOSSLESS_REOPEN_MS) {
                    SourceResolver.substituteForYouTube(target)
                }
            }
            if (prepared != null && !prepared.belowRequest && prepared.isDashManifest()) {
                NerdStats.onLosslessRaceEnd(videoId)
                return Resolved.Module(prepared)
            }
            throw java.io.IOException("Prepared lossless DASH stream is no longer available")
        }

        // First play / manual next / manual previous: resolve only the normal
        // carrier. The expensive TIDAL/module/Hi-Q lookup is not even started
        // while the first note is waiting.
        val carrier = resolveYouTubeCarrierWithRematch(videoId, target)
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            playing = carrier.format,
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
        return Resolved.YouTube(carrier)
    }

    /**
     * Publishes what the decoder is really being fed, for "stats for nerds".
     *
     * Bitrate is the awkward one: YouTube's WebM and MP4 containers carry no
     * bitrate field, so [Format.bitrate] arrives as `NO_VALUE` and the honest
     * figure is whatever named this stream instead. The source's own figure
     * comes ahead of YouTube's because a track can have both: one resolved
     * through YouTube and then upgraded to a module stream mid-song has a
     * stale 160 sitting in [NerdStats.pickedBitrateKbps] describing audio that
     * stopped playing several seconds ago. Anything still unknown is left null
     * for the UI to omit — better a shorter line than a made-up number.
     */
    private fun publishNerdStats() {
        val player = player ?: return
        val mediaItem = player.currentMediaItem
        val mediaId = mediaItem?.mediaId
        val song = mediaItem?.toSong()
        ensureLocalNativeFormat(song)

        // A renderer format can briefly outlive the queue item it belonged to.
        // Wrong quality is worse than unknown quality, so only publish measured
        // decoder fields when AnalyticsListener has tied them to this media id.
        val format = player.audioFormat.takeIf { mediaId != null && audioFormatFor == mediaId }
        val claimed = NerdStats.declaredFormat(mediaId)
        val localNative = localNativeFormat.takeIf { localQualityFor == mediaId }
        val measuredKbps = format?.bitrate?.takeIf { it != Format.NO_VALUE }?.div(1000)
        val resolvedKbps = NerdStats.pickedBitrateKbps(mediaId)
        val sourceName = when {
            !song?.localUri.isNullOrBlank() || !song?.localPath.isNullOrBlank() -> "Local"
            mediaId == null -> null
            else -> SourceRegistry.parseTrackKey(mediaId)
                ?.first
                ?.let { SourceRegistry.config(it) }
                ?.displayName
                ?: "YouTube Music"
        }
        NerdStats.current.value = NerdStats.Snapshot(
            mediaId = mediaId,
            mimeType = format?.sampleMimeType,
            bitrateKbps = measuredKbps ?: claimed?.kbps ?: resolvedKbps,
            // The exact chosen rendition is useful even when the container does
            // not expose a bitrate field to Media3 (common for AAC/MP4/WebM).
            verifiedBitrateKbps = measuredKbps ?: resolvedKbps ?: claimed?.kbps,
            sampleRateHz = format?.sampleRate?.takeIf { it != Format.NO_VALUE },
            channels = format?.channelCount?.takeIf { it != Format.NO_VALUE },
            bitDepth = format?.pcmEncoding?.let(::bitDepthOf),
            claimed = claimed,
            nativeFormat = localNative ?: claimed,
            nativeLosslessVerified = localNative?.isLossless == true ||
                NerdStats.isNativeLosslessVerified(mediaId),
            sourceName = sourceName,
        )
    }

    /**
     * PCM sample depth the renderer settled on, in bits.
     *
     * This is renderer output precision, used only for downgrade diagnostics.
     * It is not the source file's native bit depth; that comes from
     * [LocalAudioQuality] or the resolved source's [StreamFormat].
     *
     * Float32 is intentionally reported as unknown here. Android may decode an
     * ordinary 16-bit FLAC into 32-bit float for processing, and treating that
     * working precision as source depth falsely promotes CD-quality files to
     * Hi-Res Lossless.
     */
    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        C.ENCODING_PCM_FLOAT -> null
        else -> null
    }

    /**
     * Captures the one mandatory quality decision made before [mediaId] was
     * allowed to start. Direct taps publish through NerdStats; queued tracks
     * inherit the decision their predecessor made in [prepareQueuedQuality].
     */
    private fun rememberOpeningQualityDecision(mediaId: String) {
        val direct = NerdStats.consumeOpeningQualityPreflight(mediaId)
        val routeHint = player?.currentMediaItem
            ?.takeIf { it.mediaId == mediaId }
            ?.localConfiguration
            ?.uri
            ?.getQueryParameter("qp")
            ?.let { value ->
                when (value) {
                    "lossless", "hiq" -> true
                    "opus" -> false
                    else -> null
                }
            }
        val queued = freshQueuedQualityPrime(mediaId)?.betterPrepared
        val betterFound = direct ?: routeHint ?: queued
        openingQualityMissFor = mediaId
        openingQualityMissed = betterFound == false
        hideQualitySearchHint()
    }

    /** Refreshes the opener decision when shuffled Automix finishes its own gate. */
    private fun rememberOpeningQualityDecision(mediaId: String, betterFound: Boolean) {
        if (player?.currentMediaItem?.mediaId != mediaId) return
        openingQualityMissFor = mediaId
        openingQualityMissed = !betterFound
    }

    private fun currentDecoderIsOpus(mediaId: String): Boolean {
        val live = player ?: return false
        if (live.currentMediaItem?.mediaId != mediaId || audioFormatFor != mediaId) return false
        return live.audioFormat?.sampleMimeType?.endsWith("opus", ignoreCase = true) == true
    }

    private fun immediateSuccessorHasBetterQuality(mediaId: String): Boolean {
        val live = player ?: return false
        if (live.currentMediaItem?.mediaId != mediaId) return false
        val nextIndex = live.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until live.mediaItemCount) return false
        val nextId = live.getMediaItemAt(nextIndex).mediaId
        return nextQualityPrimeKey == "$mediaId->$nextId" && nextQualityPrimeSucceeded == true
    }

    /**
     * The quality-search sentence is intentionally stricter than the underlying
     * race. It appears only after all three user-facing facts are true: the
     * current track missed Lossless before start, the decoder is actually on
     * Opus, and the immediate successor's better-quality route is already safe.
     */
    private fun maybeShowQualitySearchHint(mediaId: String) {
        if (upgradeFor != mediaId || upgradeJob?.isActive != true) return
        if (openingQualityMissFor != mediaId || !openingQualityMissed) return
        if (!currentDecoderIsOpus(mediaId)) return
        if (!immediateSuccessorHasBetterQuality(mediaId)) return
        if (NerdStats.qualitySearchHintFor.value == mediaId) return

        qualitySearchHintJob?.cancel()
        NerdStats.showQualitySearchHint(mediaId)
        qualitySearchHintJob = scope.launch {
            delay(QUALITY_SEARCH_HINT_MAX_MS)
            NerdStats.hideQualitySearchHint(mediaId)
            if (NerdStats.qualitySearchHintFor.value != mediaId) {
                qualitySearchHintJob = null
            }
        }
    }

    private fun hideQualitySearchHint(mediaId: String? = null) {
        if (mediaId == null || NerdStats.qualitySearchHintFor.value == mediaId) {
            qualitySearchHintJob?.cancel()
            qualitySearchHintJob = null
        }
        NerdStats.hideQualitySearchHint(mediaId)
    }

    /** Snapshot the queue so the next launch can open where this one stopped. */
    private fun saveQueue() {
        val player = player ?: return
        if (player.mediaItemCount == 0) {
            LastPlayed.clear()
            return
        }
        LastPlayed.save(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            albumSequentialFlags = (0 until player.mediaItemCount).map { index ->
                player.getMediaItemAt(index).albumSequential
            },
        )
    }

    /** Returns a still-relevant quality decision for a queued track. */
    private fun freshQueuedQualityPrime(mediaId: String): QueuedQualityPrime? {
        val prime = queuedQualityPrimes[mediaId] ?: return null
        if (SystemClock.elapsedRealtime() - prime.decidedAtMs > QUEUED_QUALITY_PRIME_TTL_MS) {
            queuedQualityPrimes.remove(mediaId, prime)
            return null
        }
        return prime
    }

    /**
     * Resolves the official Opus path in parallel with the optional Lossless
     * search. This is intentionally not stored in StreamChoice yet: it is a
     * safety net, not the winner. Only the player that actually needs the
     * fallback publishes it as the serving choice.
     */
    private suspend fun prepareQueuedQuality(song: Song, budgetMs: Long): QueuedQualityPrime = coroutineScope {
        if (shouldUseSavedFileForStreaming(
                AppSettings.internetAvailable.value,
                AppSettings.useDownloadsWhileOnline.value,
            ) && com.music.orb.download.Downloads.savedUri(this@PlaybackService, song.videoId) != null) {
            // Offline file is the actual Automix B source. Do not run parallel
            // YouTube/HiFi prime requests for something already on the device.
            return@coroutineScope QueuedQualityPrime(preferredStream = null, opusFallback = null)
        }
        // Resolve the emergency first-note carrier and the quality candidate at the same time.
        // The fallback must never wait behind source matching; the preferred candidate is free
        // to spend the whole background budget because A is already audible.
        val fallbackDeferred = async(Dispatchers.IO + TrackLog.about(song.videoId)) {
            withTimeoutOrNull(OPUS_FALLBACK_PRIME_MS.coerceAtMost(budgetMs.coerceAtLeast(1L))) {
                runCatching { resolveYouTubeCarrierWithRematch(song.videoId, TrackMatcher.targetOf(song)) }.getOrNull()
            }
        }
        val preferredDeferred = async(Dispatchers.IO + TrackLog.about(song.videoId)) {
            withTimeoutOrNull(budgetMs.coerceAtLeast(1L)) {
                runCatching { SourceResolver.queuedBestCandidate(song, budgetMs) }.getOrNull()
            }
        }

        // Publish the fallback as soon as it is ready. On a very short A this is enough to
        // guarantee B can enter immediately even while the better-source search is still running.
        val fallback = fallbackDeferred.await()
        queuedQualityPrimes[song.videoId] = QueuedQualityPrime(
            preferredStream = null,
            opusFallback = fallback,
        )

        val preferred = preferredDeferred.await()
        QueuedQualityPrime(preferredStream = preferred, opusFallback = fallback)
    }

    /** Route token attached to a prepared queue item. */
    private fun queuedRouteFor(prime: QueuedQualityPrime, forceFallback: Boolean = false): String {
        val preferred = prime.preferredStream
            ?.takeIf { !forceFallback && SourceResolver.preparedQueueCandidateAllowed(it.format) }
        return when {
            preferred == null -> "opus"
            preferred.format.isLossless == true -> "lossless"
            else -> "hiq"
        }
    }

    /** Builds the same queue route used by playback without mutating the live timeline. */
    private fun queuedRouteUri(
        item: MediaItem,
        prime: QueuedQualityPrime,
        forceFallback: Boolean = false,
    ): Uri? {
        val uri = item.localConfiguration?.uri ?: return null
        if (uri.getQueryParameter("v") == null) return null
        val route = queuedRouteFor(prime, forceFallback)
        return uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.forEach { name ->
                // q=upgraded pins QualityUpgrade.forcedStream(). A recovery that is explicitly
                // rebuilding onto qp=opus must not carry that marker forward or the dead upgraded
                // source wins again before the fallback route is even consulted.
                if (name == "pl" || name == "qp" || name == QualityUpgrade.MARKER) return@forEach
                uri.getQueryParameters(name).forEach { value -> appendQueryParameter(name, value) }
            }
            appendQueryParameter("qp", route)
            val preferred = prime.preferredStream
                ?.takeIf { route != "opus" && SourceResolver.preparedQueueCandidateAllowed(it.format) }
            if (preferred?.isDash == true) appendQueryParameter("pl", "1")
        }.build()
    }

    /**
     * Pins a future queue item to the route that has already been validated for it. The route
     * itself is the cache key boundary: Hi-Q, Lossless and fallback bytes can never be spliced.
     */
    private fun applyPreparedQualityHint(
        player: ExoPlayer,
        index: Int,
        prime: QueuedQualityPrime,
        forceFallback: Boolean = false,
        markCurrentSwap: Boolean = false,
    ): Boolean {
        if (index !in 0 until player.mediaItemCount) return false
        val item = player.getMediaItemAt(index)
        val rebuilt = queuedRouteUri(item, prime, forceFallback) ?: return false
        val route = rebuilt.getQueryParameter("qp")
        val preferred = prime.preferredStream
            ?.takeIf { route != "opus" && SourceResolver.preparedQueueCandidateAllowed(it.format) }
        val wantsDash = preferred?.isDash == true

        val oldUri = item.localConfiguration?.uri
        val oldDash = item.localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD
        if (rebuilt == oldUri && oldDash == wantsDash) return false
        if (markCurrentSwap && index == player.currentMediaItemIndex) swappingMediaId = item.mediaId
        player.replaceMediaItem(
            index,
            item.buildUpon()
                .setUri(rebuilt)
                .setMimeType(if (wantsDash) MimeTypes.APPLICATION_MPD else null)
                .build(),
        )
        return true
    }

    /**
     * Validates a prepared route by making the exact cache request playback will make and warms
     * enough bytes for the decoder to start. A failed preferred warm is a real validation miss;
     * the caller demotes B to its already-resolved fallback instead of discovering the failure
     * during the handoff.
     */
    private suspend fun warmQueuedStartup(
        item: MediaItem,
        prime: QueuedQualityPrime,
        bytes: Long = QUEUED_STARTUP_WARM_BYTES,
        forceFallback: Boolean = false,
    ): Boolean {
        val uri = queuedRouteUri(item, prime, forceFallback) ?: return false
        val route = uri.getQueryParameter("qp")
        val preferred = prime.preferredStream
            ?.takeIf { route != "opus" && SourceResolver.preparedQueueCandidateAllowed(it.format) }
        val routeBudget = when {
            AppSettings.meteredConnection.value == true && route == "opus" ->
                minOf(bytes, METERED_OPUS_STARTUP_WARM_BYTES)
            AppSettings.meteredConnection.value == true ->
                minOf(bytes, METERED_PREFERRED_STARTUP_WARM_BYTES)
            else -> bytes
        }
        val warmBytes = if (preferred?.isDash == true) {
            minOf(routeBudget, QUEUED_DASH_MANIFEST_WARM_BYTES)
        } else {
            routeBudget
        }
        return runCatching {
            AudioCache.warmRange(uri, 0L, warmBytes)
        }.onFailure {
            TrackLog.d(
                "Orb",
                "queued ${route ?: "unknown"} startup validation failed for ${item.mediaId}: ${it.message}",
                about = item.mediaId,
            )
        }.getOrDefault(false)
    }

    /**
     * Requests Automix analysis in A -> B order without letting cellular latency starve B.
     *
     * A is always requested first. On Wi-Fi B waits for A's full result; on metered mobile data a
     * usable provisional A head may unlock B's own small head pass while A continues toward its
     * complete result. At handoff B becomes the new A, so C still cannot begin analysis before B
     * is actually the current/playing track.
     */
    /**
     * Orders only AutoPlay's untouched future section by musical proximity.
     *
     * This uses only lightweight head previews for future AutoPlay candidates; full reliable
     * analysis remains strictly A -> B -> C. Persisted/server-cached evidence is restored first.
     * The transition planner still decides the eventual A -> B recipe later.
     */
    private fun scheduleAutoplayMusicalOrdering(live: ExoPlayer) {
        if (!AppSettings.autoplay.value) return
        if (crossfade?.isTransitioning() == true) return
        val currentIndex = live.currentMediaItemIndex
        if (currentIndex !in 0 until live.mediaItemCount) return

        val firstAutoplay = ((currentIndex + 1) until live.mediaItemCount)
            .firstOrNull { live.getMediaItemAt(it).fromAutoplay }
            ?: return
        if (firstAutoplay <= 0) return
        val items = (firstAutoplay until live.mediaItemCount)
            .map { live.getMediaItemAt(it) }
            .takeWhile { it.fromAutoplay }
        if (items.size < 2) return

        val anchorItem = live.getMediaItemAt(firstAutoplay - 1)
        val membership = buildString {
            append(anchorItem.mediaId).append('|')
            items.map { it.mediaId }.sorted().forEach { append(it).append(';') }
        }
        if (membership == autoplayOrderMembership) return
        autoplayOrderMembership = membership

        val candidates = listOf(anchorItem) + items
        candidates.forEach { trackAnalyzer.restoreStored(it.mediaId) }
        autoplayOrderJob?.cancel()
        autoplayOrderJob = scope.launch {
            delay(AUTOPLAY_ORDER_STORE_SETTLE_MS)

            if (AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                RemoteAutomixClient.isAvailable()
            ) {
                withContext(Dispatchers.IO) {
                    candidates.take(AUTOPLAY_REMOTE_ANALYSIS_LOOKAHEAD).forEach { item ->
                        if (!trackAnalyzer.analysisFor(item.mediaId).isUsable) {
                            RemoteAutomixClient.cachedAnalysisForQueue(item.mediaId)
                                ?.let(trackAnalyzer::acceptCachedAnalysis)
                        }
                    }
                }
            }

            val preview = candidates.take(AUTOPLAY_PREVIEW_ANALYSIS_LOOKAHEAD)
            val deadline = SystemClock.elapsedRealtime() + AUTOPLAY_PREVIEW_BUDGET_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                var missing = false
                preview.forEach { item ->
                    if (!trackAnalyzer.analysisFor(item.mediaId).isUsable) {
                        missing = true
                        val uri = item.localConfiguration?.uri ?: return@forEach
                        val durationSeconds = runCatching { uri.getQueryParameter("d") }
                            .getOrNull()?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0
                        trackAnalyzer.requestQueuePreview(item.mediaId, uri, durationSeconds)
                    }
                }
                if (!missing) break
                delay(AUTOPLAY_PREVIEW_POLL_MS)
            }
            applyAutoplayMusicalOrder()
        }
    }

    private fun applyAutoplayMusicalOrder() {
        val live = player ?: return
        if (!AppSettings.autoplay.value) return
        if (crossfade?.isTransitioning() == true) return
        val currentIndex = live.currentMediaItemIndex
        if (currentIndex !in 0 until live.mediaItemCount) return
        val firstAutoplay = ((currentIndex + 1) until live.mediaItemCount)
            .firstOrNull { live.getMediaItemAt(it).fromAutoplay }
            ?: return
        if (firstAutoplay <= 0) return

        val tail = (firstAutoplay until live.mediaItemCount)
            .map { live.getMediaItemAt(it) }
            .takeWhile { it.fromAutoplay }
        if (tail.size < 2) return

        var anchor = trackAnalyzer.analysisFor(live.getMediaItemAt(firstAutoplay - 1).mediaId)
        if (!anchor.isUsable) return

        // Respect explicit user placement. AutoPlay may continuously reorganize only
        // untouched future suggestions; manually pinned entries keep their slot.
        val desired = tail.toMutableList()
        var cursor = 0
        while (cursor < tail.size) {
            if (ManualQueuePins.isPinned(tail[cursor].mediaId) || tail[cursor].queuePinned) {
                val pinnedAnalysis = trackAnalyzer.analysisFor(tail[cursor].mediaId)
                if (pinnedAnalysis.isUsable) anchor = pinnedAnalysis
                cursor += 1
                continue
            }

            val segmentStart = cursor
            while (
                cursor < tail.size &&
                !ManualQueuePins.isPinned(tail[cursor].mediaId) &&
                !tail[cursor].queuePinned
            ) {
                cursor += 1
            }
            val segment = tail.subList(segmentStart, cursor).toMutableList()
            val ordered = ArrayList<MediaItem>(segment.size)
            while (segment.isNotEmpty()) {
                val scored = segment.mapNotNull { candidate ->
                    val analysis = trackAnalyzer.analysisFor(candidate.mediaId)
                    if (!analysis.isUsable) null
                    else candidate to automixQueueCompatibility(anchor, analysis)
                }
                if (scored.isEmpty()) {
                    ordered += segment
                    break
                }
                val best = scored.maxByOrNull { it.second }!!.first
                ordered += best
                anchor = trackAnalyzer.analysisFor(best.mediaId)
                segment.remove(best)
            }
            ordered.forEachIndexed { offset, item ->
                desired[segmentStart + offset] = item
            }
        }

        val currentOrder = tail.map { it.mediaId }
        val desiredOrder = desired.map { it.mediaId }
        if (currentOrder == desiredOrder) return

        autoplayQueueReordering = true
        try {
            desiredOrder.forEachIndexed { offset, mediaId ->
                val target = firstAutoplay + offset
                val actual = (target until live.mediaItemCount)
                    .firstOrNull { live.getMediaItemAt(it).mediaId == mediaId }
                    ?: return@forEachIndexed
                if (actual != target) live.moveMediaItem(actual, target)
            }
            TrackLog.i(
                "AUTOPLAY",
                "ordered by Automix compatibility: ${desiredOrder.joinToString(" -> ")}",
                about = live.currentMediaItem?.mediaId,
            )
        } finally {
            autoplayQueueReordering = false
        }
    }

    private fun requestCurrentAutomixPairAnalysis(player: ExoPlayer) {
        if (!AppSettings.smartFadeEnabled.value ||
            !canRunAutomixOnNetwork(AppSettings.internetAvailable.value)) return
        if (player.currentMediaItem?.albumSequential == true && !QueueShuffle.enabled.value) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return
        val current = player.getMediaItemAt(currentIndex)
        requestAutomixAnalysisFor(player, currentIndex, current)

        val nextIndex = player.nextMediaItemIndex
        if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount &&
            AppSettings.wifiConnection.value == true
        ) {
            // Preserve strict A -> B inference ordering, but pull B's immutable
            // analysis carrier down while A is using CPU/server time.
            player.getMediaItemAt(nextIndex).localConfiguration?.uri?.let(trackAnalyzer::prewarmReliableAudio)
        }

        if (!automixOutgoingReadyForIncoming(current.mediaId)) return

        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
        val next = player.getMediaItemAt(nextIndex)
        // B's Automix preparation is composite: start DSP analysis and best-route discovery
        // together. The UI does not call B "Analysed" until both sides have settled.
        requestAutomixAnalysisFor(player, nextIndex, next)
        primeImmediateSuccessorQuality(player)
    }

    /**
     * Gate used by CrossfadeController's heartbeat. The controller intentionally stays ignorant of
     * analyzer lifecycle; this service is the single place that decides whether an item is A or B
     * and therefore whether it is currently eligible for analysis.
     */
    private fun requestSequentialAutomixAnalysis(item: MediaItem, durationMs: Long) {
        if (!AppSettings.smartFadeEnabled.value ||
            !canRunAutomixOnNetwork(AppSettings.internetAvailable.value)) return
        val live = player ?: return
        val currentIndex = live.currentMediaItemIndex
        if (currentIndex !in 0 until live.mediaItemCount) return
        val current = live.getMediaItemAt(currentIndex)
        if (current.albumSequential && !QueueShuffle.enabled.value) return

        if (item.mediaId == current.mediaId) {
            item.localConfiguration?.uri?.let { uri ->
                trackAnalyzer.requestOutgoingTail(item.mediaId, uri, durationMs / 1000.0)
            }
            return
        }

        val nextIndex = live.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until live.mediaItemCount) return
        val next = live.getMediaItemAt(nextIndex)
        if (item.mediaId != next.mediaId) return
        if (!automixOutgoingReadyForIncoming(current.mediaId)) return

        item.localConfiguration?.uri?.let { uri ->
            // Once A is final, B first gets the cheapest useful opening pass.
            // If that produces enough BPM/beat/key/entry evidence, planning can
            // start immediately while requestReliable continues the full refinement.
            if (!trackAnalyzer.isReadyForPlan(item.mediaId, incoming = true)) {
                trackAnalyzer.requestIncomingHead(
                    item.mediaId,
                    uri,
                    durationMs / 1000.0,
                )
            }
        }
        primeImmediateSuccessorQuality(live)
    }

    /**
     * B may start as soon as A has enough transition evidence to plan A -> B.
     * Refinements that do not change readiness must not serialize the pair for
     * another tens of seconds. The A -> B ordering is preserved because B is
     * still gated on A reaching READY_FOR_PLAN first.
     */
    private fun automixOutgoingReadyForIncoming(mediaId: String): Boolean =
        trackAnalyzer.isReadyForPlan(mediaId, incoming = false)

    private fun requestAutomixAnalysisFor(player: ExoPlayer, index: Int, item: MediaItem) {
        if (!canRunAutomixOnNetwork(AppSettings.internetAvailable.value)) return
        val uri = item.localConfiguration?.uri ?: return
        val durationMs = player.currentTimeline
            .takeUnless { it.isEmpty }
            ?.takeIf { index < it.windowCount }
            ?.getWindow(index, Timeline.Window())
            ?.durationMs
            ?.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: runCatching { uri.getQueryParameter("d") }
                .getOrNull()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1000L)
            ?: 0L

        val currentIndex = player.currentMediaItemIndex
        if (index == currentIndex) {
            trackAnalyzer.requestOutgoingTail(item.mediaId, uri, durationMs / 1000.0)
        } else {
            trackAnalyzer.requestIncomingHead(item.mediaId, uri, durationMs / 1000.0)
        }
    }

    /**
     * Hands the cache the queue ahead of the one playing: [AudioCache.QUEUE_DEPTH]
     * tracks is more than it does anything with, but it decides that, not this.
     */
    private fun protectCurrentMobileBuffer(player: ExoPlayer) {
        val headroomMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        if (mobileReadAheadGuard.shouldCancel(
                metered = AppSettings.meteredConnection.value == true,
                bufferedMs = headroomMs,
                fullyBuffered = player.bufferedPercentage == 100,
            )
        ) {
            // The already-playing song has priority when cellular throughput drops.
            // Cancel speculative queue bytes/resolves, not the main stream or B's
            // prepared Automix deck. Once the main player has recovered its
            // cushion, prefetchAround() is free to resume read-ahead.
            AudioCache.cancel()
            TrackLog.d("Orb", "MOBILE_BUFFER low (${headroomMs}ms); paused queue read-ahead", about = player.currentMediaItem?.mediaId)
        }
    }

    private fun prefetchAround(player: ExoPlayer) {
        // A shorter remaining track time can lower the normal prefetch target to
        // just two seconds. That must not undo an explicit low-buffer cancellation
        // in the very same progress tick: wait for the hysteresis reset first.
        if (AppSettings.meteredConnection.value == true && mobileReadAheadGuard.suspended) return
        val firstAudibleAt = currentTrackFirstAudibleAtMs ?: return
        if (SystemClock.elapsedRealtime() - firstAudibleAt < FIRST_AUDIO_FALLBACK_STABILIZE_MS) return
        val remainingTrackMs = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(player.currentPosition)
            ?.coerceAtLeast(0L)
        val meteredPrefetchTargetMs = remainingTrackMs
            ?.let { minOf(METERED_PREFETCH_MIN_HEADROOM_MS, (it / 2).coerceAtLeast(2_000L)) }
            ?: METERED_PREFETCH_MIN_HEADROOM_MS
        if (AppSettings.meteredConnection.value == true &&
            player.bufferedPercentage < 100 &&
            (player.bufferedPosition - player.currentPosition) < meteredPrefetchTargetMs
        ) {
            // Don't use a weak mobile radio to warm B while A barely has
            // enough bytes to continue. Progress/timeline callbacks retry later.
            return
        }
        val nextIndex = player.nextMediaItemIndex
        val upcoming = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            (nextIndex..end).map { player.getMediaItemAt(it) }
        } else {
            emptyList()
        }
        AudioCache.prefetchQueue(upcoming.map { it.mediaId })

        // Ordinary read-ahead and B's quality preflight are separate jobs. Both are held
        // off the Play -> first-note path; this queue warm-up is released first because it
        // is only the cheap official-carrier safety net for the wider queue.
    }

    /**
     * B is planning-ready only after the exact route it will start with has been validated.
     * `true` may mean the best discovered rendition or the fallback after the quality deadline;
     * it never means merely that a URL candidate exists.
     */
    private fun automixIncomingAudioReady(item: MediaItem): Boolean {
        val live = player ?: return false
        val current = live.currentMediaItem ?: return false
        val nextIndex = live.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until live.mediaItemCount) return false
        val next = live.getMediaItemAt(nextIndex)
        if (next.mediaId != item.mediaId) return false

        val song = item.toSong()
        if (song.localUri != null) return true

        val key = "${current.mediaId}->${item.mediaId}"
        if (nextQualityPrimeKey != key) return false
        val decision = nextQualityPrimeSucceeded ?: return false
        val prime = freshQueuedQualityPrime(item.mediaId) ?: return false
        return if (decision) prime.preferredStream != null else prime.opusFallback != null
    }

    /**
     * Starts only after the current song has cleared the protected first-audio grace and gets at most 30 seconds
     * to identify and prove the best allowed stream for its immediate successor.
     * On Wi-Fi Maximum this means Lossless/Hi-Res first and Hi-Q only after that
     * phase misses; on lossy ceilings it prepares the best verified Hi-Q candidate.
     * Failure never delays playback because the official fallback resolves in parallel.
     *
     * B is reported as analysed only after this lookup has settled on a validated best route or,
     * at the deadline, a validated fallback. Only then is the pair eligible for server planning.
     */
    private fun primeImmediateSuccessorQuality(player: ExoPlayer) {
        val firstAudibleAt = currentTrackFirstAudibleAtMs ?: return
        if (SystemClock.elapsedRealtime() - firstAudibleAt < FIRST_AUDIO_QUALITY_GRACE_MS) return
        val current = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) {
            nextQualityPrimeJob?.cancel()
            nextQualityPrimeJob = null
            nextQualityPrimeKey = null
            nextQualityPrimeSucceeded = null
            nextTransitionWarmJob?.cancel()
            nextTransitionWarmJob = null
            nextTransitionWarmKey = null
            if (AppSettings.smartFadeEnabled.value) NerdStats.clearAutomix()
            return
        }

        val next = player.getMediaItemAt(nextIndex)
        val nextSong = next.toSong()
        val key = "${current.mediaId}->${next.mediaId}"

        // Best-route discovery is part of B's Automix preparation, but never at A's expense.
        // Cellular starts it as soon as A has a modest safety runway; until then the lightweight
        // cache warm-up protects immediate playback. reportProgress() retries this gate often.
        if (AppSettings.meteredConnection.value == true) {
            val headroomMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            val requiredHeadroom = if (AppSettings.smartFadeEnabled.value) {
                METERED_AUTOMIX_NEXT_QUALITY_HEADROOM_MS
            } else {
                METERED_NEXT_QUALITY_HEADROOM_MS
            }
            if (player.bufferedPercentage < 100 && headroomMs < requiredHeadroom) {
                return
            }
        }

        val tracingAutomix = AppSettings.smartFadeEnabled.value
        if (tracingAutomix) {
            NerdStats.beginAutomixPair(
                outgoingId = current.mediaId,
                incomingId = next.mediaId,
                outgoingTitle = current.mediaMetadata.title?.toString().orEmpty(),
                incomingTitle = next.mediaMetadata.title?.toString().orEmpty(),
            )
        }

        if (nextQualityPrimeKey == key && nextQualityPrimeJob?.isActive == true) {
            if (tracingAutomix) {
                NerdStats.onAutomixQuality(current.mediaId, next.mediaId, NerdStats.AutomixQuality.SEARCHING)
            }
            return
        }
        if (nextQualityPrimeKey == key && nextQualityPrimeSucceeded != null) return

        nextQualityPrimeJob?.cancel()
        nextQualityPrimeJob = null
        nextQualityPrimeKey = key
        nextQualityPrimeSucceeded = null
        if (nextTransitionWarmKey != key) {
            nextTransitionWarmJob?.cancel()
            nextTransitionWarmJob = null
            nextTransitionWarmKey = null
        }

        if (upgradeJob?.isActive == true && upgradeFor == current.mediaId) {
            TrackLog.d(
                "Orb",
                "successor ${next.mediaId} quality preparation running beside current-track upgrade",
                about = next.mediaId,
            )
        }

        if (nextSong.localUri != null) {
            nextQualityPrimeSucceeded = false
            if (tracingAutomix) {
                NerdStats.onAutomixQuality(current.mediaId, next.mediaId, NerdStats.AutomixQuality.LOCAL)
            }
            return
        }

        if (tracingAutomix) {
            NerdStats.onAutomixQuality(
                current.mediaId,
                next.mediaId,
                NerdStats.AutomixQuality.SEARCHING,
                outgoingTitle = current.mediaMetadata.title?.toString().orEmpty(),
                incomingTitle = next.mediaMetadata.title?.toString().orEmpty(),
            )
        }

        TrackLog.d(
            "Orb",
            "A is audible; preparing best available quality for successor ${nextSong.videoId}",
            about = nextSong.videoId,
        )

        nextQualityPrimeJob = scope.launch(Dispatchers.IO + TrackLog.about(nextSong.videoId)) {
            try {
                val discovered = prepareQueuedQuality(nextSong, NEXT_TRACK_QUALITY_SEARCH_MS)

                // Validate the preferred candidate before it is attached to the live queue.
                // The synthetic qp route uses the same resolver/cache path B will use later.
                val stillNextItem = withContext(Dispatchers.Main) {
                    val live = this@PlaybackService.player ?: return@withContext null
                    val liveNext = live.nextMediaItemIndex
                    if (nextQualityPrimeKey != key ||
                        live.currentMediaItem?.mediaId != current.mediaId ||
                        liveNext == C.INDEX_UNSET || liveNext !in 0 until live.mediaItemCount ||
                        live.getMediaItemAt(liveNext).mediaId != nextSong.videoId
                    ) null else live.getMediaItemAt(liveNext)
                } ?: return@launch

                // A very short A may already have entered the transition-buffer lock while the
                // quality search was still running. Do not change B's route underneath that lock;
                // the fallback already published by prepareQueuedQuality is the safe handoff, and
                // B's live upgrade path will continue after it starts.
                val transitionOwnsRoute = withContext(Dispatchers.Main) {
                    nextTransitionWarmKey == key
                }
                if (transitionOwnsRoute) {
                    TrackLog.d(
                        "Orb",
                        "late successor quality answer kept out of locked transition route for ${nextSong.videoId}",
                        about = nextSong.videoId,
                    )
                    return@launch
                }

                // Make the candidate visible only to the synthetic validation request. The real
                // queue item still has no qp route until validation succeeds below.
                queuedQualityPrimes[nextSong.videoId] = discovered
                if (discovered.preferredStream != null) StreamChoice.forget(nextSong.videoId)

                val preferredReady = if (discovered.preferredStream != null) {
                    warmQueuedStartup(
                        item = stillNextItem,
                        prime = discovered,
                        bytes = QUEUED_STARTUP_WARM_BYTES,
                        forceFallback = false,
                    )
                } else false

                val finalPrime = if (preferredReady) {
                    discovered.copy(decidedAtMs = SystemClock.elapsedRealtime())
                } else {
                    if (discovered.preferredStream != null) {
                        StreamChoice.forget(nextSong.videoId)
                        TrackLog.w(
                            "Orb",
                            "preferred successor candidate failed validation; demoting ${nextSong.videoId} to prepared fallback",
                            about = nextSong.videoId,
                        )
                    }
                    discovered.copy(
                        preferredStream = null,
                        decidedAtMs = SystemClock.elapsedRealtime(),
                    )
                }

                // The transition-buffer lock may have started while validation was in flight.
                // If so, it owns the route from here and this late result must not replace it.
                if (withContext(Dispatchers.Main) { nextTransitionWarmKey == key }) {
                    return@launch
                }
                queuedQualityPrimes[nextSong.videoId] = finalPrime

                // A fallback only completes B's composite analysis after the exact opening route
                // has also been proven readable. If validation fails, leave the decision pending so
                // the next heartbeat can resolve a fresh carrier instead of declaring B ready early.
                val fallbackReady = if (!finalPrime.betterPrepared && finalPrime.opusFallback != null) {
                    warmQueuedStartup(
                        item = stillNextItem,
                        prime = finalPrime,
                        bytes = QUEUED_FALLBACK_WARM_BYTES,
                        forceFallback = true,
                    )
                } else {
                    finalPrime.betterPrepared
                }

                val published = withContext(Dispatchers.Main) {
                    if (nextQualityPrimeKey != key) return@withContext false
                    val live = this@PlaybackService.player ?: return@withContext false
                    val liveNext = live.nextMediaItemIndex
                    if (live.currentMediaItem?.mediaId != current.mediaId ||
                        liveNext == C.INDEX_UNSET || liveNext !in 0 until live.mediaItemCount ||
                        live.getMediaItemAt(liveNext).mediaId != nextSong.videoId
                    ) return@withContext false

                    val routeValidated = finalPrime.betterPrepared || fallbackReady
                    nextQualityPrimeSucceeded = if (routeValidated) finalPrime.betterPrepared else null
                    if (!routeValidated) {
                        queuedQualityPrimes.remove(nextSong.videoId, finalPrime)
                        TrackLog.w(
                            "Orb",
                            "successor ${nextSong.videoId} route validation failed; B remains analysing",
                            about = nextSong.videoId,
                        )
                        return@withContext false
                    }
                    if (tracingAutomix) {
                        val quality = when {
                            finalPrime.losslessPrepared -> NerdStats.AutomixQuality.LOSSLESS_READY
                            finalPrime.hiQualityPrepared -> NerdStats.AutomixQuality.HI_QUALITY_READY
                            else -> NerdStats.AutomixQuality.OPUS_FALLBACK
                        }
                        NerdStats.onAutomixQuality(current.mediaId, next.mediaId, quality)
                    }
                    applyPreparedQualityHint(live, liveNext, finalPrime)
                    true
                }
                if (!published) return@launch

                val prepared = finalPrime.preferredStream
                if (prepared != null) {
                    TrackLog.d(
                        "Orb",
                        "successor ${nextSong.videoId} validated and prepared at ${prepared.format.summary}",
                        about = nextSong.videoId,
                    )
                } else {
                    TrackLog.d(
                        "Orb",
                        "successor ${nextSong.videoId} has no validated upgrade yet; prepared fallback will start and live upgrade remains armed",
                        about = nextSong.videoId,
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (nextQualityPrimeKey == key) {
                        nextQualityPrimeJob = null
                        val live = this@PlaybackService.player
                        if (live?.currentMediaItem?.mediaId == current.mediaId) {
                            // A's own upgrade remains independent from B's preparation.
                            lookForBetterCopy(live)
                        }
                    }
                }
            }
        }
    }

    /**
     * When A approaches its transition, stop doing discovery work for B and spend the network
     * budget on the route B will actually open. The early quality job has normally settled long
     * before this point; this is a safety lock for short tracks and a second validity check for
     * signed URLs discovered near A's start.
     */
    private fun prioritizeImminentSuccessorBuffer(player: ExoPlayer) {
        val duration = player.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return
        val remaining = (duration - player.currentPosition).coerceAtLeast(0L)
        val metered = AppSettings.meteredConnection.value == true
        val priorityLeadMs = when {
            metered && AppSettings.smartFadeEnabled.value -> METERED_AUTOMIX_BUFFER_PRIORITY_LEAD_MS
            metered -> METERED_NEXT_TRACK_BUFFER_PRIORITY_LEAD_MS
            else -> NEXT_TRACK_BUFFER_PRIORITY_LEAD_MS
        }
        if (remaining > priorityLeadMs) return

        val current = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
        val next = player.getMediaItemAt(nextIndex)
        val nextSong = next.toSong()
        val key = "${current.mediaId}->${next.mediaId}"

        if (metered) {
            // Do not kill B's quality search merely because the transition is approaching. The old
            // 110-second lock made cellular Automix settle on Opus long before the better route had
            // a fair chance. Keep discovery alive until a real handoff deadline; only then validate
            // the already-prepared fallback so immediate playback remains guaranteed.
            val headroomMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            if (player.bufferedPercentage < 100 &&
                headroomMs < METERED_TRANSITION_WARM_MIN_HEADROOM_MS
            ) {
                return
            }

            val settledPrime = freshQueuedQualityPrime(next.mediaId)
            val qualitySettled = nextQualityPrimeKey == key && nextQualityPrimeSucceeded != null
            if (!qualitySettled && nextQualityPrimeJob?.isActive == true &&
                remaining > METERED_AUTOMIX_QUALITY_FALLBACK_DEADLINE_MS
            ) {
                return
            }
            if (nextTransitionWarmKey == key) return

            // If the best route already won, warm that exact route rather than silently demoting B.
            if (qualitySettled && settledPrime != null) {
                val forceFallback = nextQualityPrimeSucceeded != true
                nextTransitionWarmKey = key
                nextTransitionWarmJob = scope.launch(Dispatchers.IO + TrackLog.about(next.mediaId)) {
                    val warmed = warmQueuedStartup(
                        item = next,
                        prime = settledPrime,
                        bytes = METERED_TRANSITION_WARM_BYTES,
                        forceFallback = forceFallback,
                    )
                    if (!warmed) {
                        // Do not turn one bad cellular signed URL into a retry storm. The opening
                        // prefix was already validated earlier; if deepening it now fails, keep
                        // this transition locked and let playback's transport recovery mint a new
                        // carrier only if/when the cached prefix is actually exhausted.
                        TrackLog.w(
                            "Orb",
                            "cellular transition warm failed for ${next.mediaId}; keeping existing startup prefix without retry storm",
                            about = next.mediaId,
                        )
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        if (nextTransitionWarmKey != key) return@withContext
                        val live = this@PlaybackService.player ?: return@withContext
                        val liveNext = live.nextMediaItemIndex
                        if (live.currentMediaItem?.mediaId != current.mediaId ||
                            liveNext == C.INDEX_UNSET || liveNext !in 0 until live.mediaItemCount ||
                            live.getMediaItemAt(liveNext).mediaId != next.mediaId
                        ) return@withContext
                        applyPreparedQualityHint(live, liveNext, settledPrime, forceFallback = forceFallback)
                    }
                }
                return
            }

            // The deadline arrived before a better candidate was validated. This is the explicit
            // fallback clause: stop optional discovery and prove the official first-note carrier.
            if (remaining > METERED_AUTOMIX_QUALITY_FALLBACK_DEADLINE_MS) return
            nextQualityPrimeJob?.cancel()
            nextQualityPrimeJob = null
            nextQualityPrimeSucceeded = null
            nextTransitionWarmJob?.cancel()
            nextTransitionWarmKey = key
            nextTransitionWarmJob = scope.launch(Dispatchers.IO + TrackLog.about(next.mediaId)) {
                val alreadyResolved = freshQueuedQualityPrime(next.mediaId)?.opusFallback
                val fallback = alreadyResolved ?: withTimeoutOrNull(OPUS_FALLBACK_PRIME_MS) {
                    runCatching { resolveYouTubeCarrierWithRematch(next.mediaId, TrackMatcher.targetOf(next.toSong())) }.getOrNull()
                }
                if (fallback == null) {
                    withContext(Dispatchers.Main) {
                        if (nextTransitionWarmKey == key) nextTransitionWarmKey = null
                    }
                    return@launch
                }

                val prime = QueuedQualityPrime(
                    preferredStream = null,
                    opusFallback = fallback,
                    decidedAtMs = SystemClock.elapsedRealtime(),
                )
                queuedQualityPrimes[next.mediaId] = prime
                val warmed = warmQueuedStartup(
                    item = next,
                    prime = prime,
                    bytes = METERED_TRANSITION_WARM_BYTES,
                    forceFallback = true,
                )
                if (!warmed) {
                    // Do not retry the same deep cellular warm from every heartbeat. The handoff
                    // can still resolve a fresh carrier on demand, while a retry loop here would
                    // compete with A and repeatedly hit the same signed URL.
                    TrackLog.w(
                        "Orb",
                        "cellular fallback warm failed for ${next.mediaId}; deferring fresh resolution to handoff/recovery",
                        about = next.mediaId,
                    )
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (nextTransitionWarmKey != key) return@withContext
                    val live = this@PlaybackService.player ?: return@withContext
                    val liveNext = live.nextMediaItemIndex
                    if (live.currentMediaItem?.mediaId != current.mediaId ||
                        liveNext == C.INDEX_UNSET || liveNext !in 0 until live.mediaItemCount ||
                        live.getMediaItemAt(liveNext).mediaId != next.mediaId
                    ) return@withContext

                    applyPreparedQualityHint(live, liveNext, prime, forceFallback = true)
                    nextQualityPrimeSucceeded = false
                    if (AppSettings.smartFadeEnabled.value) {
                        NerdStats.onAutomixQuality(
                            current.mediaId,
                            next.mediaId,
                            NerdStats.AutomixQuality.OPUS_FALLBACK,
                        )
                    }
                    TrackLog.d(
                        "Orb",
                        "quality deadline reached; validated fallback locked for ${nextSong.videoId}",
                        about = next.mediaId,
                    )
                }
            }
            return
        }

        if (nextQualityPrimeKey != key) {
            primeImmediateSuccessorQuality(player)
            return
        }
        if (nextTransitionWarmKey == key) return

        val prime = freshQueuedQualityPrime(next.mediaId) ?: return
        nextTransitionWarmKey = key

        // If the only thing still running is the better-source search, the handoff wins now.
        // The fallback has already resolved (otherwise no prime would exist), so cancel late
        // discovery rather than letting it compete with B's startup buffer. B will restart its
        // normal live upgrade after it becomes current.
        if (nextQualityPrimeJob?.isActive == true && !prime.betterPrepared) {
            nextQualityPrimeJob?.cancel()
            nextQualityPrimeJob = null
            nextQualityPrimeSucceeded = false
            TrackLog.d(
                "Orb",
                "transition window reached before B quality search settled; locking prepared fallback",
                about = next.mediaId,
            )
        }

        nextTransitionWarmJob?.cancel()
        nextTransitionWarmJob = scope.launch(Dispatchers.IO + TrackLog.about(next.mediaId)) {
            // From this point onward the route is intentionally stable. If the early preferred
            // candidate is still valid, deepen its opening buffer. If it is not, pin the warmed
            // fallback and let B's normal post-start upgrade continue after the handoff.
            val preferredReady = if (prime.betterPrepared) {
                warmQueuedStartup(
                    item = next,
                    prime = prime,
                    bytes = QUEUED_TRANSITION_WARM_BYTES,
                    forceFallback = false,
                )
            } else false

            val lockedPrime = if (preferredReady) {
                prime.copy(decidedAtMs = SystemClock.elapsedRealtime())
            } else {
                prime.copy(preferredStream = null, decidedAtMs = SystemClock.elapsedRealtime())
            }
            queuedQualityPrimes[next.mediaId] = lockedPrime

            if (!lockedPrime.betterPrepared) {
                warmQueuedStartup(
                    item = next,
                    prime = lockedPrime,
                    bytes = QUEUED_FALLBACK_WARM_BYTES,
                    forceFallback = true,
                )
            }

            withContext(Dispatchers.Main) {
                if (nextQualityPrimeKey != key || nextTransitionWarmKey != key) return@withContext
                val live = this@PlaybackService.player ?: return@withContext
                val liveNext = live.nextMediaItemIndex
                if (live.currentMediaItem?.mediaId != current.mediaId ||
                    liveNext == C.INDEX_UNSET || liveNext !in 0 until live.mediaItemCount ||
                    live.getMediaItemAt(liveNext).mediaId != next.mediaId
                ) return@withContext

                // If the early discovery job is still finishing, this explicit route becomes the
                // transition lock. Its publish step below is not allowed to replace it afterwards.
                nextQualityPrimeSucceeded = lockedPrime.betterPrepared
                applyPreparedQualityHint(live, liveNext, lockedPrime)
                if (AppSettings.smartFadeEnabled.value) {
                    val quality = when {
                        lockedPrime.losslessPrepared -> NerdStats.AutomixQuality.LOSSLESS_READY
                        lockedPrime.hiQualityPrepared -> NerdStats.AutomixQuality.HI_QUALITY_READY
                        else -> NerdStats.AutomixQuality.OPUS_FALLBACK
                    }
                    NerdStats.onAutomixQuality(current.mediaId, next.mediaId, quality)
                }
                TrackLog.d(
                    "Orb",
                    "transition buffer locked for ${next.mediaId} on ${queuedRouteFor(lockedPrime)} with ${remaining}ms left in A",
                    about = next.mediaId,
                )
            }
        }
    }

    /**
     * Feeds played-seconds to [PlaybackTracker]. The tracker can't read the
     * player itself — ExoPlayer is confined to this thread — and a history
     * entry with no watchtime behind it barely registers as a listen, so the
     * sampling has to come from here.
     */
    private fun reportProgress() {
        scope.launch {
            while (isActive) {
                // Re-read every tick rather than captured once: the session
                // moves between two players, and a sampler pinned to the one
                // that happened to be first would go on reporting a player that
                // has been silent since the last crossfade.
                val player = this@PlaybackService.player
                if (player != null && player.isPlaying) {
                    lastPositionSeconds = player.currentPosition / 1000
                    player.currentMediaItem?.mediaId?.let {
                        PlaybackTracker.onProgress(it, lastPositionSeconds)
                    }
                    socialPlaybackReporter.onProgress(
                        song = player.currentMediaItem?.toSong(),
                        positionMs = player.currentPosition,
                        durationMs = player.duration.takeIf { it > 0 },
                    )
                    // Queue structure is already saved on media-item changes and pause.
                    // Updating only every 15s here avoids serializing up to 60 items
                    // every 5s while still keeping a recent crash-recovery position.
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastPeriodicQueueSaveAtMs >= PERIODIC_QUEUE_SAVE_MS) {
                        saveQueue()
                        lastPeriodicQueueSaveAtMs = nowMs
                    }
                    // The renderer can settle on its format a moment after the
                    // track change, which no callback of ours follows up on.
                    publishNerdStats()
                    // The backstop for the second look. The callbacks that
                    // start it fire at moments a track may not be resolved
                    // yet — the resolve happens on the loader thread when the
                    // source is opened, which for a track skipped to directly
                    // is after its own transition has been and gone. Cheap to
                    // repeat: it returns immediately unless the track is
                    // pending and nothing is already looking.
                    lookForBetterCopy(player)
                    // A queued prefetch that started with healthy bandwidth can still
                    // be competing when the mobile buffer collapses. Stop speculative
                    // reads promptly, and resume only with a rebuilt cushion.
                    protectCurrentMobileBuffer(player)
                    // A mobile link may not have had enough current-song buffer
                    // at first audio to release queue read-ahead. Recheck at
                    // the existing low-frequency sampler; AudioCache deduplicates
                    // identical queue requests once warming starts.
                    prefetchAround(player)
                    // Queue quality read-ahead is a playback rule, not an Automix
                    // or Shuffle rule. Keep asserting the immediate-successor
                    // preflight from the progress sampler as a backstop for normal
                    // ordered album/playlist playback too. The pair key makes this
                    // effectively free once the current -> next decision is running
                    // or has already settled.
                    primeImmediateSuccessorQuality(player)
                    prioritizeImminentSuccessorBuffer(player)
                    // AutoPlay musical ordering is event-driven by analysis/timeline
                    // changes. Do not poll/re-score the future queue on this sampler.
                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    /**
     * Pause when the sleep timer runs out.
     *
     * `collectLatest` is what makes re-setting the timer work: the pending wait
     * is cancelled and restarted on the new deadline instead of both firing.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player?.pause()
                SleepTimer.cancel()
            }
        }
    }

    /**
     * Buffers as far ahead as a whole track rather than a rolling window.
     *
     * Media3's audio default stops loading at 13 buffer segments — around 830kB,
     * or 40 seconds of a 160kbps stream — and everything past that is fetched
     * only as playback consumes it. Since the data source writes through to
     * [AudioCache], how far ahead the player loads is also how much of the
     * track ends up on disk, and a seek past the buffered part is the one that
     * has to wait on the network.
     *
     * This matters for the track playback *starts* on. Everything after it is
     * on disk in full before it is reached, read ahead while it was still the
     * queued track — a first track has had no such chance.
     *
     * The byte ceiling is what governs; the duration is set past any song so
     * that it never becomes the binding constraint.
     *
     * Two further departures from the defaults, both about how long the
     * listener waits for sound:
     *
     *  - **Back buffer.** Media3 keeps nothing behind the playhead, so a seek
     *    *backwards* drops the buffer and reloads, while a seek forwards lands
     *    in samples already held. Half a minute of history closes that gap for
     *    the seek people actually make — nudging back a few seconds to catch a
     *    lyric — and it is deliberately no longer than that. The byte ceiling
     *    above counts *everything* the player holds, history included, so a
     *    back buffer wide enough to keep a whole track would spend the entire
     *    read-ahead budget on audio already heard: past the ceiling, loading
     *    stops, and since every second played moves a second from the front of
     *    the buffer to the back, the total never falls again and it never
     *    restarts. Read-ahead collapses and the track stalls every couple of
     *    seconds for the rest of its length. Seeking further back than this
     *    window is a disk read anyway, not a network one — [AudioCache] has
     *    written every byte already played.
     *  - **Thresholds to (re)start playback.** The defaults — 2.5s of audio
     *    before starting, 5s before resuming after a rebuffer — are sized for
     *    streaming video over a network that might stall again. Here the bytes
     *    are usually already on disk, so those seconds are spent waiting on a
     *    buffer that fills instantly and are simply dead air after a seek.
     *    Resuming is given more room than starting: a stall means the network
     *    is genuinely struggling, and coming back with a second of audio in
     *    hand only buys the next stall.
     */
    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            /* maxBufferMs = */ FAR_BUFFER_MS,
            /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        // Never let a byte ceiling stop loading while the time buffer is still
        // shallow. This matters most for high-bitrate audio, where 16 MiB can
        // represent much less runway than it does for AAC/Opus.
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(/* backBufferDurationMs = */ BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()

    /**
     * Renderers whose audio sink only skips silence worth skipping.
     *
     * Media3's stock threshold is 100ms, which eats the breaths, rests and
     * pre-chorus beats *inside* a song — the setting reads as "make the music
     * sound rushed" rather than "trim dead air". A second-long floor leaves
     * musical pauses alone and still collapses the run-in and run-out of a
     * track. Everything else about the chain stays default, so
     * `skipSilenceEnabled` keeps driving it as before.
     */
    private fun silenceSkippingRenderers(
        spatial: SpatialAudioProcessor,
        eq: TransitionDjEqProcessor,
        transition: TransitionFilterProcessor,
    ) = object : DefaultRenderersFactory(this) {
        init {
            // Only a compatible preferred USB route gets PCM_FLOAT. Speaker/Bluetooth
            // paths keep Media3's stable PCM16 output even when float was requested.
            setEnableAudioFloatOutput(configuredFloatOutput)
            if (configuredFloatOutput) {
                setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    // Orb's Media3 version does not expose PREFER_SOFTWARE. Start from
                    // Media3's normal decoder ordering and remove only the Samsung FLAC
                    // decoders known to be unsafe with PCM_FLOAT. On affected Samsung
                    // devices this naturally leaves the platform software FLAC decoder
                    // as the usable fallback without depending on a newer Media3 API.
                    val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder,
                    )
                    if (mimeType == MimeTypes.AUDIO_FLAC) {
                        candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                    } else {
                        candidates
                    }
                }
            }
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    // Per-deck chain: spatial character first, DJ EQ second,
                    // transition filters last. A bass swap/filter ride must see
                    // the already-shaped deck so no later processor feeds low
                    // end back into a band the recipe deliberately removed.
                    arrayOf(spatial, eq, transition),
                    SilenceSkippingAudioProcessor(
                        MIN_SILENCE_US,
                        SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                        SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                        SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                        SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                    ),
                    SonicAudioProcessor(),
                ),
            )
            .build()
    }

    /**
     * Push current settings onto a player. Called for both: whichever one is
     * idle right now is the one the next transition will start a song on, so it
     * cannot be left on stale settings.
     */
    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
    }

    /** Runs [body] against both players, in whichever roles they currently hold. */
    private inline fun eachPlayer(body: (ExoPlayer) -> Unit) {
        player?.let(body)
        spare?.let(body)
    }

    private fun preferredUsbDevice(): AudioDeviceInfo? {
        val manager = getSystemService(AudioManager::class.java)
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
            device.isSink && device.type in setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
            )
        }
    }

    private fun shouldEnableFloatOutput(): Boolean {
        val usb = preferredUsbDevice()
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = AppSettings.outputPcmMode.value,
            isPreferredUsbRoute = AppSettings.preferUsbDac.value && usb != null,
            advertisesPcmFloat = usb?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true,
        )
    }

    private fun applyOutputRoute() {
        val manager = getSystemService(AudioManager::class.java)
        val preferred = preferredUsbDevice().takeIf { AppSettings.preferUsbDac.value }
        eachPlayer { it.setPreferredAudioDevice(preferred) }
        AudioOutputStatus.publish(
            manager = manager,
            requestedPcmMode = AppSettings.outputPcmMode.value,
            preferred = preferred,
            floatEnabled = configuredFloatOutput,
        )
    }

    private fun requestOutputReconfiguration() {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            // Replacing the renderer during a blend would cut one deck in half.
            while (crossfade?.isTransitioning() == true) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput) {
                applyOutputRoute()
            } else {
                rebuildPlayersForOutput(requestedFloat)
            }
        }
    }

    /**
     * Media3's PCM-float choice is fixed when the renderer is built. Rebuild
     * both decks in place while preserving queue, position and transport state.
     */
    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player ?: return
        val oldSpare = spare ?: return
        if (crossfade?.isTransitioning() == true) return

        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val shuffle = oldActive.shuffleModeEnabled
        val volume = oldActive.volume

        oldActive.playWhenReady = false
        oldSpare.playWhenReady = false
        oldActive.removeListener(playbackListener)
        oldActive.removeAnalyticsListener(formatListener)
        oldActive.release()
        oldSpare.release()
        transitionStemDeck?.release()
        transitionStemDeck = null

        configuredFloatOutput = enableFloat
        activeFilter = transitionFilterA
        spareFilter = transitionFilterB
        activeEq = transitionEqA
        spareEq = transitionEqB

        val newActive = buildPlayer(
            spatialAudioProcessorA, transitionEqA, transitionFilterA, ownsSession = true,
        )
        val newSpare = buildPlayer(
            spatialAudioProcessorB, transitionEqB, transitionFilterB, ownsSession = false,
        )
        val newStem = buildStemPlayer(
            spatialAudioProcessorStem, transitionEqStem, transitionFilterStem,
        )
        transitionStemDeck = TransitionStemDeck(newStem, transitionEqStem, transitionFilterStem)

        player = newActive
        spare = newSpare
        newSpare.audioSessionId = newActive.audioSessionId
        newStem.audioSessionId = newActive.audioSessionId
        AppSettings.audioSessionId.value = newActive.audioSessionId

        applySettings(newActive)
        applySettings(newSpare)
        newActive.repeatMode = repeatMode
        newActive.shuffleModeEnabled = shuffle
        newActive.volume = volume

        if (items.isNotEmpty()) {
            newActive.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
        }

        newActive.addListener(playbackListener)
        newActive.addAnalyticsListener(formatListener)
        mediaSession?.player = SessionPlayer(
            newActive,
            requireNotNull(crossfade),
            ::onTransportPlayRequested,
        )
        crossfade?.onPlayersRebuilt()
        applyOutputRoute()

        if (items.isNotEmpty()) {
            newActive.prepare()
            newActive.playWhenReady = playWhenReady
        }
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.autoplay.collect { enabled ->
                if (enabled) {
                    scheduleAutoplayRefill()
                } else {
                    autoplayFillJob?.cancel()
                    autoplayFillJob = null
                }
            }
        }
        scope.launch {
            AppSettings.skipSilence.collect { on ->
                // Active mix timing is based on unskipped media time; apply the
                // preference again when the controller releases both decks.
                if (crossfade?.isTransitioning() != true) eachPlayer { it.skipSilenceEnabled = on }
            }
        }
        scope.launch {
            AppSettings.outputPcmMode.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.preferUsbDac.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.losslessAudio.collect { enabled ->
                if (!enabled) {
                    // Master-off is immediate for all *future* playback work:
                    // cancel discovery/retry jobs and strip any queued DASH
                    // route that had already been prepared. The currently
                    // audible stream is not interrupted mid-song.
                    nextQualityPrimeJob?.cancel()
                    nextQualityPrimeJob = null
                    nextQualityPrimeKey = null
                    nextQualityPrimeSucceeded = false
                    nextTransitionWarmJob?.cancel()
                    nextTransitionWarmJob = null
                    nextTransitionWarmKey = null

                    queuedQualityPrimes.replaceAll { _, prime ->
                        if (prime.losslessPrepared) prime.copy(preferredStream = null) else prime
                    }
                    player?.let { live ->
                        val current = live.currentMediaItemIndex
                        for (index in 0 until live.mediaItemCount) {
                            if (index == current) continue
                            val id = live.getMediaItemAt(index).mediaId
                            freshQueuedQualityPrime(id)?.let { prime ->
                                applyPreparedQualityHint(live, index, prime)
                            }
                        }
                    }
                    spare?.let { standby ->
                        for (index in 0 until standby.mediaItemCount) {
                            val id = standby.getMediaItemAt(index).mediaId
                            freshQueuedQualityPrime(id)?.let { prime ->
                                applyPreparedQualityHint(standby, index, prime)
                            }
                        }
                    }
                } else {
                    // Re-enabling does not need a settings-screen health check.
                    // Resume core queue discovery immediately using persistent
                    // quality knowledge first and TIDAL only for unknown rows.
                    player?.takeIf { it.isPlaying || it.playWhenReady }
                        ?.let(::primeImmediateSuccessorQuality)
                }
            }
        }
        scope.launch {
            // Not applied to a player mid-transition: [CrossfadeController]
            // stacks a beatmatch stretch on top of this setting, and writing the
            // raw value over it would drop the incoming track back to its own
            // tempo halfway through a blend. The controller re-reads the setting
            // when it restores the rate, so the change still lands.
            AppSettings.playbackSpeed.collect { speed ->
                if (crossfade?.isTransitioning() == true) return@collect
                eachPlayer { it.setPlaybackSpeed(speed) }
            }
        }
        // Orb 360 Audio is our own post-decode stereo DSP. It deliberately does
        // not depend on Dolby Atmos hardware or the OEM/system Atmos switch.
        // Both Automix decks follow the same preference so a transition cannot
        // change spatial character halfway through the handoff.
        scope.launch {
            AppSettings.spatialAudio.collect { enabled ->
                spatialAudioProcessorA.enabled = enabled
                spatialAudioProcessorB.enabled = enabled
                spatialAudioProcessorStem.enabled = enabled
            }
        }
    }

    private fun observeScrobbling() {
        scope.launch {
            combine<Any, ScrobblingSnapshot>(
                AppSettings.lastfmEnabled,
                AppSettings.lastfmScrobbleEnabled,
                AppSettings.lastfmNowPlaying,
                AppSettings.lastfmSessionKey,
                AppSettings.scrobbleMinDuration,
                AppSettings.scrobbleDelayPercent,
                AppSettings.scrobbleDelaySeconds,
            ) { values ->
                ScrobblingSnapshot(
                    lastfmEnabled = values[0] as Boolean,
                    scrobbleEnabled = values[1] as Boolean,
                    nowPlaying = values[2] as Boolean,
                    sessionKey = values[3] as String,
                    minDuration = values[4] as Int,
                    delayPercent = values[5] as Float,
                    delaySeconds = values[6] as Int,
                )
            }.collectLatest { snapshot ->
                scrobbleManager?.destroy()
                scrobbleManager = null
                if (snapshot.lastfmEnabled && snapshot.sessionKey.isNotBlank() &&
                    (snapshot.scrobbleEnabled || snapshot.nowPlaying)) {
                    scrobbleManager = ScrobbleManager(
                        context = applicationContext,
                        scope = scope,
                        minSongDuration = snapshot.minDuration,
                        scrobbleDelayPercent = snapshot.delayPercent,
                        scrobbleDelaySeconds = snapshot.delaySeconds,
                    ).apply {
                        useNowPlaying = snapshot.nowPlaying
                        useScrobbling = snapshot.scrobbleEnabled
                    }
                    LastfmScrobbleQueue.scheduleIfPending(applicationContext)
                    val live = player
                    if (live?.isPlaying == true) {
                        scrobbleManager?.onSongStart(live.currentMediaItem?.toSong(),
                            live.duration.takeIf { it > 0 })
                    }
                }
            }
        }
    }

    private data class ScrobblingSnapshot(
        val lastfmEnabled: Boolean,
        val scrobbleEnabled: Boolean,
        val nowPlaying: Boolean,
        val sessionKey: String,
        val minDuration: Int,
        val delayPercent: Float,
        val delaySeconds: Int,
    )

    // ---- Discord Rich Presence -------------------------------------------------

    /**
     * Keeps the gateway connection in step with the settings that decide whether
     * there should be one, and re-pushes the presence when the settings that
     * decide what it *says* change.
     *
     * Two collectors rather than one because the two do different work. The
     * account and the master switch can only be honoured by building or tearing
     * down a connection; everything else is a field in a payload that can be
     * re-sent over the connection already open. Combining them would reconnect
     * the socket every time the user typed a character into a button label.
     */
    private fun observeDiscord() {
        scope.launch {
            combine(
                AppSettings.discordToken,
                AppSettings.discordRpcEnabled,
            ) { token, enabled -> token.takeIf { enabled && it.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    // Torn down before anything is built, so switching accounts
                    // can't leave the old one's socket up publishing under a
                    // profile the user has just disconnected.
                    discordUpdateJob?.cancel()
                    discordRpc?.let { rpc ->
                        val wasUp = discordPresenceUp
                        discordPresenceUp = false
                        // On IO, not on this collector's main thread: the
                        // teardown closes a socket, and closing one gracefully
                        // — which is what flushes the presence-clear queued on
                        // the line above — blocks until the frame is away.
                        //
                        // Bounded, and that is the point rather than a
                        // precaution. This runs on the way to *replacing*
                        // [discordRpc], so for as long as it takes the field is
                        // null and the feature is off: a teardown that hung —
                        // which one waiting on an unreachable socket did — read
                        // to the user as a switch that had stopped working
                        // altogether until the app was restarted.
                        withContext(Dispatchers.IO + NonCancellable) {
                            withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                                if (wasUp) runCatching { rpc.close() }
                            }
                            runCatching { rpc.closeRPC() }
                        }
                    }
                    discordRpc = null

                    if (token == null) return@collectLatest
                    discordRpc = DiscordRPC(this@PlaybackService, token)
                    // A presence that only appeared at the next track change
                    // would make turning the switch on look like it had done
                    // nothing for the length of a song.
                    player?.takeIf { it.isPlaying }?.let(::pushDiscordPresence)
                }
        }

        // The card's own contents, plus the playback rate — which is not
        // cosmetic here: the timestamps are wall-clock instants with the rate
        // divided out, so a change to it invalidates a presence already up.
        scope.launch {
            // Explicit <Any, _> for the same reason as [observeScrobbling]:
            // mixed element types, and the reified vararg combine() otherwise
            // infers an intersection type. Compared as a list rather than a
            // joined string so two different settings can't stringify alike.
            combine<Any, List<Any>>(
                AppSettings.discordUseDetails,
                AppSettings.discordStatus,
                AppSettings.discordActivityType,
                AppSettings.discordActivityName,
                AppSettings.discordButton1Text,
                AppSettings.discordButton1Visible,
                AppSettings.discordButton2Text,
                AppSettings.discordButton2Visible,
                AppSettings.playbackSpeed,
            ) { it.toList() }
                .distinctUntilChanged()
                // Dropped so the collector's first emission — which arrives at
                // startup, before anything is playing — isn't treated as a
                // change the user made.
                .drop(1)
                .collect {
                    player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
                }
        }

        // A network coming back, which is the other half of surviving a spell in
        // the background: the gateway heals itself, but its retry backoff climbs
        // to a minute, and a listener who walked back into Wi-Fi shouldn't watch
        // a blank profile for that long. Nudging it here collapses the wait.
        //
        // [AppSettings.meteredConnection] is null only while there is no active
        // network, so null -> non-null is exactly "we are online again". A
        // metered/unmetered flip is worth acting on too: the socket does not
        // survive a transport handover, and the old one may not have noticed yet.
        scope.launch {
            AppSettings.meteredConnection
                .drop(1)
                .collect { metered ->
                    if (metered == null) return@collect

                    if (metered) {
                        // A transport handover to cellular changes the priority:
                        // protect the stream already audible, cancel any live
                        // source swap/search that could tear it down, and let the
                        // lightweight queue prefix warm-up handle the successor.
                        upgradeJob?.cancel()
                        upgradeJob = null
                        player?.currentMediaItem?.mediaId?.let { mediaId ->
                            QualityUpgrade.settlePreparedQueueTrack(mediaId)
                            hideQualitySearchHint(mediaId)
                        }
                        nextQualityPrimeJob?.cancel()
                        nextQualityPrimeJob = null
                        nextQualityPrimeSucceeded = false
                    }

                    // Connectivity returning is also an Automix event. Offline
                    // analysis-head requests deliberately never claim a session
                    // attempt, so one planning pass is enough to resume fetching
                    // unknown queue entries and to reconsider their positions.
                    player?.let { sessionPlayer ->
                        offlineAutoplayStalledFor?.let { stalledId ->
                            if (sessionPlayer.currentMediaItem?.mediaId == stalledId) {
                                offlineAutoplayStalledFor = null
                                sessionPlayer.prepare()
                                sessionPlayer.play()
                            } else {
                                offlineAutoplayStalledFor = null
                            }
                        }
                        val playbackActive = sessionPlayer.isPlaying || sessionPlayer.playWhenReady ||
                                AutomixQueueLaunch.isPendingFor(sessionPlayer)
                        if (playbackActive) {
                            // Reconnect quality preparation for every queue mode. A
                            // sequential album/playlist still deserves its next track
                            // resolved while the current one is playing; Automix only
                            // decides whether musical re-planning also runs.
                            prefetchAround(sessionPlayer)
                        }
                    }

                    val rpc = discordRpc ?: return@collect
                    withContext(Dispatchers.IO) { runCatching { rpc.wakeUp() } }
                    // Re-pushed rather than left to the gateway's own replay,
                    // because a handover can strand the socket in a state where
                    // it believes it is still connected: the push is what makes
                    // it prove otherwise.
                    if (discordPresenceUp) {
                        player?.takeIf { p -> p.isPlaying }?.let(::pushDiscordPresence)
                    }
                }
        }
    }

    /**
     * Publishes the track [exoPlayer] is on as the user's Discord presence.
     *
     * A no-op with no connection, which is the ordinary case — most people will
     * never connect an account, and this is called from the middle of every
     * track change.
     */
    private fun pushDiscordPresence(exoPlayer: ExoPlayer) {
        val rpc = discordRpc ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        // Read on the main thread, before the push is handed to IO: by the time
        // a coroutine gets to run, the queue may have moved on, and ExoPlayer's
        // state is only legal to read from the thread it was built on.
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        val speed = exoPlayer.playbackParameters.speed

        discordUpdateJob?.cancel()
        discordPresenceUp = true
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            rpc.updateSong(
                song = song,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                playbackSpeed = speed,
                useDetails = AppSettings.discordUseDetails.value,
                status = AppSettings.discordStatus.value,
                button1Text = AppSettings.discordButton1Text.value,
                button1Visible = AppSettings.discordButton1Visible.value,
                button2Text = AppSettings.discordButton2Text.value,
                button2Visible = AppSettings.discordButton2Visible.value,
                activityType = AppSettings.discordActivityType.value,
                activityName = AppSettings.discordActivityName.value,
            ).onFailure {
                TrackLog.d("Orb", "Discord presence failed: ${it.message}", about = song.videoId)
            }
        }
    }

    /**
     * Takes the presence down but leaves the socket up, so resuming doesn't pay
     * for a reconnect. Discord clears the card on an activity-less presence.
     */
    private fun clearDiscordPresence() {
        val rpc = discordRpc ?: return
        if (!discordPresenceUp) return
        discordPresenceUp = false
        discordUpdateJob?.cancel()
        discordUpdateJob = scope.launch(Dispatchers.IO) {
            runCatching { rpc.close() }
        }
    }

    /**
     * Submits a finished ListenBrainz listen, but only if the service is
     * actually scrobbling — the settings are read at call time so the helper
     * stays a no-op whenever ListenBrainz is switched off.
     */
    private fun submitListenBrainzFinished(song: Song, startMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        val endMs = System.currentTimeMillis()
        scope.launch {
            ListenBrainzManager.submitFinished(lbToken, song, startMs, endMs, durationMs)
        }
    }

    /** Sends a ListenBrainz "now playing" update for the current track. */
    private fun submitListenBrainzPlayingNow(song: Song, positionMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        scope.launch {
            ListenBrainzManager.submitPlayingNow(lbToken, song, positionMs, durationMs)
        }
    }

    /** A small, fast, on-device browse tree for Samsung, Android Auto and legacy browsers.
     * No external account/session is required. It advertises the current/restored queue only,
     * avoiding network requests on system controller callbacks.
     */
    private val mediaLibraryCallback = object : MediaLibrarySession.Callback {
        private fun rootItem(): MediaItem = MediaItem.Builder()
            .setMediaId(LIBRARY_ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

        private fun currentLibraryItems(): List<MediaItem> {
            val live = player
            if (live != null && live.mediaItemCount > 0) {
                return (0 until live.mediaItemCount).map(live::getMediaItemAt)
            }
            val saved = LastPlayed.load() ?: return emptyList()
            return saved.songs.mapIndexed { index, song ->
                song.toMediaItem(albumSequential = saved.albumSequentialFlags.getOrNull(index) == true)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            val tracks = if (parentId == LIBRARY_ROOT_ID) currentLibraryItems() else emptyList()
            val offset = page.toLong() * pageSize.toLong()
            val chunk = if (page < 0 || pageSize <= 0 || offset >= tracks.size) emptyList()
                else tracks.drop(offset.toInt()).take(pageSize)
            return Futures.immediateFuture(LibraryResult.ofItemList(chunk, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == LIBRARY_ROOT_ID) rootItem()
                else currentLibraryItems().firstOrNull { it.mediaId == mediaId }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        /** Android System UI can request this after a process restart or a reboot. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = LastPlayed.load()
            val items = saved?.songs?.mapIndexed { index, song ->
                song.toMediaItem(albumSequential = saved.albumSequentialFlags.getOrNull(index) == true)
            } ?: emptyList()
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    items,
                    if (items.isEmpty()) 0 else saved!!.index.coerceIn(0, items.lastIndex),
                    saved?.positionMs?.coerceAtLeast(0L) ?: 0L,
                ),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Screen-off and ordinary background playback are ALWAYS supported, free or
     * Premium: neither invokes this callback. Only removing the MAIN app task
     * from Recents is a separate user action, configurable for Premium accounts.
     * A transient Orb Lock task removal is NEVER treated as app closure.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val removedComponent = rootIntent?.component?.className
        val recentLockscreenExit = OrbLockscreenActivity.hasRecentlyFinished()
        // IMPORTANT: this check MUST precede super.onTaskRemoved(). Media3's
        // default implementation may pause all players and stop the service
        // when it does not yet consider playback ongoing in the foreground.
        // The lockscreen is a separate single-activity task, not the Orb app.
        // Some OEMs send a null rootIntent for that transient task removal.
        if (OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(
                removedComponent = removedComponent,
                lockscreenComponent = OrbLockscreenActivity::class.java.name,
                recentlyFinished = recentLockscreenExit,
            )
        ) {
            Log.d(
                "OrbLockscreen",
                "Ignoring transient task removal: component=$removedComponent, " +
                    "recentExit=$recentLockscreenExit, playing=${player?.isPlaying}",
            )
            // A lockscreen exit is not a dismissal of the music session, even
            // while paused or buffering. Media3 manages its own paused-session
            // lifetime; calling super here can stop the notification/session.
            return
        }
        // On One UI and some other OEMs the callback can be delivered for a
        // temporary secondary task, or with rootIntent.component missing.
        // Stopping the entire service in that case kills the active media session
        // even though Orb's actual main task was never swiped away. Only a
        // positively identified MainActivity task removal is a user-requested
        // exit; simply switching apps, locking the screen or closing Orb Lock
        // must not tear down background playback.
        if (!OrbLockscreenTaskRemovalPolicy.isConfirmedMainTaskRemoval(
                removedComponent = removedComponent,
                mainComponent = MainActivity::class.java.name,
            )
        ) {
            Log.w(
                "OrbPlaybackLifecycle",
                "Ignoring unconfirmed task removal: component=$removedComponent, " +
                    "playing=${player?.isPlaying}, requested=${player?.playWhenReady}, " +
                    "spareRequested=${spare?.playWhenReady}",
            )
            return
        }

        val playbackRequested = player?.playWhenReady == true || spare?.playWhenReady == true
        if (OrbLockscreenTaskRemovalPolicy.shouldPreserveMainTaskPlayback(
                premiumEntitled = AppSettings.premiumEntitled.value,
                optedIn = AppSettings.canContinuePlaybackAfterRecentRemoval(),
                playbackOngoing = isPlaybackOngoing(),
                playbackRequested = playbackRequested,
            )
        ) {
            Log.d(
                "OrbLockscreen",
                "Premium opted into playback after removal from Recents: " +
                    "component=$removedComponent, playing=${player?.isPlaying}, requested=$playbackRequested",
            )
            return
        }

        // The default for everyone (including Premium with the switch OFF) is
        // to stop only when Orb is explicitly removed from the recent apps list.
        // Stop both decks so Automix cannot leave an outgoing track playing.
        Log.d("OrbLockscreen", "Main task removed; stopping both players: component=$removedComponent")
        // Preserve the position before stop() changes the player's timeline/state.
        saveQueue()
        preserveLastQueueSnapshotOnDestroy = true
        eachPlayer {
            it.pause()
            it.stop()
        }
        transitionStemDeck?.reset()
        stopSelf()
    }


    override fun onDestroy() {
        Log.d("OrbLockscreen", "PlaybackService onDestroy: playing=${player?.isPlaying}, playWhenReady=${player?.playWhenReady}")
        if (orbLockscreenReceiverRegistered) {
            runCatching { unregisterReceiver(orbLockscreenReceiver) }
            orbLockscreenReceiverRegistered = false
        }

        watchArtworkJob?.cancel()
        watchArtworkPrefetchJob?.cancel()
        watchArtworkJob = null
        watchArtworkPrefetchJob = null
        castBridge?.close()
        castBridge = null
        com.music.orb.data.rooms.RoomSession.detach()
        // The explicit Recents removal already saved the queue before stopping decks.
        if (!preserveLastQueueSnapshotOnDestroy) saveQueue()
        AudioCache.cancel()
        AutomixQueueLaunch.cancel()
        transitionStemDeck?.reset()
        trackAnalyzer.setOnAnalysisUpdated(null)
        trackAnalyzer.release()
        // Also the last chance to close out the track that was playing — a
        // swipe-away or stop never fires STATE_ENDED, so the session would
        // otherwise end with an un-scrobbled song. This must not ride on the
        // service scope: it is cancelled a few lines down, and the request
        // should still reach ListenBrainz.
        val lastSong = listenBrainzSong
        if (lastSong != null) {
            val lbEnabled =
                AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value
            val lbToken = AppSettings.listenBrainzToken.value
            if (lbEnabled && lbToken.isNotBlank()) {
                val lastStart = listenBrainzStartMs
                val lastDuration = player?.duration?.takeIf { it > 0 }
                CoroutineScope(Dispatchers.IO).launch {
                    ListenBrainzManager.submitFinished(
                        lbToken, lastSong, lastStart, System.currentTimeMillis(), lastDuration,
                    )
                }
            }
        }
        scrobbleManager?.destroy()
        scrobbleManager = null
        // Discord, on the same terms as the ListenBrainz submit above: the
        // service scope is cancelled a few lines down, and a presence left up
        // would advertise a track that stopped when the process did — until
        // Discord noticed the socket had gone, which can take minutes.
        discordRpc?.let { rpc ->
            discordRpc = null
            val wasUp = discordPresenceUp
            discordPresenceUp = false
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                    if (wasUp) runCatching { rpc.close() }
                }
                runCatching { rpc.closeRPC() }
            }
        }
        socialPlaybackReporter.shutdown()
        hideQualitySearchHint()
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(outputDeviceCallback) }
        outputReconfigureJob?.cancel()
        outputReconfigureJob = null
        touchTransferStateJob?.cancel()
        touchTransferStateJob = null
        touchTransferEventJob?.cancel()
        touchTransferEventJob = null
        OrbTouchTransfer.stop(OrbTouchTransfer.Client.PLAYBACK_SERVICE)
        scope.cancel()
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playbackListener)
        player?.removeAnalyticsListener(formatListener)
        player?.release()
        player = null
        // Released too, and not conditionally: mid-crossfade it is holding a
        // decoder and an open audio track of its own, and the service going away
        // is not a reason to leave either behind.
        spare?.release()
        spare = null
        transitionStemDeck?.release()
        transitionStemDeck = null
        super.onDestroy()
    }

    /**
     * What the MediaSession, and so every control surface, actually talks to.
     *
     * Two behaviours are grafted onto the player here rather than left to
     * ExoPlayer's defaults:
     *
     * **Back restarts the track.** ExoPlayer already implements
     * restart-then-skip in [Player.seekToPrevious], gated on
     * `maxSeekToPreviousPosition`. External surfaces don't use it:
     * [DefaultMediaNotificationProvider] binds its previous button to
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, which skips unconditionally. So
     * that command is redirected rather than left to behave differently
     * depending on which back button was pressed.
     *
     * **A skip cancels the crossfade.** Blending is for a track running out,
     * not for one being changed: told to move on, the listener wants the song
     * they were on to stop, not to keep playing over the one they asked for.
     * So every skip tells [CrossfadeController] to drop whatever is in flight
     * and then moves the queue plainly.
     *
     * Command availability is deliberately untouched — mutating it through a
     * [ForwardingPlayer] means intercepting listener callbacks too. The one
     * consequence is the first track of a queue, where ExoPlayer withholds
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` for want of a previous item: back
     * stays inert on those surfaces, exactly as it already was. In the app it
     * restarts, since that path asks for `COMMAND_SEEK_TO_PREVIOUS`.
     */
    private class SessionPlayer(
        player: Player,
        private val crossfade: CrossfadeController,
        private val onPlayRequested: () -> Unit,
    ) : ForwardingPlayer(player) {

        private fun transportLocked(): Boolean = com.music.orb.data.rooms.RoomRepository.isGuest()

        override fun play() {
            if (transportLocked()) {
                com.music.orb.data.rooms.RoomSession.setGuestPaused(false)
                return
            }
            wrappedPlayer.play()
            onPlayRequested()
        }

        override fun pause() {
            if (transportLocked()) com.music.orb.data.rooms.RoomSession.setGuestPaused(true)
            wrappedPlayer.pause()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (transportLocked()) {
                com.music.orb.data.rooms.RoomSession.setGuestPaused(!playWhenReady)
                if(!playWhenReady) wrappedPlayer.pause()
            } else wrappedPlayer.playWhenReady = playWhenReady
        }

        override fun seekTo(positionMs: Long) {
            if (!transportLocked()) wrappedPlayer.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (!transportLocked()) wrappedPlayer.seekTo(mediaItemIndex, positionMs)
        }

        override fun seekToDefaultPosition() {
            if (!transportLocked()) wrappedPlayer.seekToDefaultPosition()
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (!transportLocked()) wrappedPlayer.seekToDefaultPosition(mediaItemIndex)
        }

        override fun seekToPrevious() {
            if (transportLocked()) return
            crossfade.onSkipRequested()
            wrappedPlayer.seekToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            if (transportLocked()) return
            crossfade.onSkipRequested()
            wrappedPlayer.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            if (transportLocked()) return
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNextMediaItem()
        }

        override fun seekToNext() {
            if (transportLocked()) return
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNext()
        }

        override fun clearMediaItems() {
            if (transportLocked()) return
            // Clearing the timeline is an explicit end-of-session action. An
            // Automix standby/outgoing deck is outside MediaController's visible
            // timeline, so retire it here before the authoritative deck is cleared.
            crossfade.onQueueCleared()
            wrappedPlayer.clearMediaItems()
        }
    }

    private companion object {
        /** Brief disk-cache settle before ordering a freshly appended AutoPlay batch. */
        const val AUTOPLAY_ORDER_STORE_SETTLE_MS = 650L

        /** Bound cache-only server lookups; no future-track audio analysis is started here. */
        const val AUTOPLAY_REMOTE_ANALYSIS_LOOKAHEAD = 16
        const val AUTOPLAY_PREVIEW_ANALYSIS_LOOKAHEAD = 8
        const val AUTOPLAY_PREVIEW_BUDGET_MS = 7_000L
        const val AUTOPLAY_PREVIEW_POLL_MS = 250L

        private const val REMOTE_AUTOMIX_PROBE_RETRY_MS = 120_000L
        /**
         * Shared by both players. Identical on purpose: they take turns being
         * the session, and a difference here would be an audible change of
         * routing at the handoff.
         */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        const val CHANNEL_ID = "bitchord_playback"
        const val SESSION_ID = "OrbPlayback"

        /** How often played-seconds are sampled off the player. */
        const val PROGRESS_SAMPLE_MS = 5_000L
        /** Tiny hand-off poll used only to ensure A's analyzer is fully idle before B starts. */
        const val AUTOMIX_ANALYSIS_ORDER_POLL_MS = 50L

        /**
         * Bounded live-quality probes released only after the first-audio grace.
         * They cover the tail of the opening six-second window instead of competing
         * with the critical first buffer. Crossfaded B uses the same probes after handoff.
         */
        val EARLY_UPGRADE_PROBE_MS = longArrayOf(450L, 1_150L, 1_900L)
        /** A keeps exclusive upgrade priority for roughly its first six audible seconds. */
        const val CURRENT_TRACK_UPGRADE_PRIORITY_MS = 6_000L


        /** Far-future analysis is progressive, never an unbounded playlist-wide burst. */
        const val SESSION_BACKGROUND_ANALYSIS_SLOTS = 4
        const val SESSION_BACKGROUND_ANALYSIS_SLOT_MS = 45_000L

        /**
         * A freshly selected shuffled queue may hold playback briefly so Automix
         * can understand the current song plus several successors before the
         * first audible beat. It is deliberately bounded: smarter ordering may
         * delay playback, a failed analyzer may not prevent it.
         */

        /** Opening-track best-quality lookup before a new set is allowed to sound. */

        /** B starts quality discovery as soon as A is audible; this bounds only the background search itself. */
        const val NEXT_TRACK_QUALITY_SEARCH_MS = 30_000L

        /** In A's final approach, stop changing B's route and spend bandwidth on its opening buffer. */
        const val NEXT_TRACK_BUFFER_PRIORITY_LEAD_MS = 25_000L
        /**
         * Mobile links fluctuate more than Wi-Fi. Start B's continuity-only
         * runway earlier so the cache can absorb those throughput dips before
         * the two-deck portion of Automix begins.
         */
        const val METERED_NEXT_TRACK_BUFFER_PRIORITY_LEAD_MS = 40_000L
        /**
         * Long Instrumental Overlay plans may open B up to ~90 seconds before
         * A's file end. Start the cellular runway before that window so the
         * overlap is served from cache instead of asking one radio link to feed
         * two live decoders at once.
         */
        const val METERED_AUTOMIX_BUFFER_PRIORITY_LEAD_MS = 110_000L
        /** Do not steal radio time from A unless it already owns a real safety cushion. */
        const val METERED_TRANSITION_WARM_MIN_HEADROOM_MS = 20_000L

        /** Maximum lifetime of the transient “looking for better quality” sentence. */
        const val QUALITY_SEARCH_HINT_MAX_MS = 20_000L

        /** Official YouTube/Opus is warmed in parallel; it must never wait on the Lossless budget. */
        const val OPUS_FALLBACK_PRIME_MS = 30_000L

        /** Queue decisions live long enough for ordinary tracks to finish before their successor opens. */
        const val QUEUED_QUALITY_PRIME_TTL_MS = 10 * 60_000L

        /** Reopening an already-matched Lossless source should be quick or yield to the warmed fallback. */
        const val PRIMED_LOSSLESS_REOPEN_MS = 2_000L
        const val QUEUED_STARTUP_WARM_BYTES = 768L * 1024L
        const val QUEUED_FALLBACK_WARM_BYTES = 384L * 1024L
        const val QUEUED_TRANSITION_WARM_BYTES = 3L * 1024L * 1024L
        /**
         * Dedicated cellular Automix runway. This is deliberately fallback
         * quality: reliability during the overlap wins over spending the same
         * radio budget on a late quality race.
         */
        // Cellular queue warms must stay small. The old 4 MiB bounded request was passed
        // straight through by ChunkedDataSource and many googlevideo sessions answered it 403,
        // after which the transition heartbeat retried the same dead range every ~5 seconds.
        const val METERED_TRANSITION_WARM_BYTES = 768L * 1024L
        const val METERED_OPUS_STARTUP_WARM_BYTES = 384L * 1024L
        const val METERED_PREFERRED_STARTUP_WARM_BYTES = 768L * 1024L
        const val QUEUED_DASH_MANIFEST_WARM_BYTES = 128L * 1024L

        /** Start preparing AutoPlay while this many user tracks remain before it. */

        /** New fit-request tracks have no order worth preserving; move on modest evidence. */
        const val SESSION_FIT_MIN_IMPROVEMENT = 0.03

        /**
         * How long a Discord teardown may spend clearing the presence before the
         * socket is closed out from under it.
         *
         * Closing the gateway ends the session, which clears the card on
         * Discord's side anyway — the explicit clear only makes it immediate. So
         * this is a bound on politeness, not on correctness, and it is short
         * because whatever is tearing down is waiting on it.
         */
        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L

        /**
         * Size of each range the player fetches. The same figure read-ahead
         * uses, and for the same reason — see [ChunkedDataSource].
         */
        const val STREAM_CHUNK_BYTES = 2L * 1024 * 1024

        /** Shortest gap "skip silence" is allowed to touch. */
        const val MIN_SILENCE_US = 1_000_000L

        /** Past any song, so the byte ceiling is what stops loading. */
        const val FAR_BUFFER_MS = 15 * 60 * 1000

        /**
         * Large enough to hold a typical 320 kbps mobile stream end-to-end
         * (~6m40s), so the player can bank network bursts instead of hovering
         * just ahead of the playhead when cellular throughput oscillates.
         */
        const val FAR_BUFFER_BYTES = 16 * 1024 * 1024

        /**
         * A short nudge backwards, and no more: this shares the byte ceiling
         * above with the read-ahead it would otherwise starve.
         */
        const val BACK_BUFFER_MS = 30 * 1000

        /** Small first-note cushion; 200ms repeatedly restarted on unstable mobile links. */
        const val START_PLAYBACK_MS = 500
        const val FIRST_AUDIO_SOURCE_BUDGET_MS = 1_800L
        /** First audio gets a quiet runway before even cheap queue/read-ahead work starts. */
        const val FIRST_AUDIO_FALLBACK_STABILIZE_MS = 1_200L
        /** Best-quality discovery is never allowed onto the Play -> first-note critical path. */
        const val FIRST_AUDIO_QUALITY_GRACE_MS = 4_000L
        // Do not cancel an in-progress mobile source handshake at 2.2 seconds.
        const val FIRST_AUDIO_STALL_MS = 5_000L
        const val FIRST_AUDIO_PROGRESS_EPSILON_MS = 350L
        const val FIRST_AUDIO_PROGRESS_GRACE_MS = 2_500L
        const val FIRST_AUDIO_PROGRESS_MAX_CHECKS = 3
        const val FIRST_AUDIO_RECOVERY_LIMIT = 1
        const val SONIC_RECOVERY_DELAY_MS = 80L

        /**
         * Optional quality work on cellular is allowed only when the stream
         * already has enough runway to absorb catalogue/CDN variance. This is
         * the guard that preserves Hi-Q upgrades on a strong mobile connection
         * without letting them steal bandwidth from a barely-sustaining one.
         */
        // Mobile playback still owns the radio first. A current-track upgrade waits for ~15 s;
        // B's Automix quality leg may start at ~12 s because it is now part of B preparation.
        // Ordinary non-Automix queue preflight remains more conservative at ~30 s.
        const val METERED_ARTWORK_MIN_HEADROOM_MS = 12_000L
        const val METERED_PREFETCH_MIN_HEADROOM_MS = 15_000L
        const val PERIODIC_QUEUE_SAVE_MS = 15_000L
        const val METERED_CURRENT_QUALITY_HEADROOM_MS = 15_000L
        const val METERED_NEXT_QUALITY_HEADROOM_MS = 30_000L
        const val METERED_AUTOMIX_NEXT_QUALITY_HEADROOM_MS = 12_000L
        const val METERED_AUTOMIX_QUALITY_FALLBACK_DEADLINE_MS = 45_000L

        /**
         * A rebuffer proves the connection is unstable. Resume with a real
         * cushion instead of two seconds that immediately drains into another
         * stall; first-play latency is unaffected because START_PLAYBACK_MS is
         * still only 200 ms.
         */
        const val RESUME_PLAYBACK_MS = 4_000

        /**
         * Outer cap on stream resolution. Individual client calls and probes
         * have their own timeouts, but iterating all seven plus the NewPipe
         * fallback can accumulate far beyond what a listener should wait.
         *
         * The NewPipe fallback alone — a scrape of the watch page, shaped
         * harder than anything else this app asks Google for — routinely
         * takes 45-90s on its own when every player client is bot-checked, a
         * state that has become the common case rather than the rare one. A
         * cap shorter than that doesn't bound the wait; it cancels the
         * resolve just as it was about to succeed, and the retry that
         * follows restarts the same slow walk from zero, so the listener
         * waits *longer* under a tighter cap than a looser one.
         */
        const val RESOLVE_TIMEOUT_MS = 120_000L

        /** Bounded catalogue lookup used only after a deterministic Premium-only refusal. */
        const val PREMIUM_CARRIER_REMATCH_SEARCH_MS = 4_000L
        /** Each alternate id gets a short first-note proof; never fall back into a long retry loop. */
        const val PREMIUM_CARRIER_REMATCH_RESOLVE_MS = 4_000L
        /** Lower-ranked enabled sources get one bounded chance before playback gives up. */
        const val PREMIUM_CARRIER_SOURCE_FALLBACK_MS = 6_000L
        const val LOSSLESS_TO_HIQ_RECOVERY_MS = 5_000L
        /** TrackMatcher already ranks exact-copy candidates, so a handful is enough. */
        const val PREMIUM_CARRIER_REMATCH_MAX_CANDIDATES = 4

        /**
         * Cap on offering a YouTube track to a higher-ranked source.
         *
         * Nothing like [RESOLVE_TIMEOUT_MS], because the two are not the same
         * kind of wait: that one bounds the only way to hear the track, this
         * one bounds an optional upgrade over a stream YouTube will serve
         * anyway. Generous enough for a cold module — index fetch, JS
         * download, engine init, search, then the stream URL — and short
         * enough that a dead server costs a pause rather than a stall.
         */
        const val SUBSTITUTE_TIMEOUT_MS = 20_000L

        /**
         * Once A is inside the transition approach, do not tear down its source for a late
         * quality swap. B's prepared buffer owns the radio/player budget from here on.
         */
        const val UPGRADE_MIN_REMAINING_MS = 15_000L

        /**
         * No artificial "first seconds" hold. The lookup/audition happens while
         * the current stream keeps playing, so a proven upgrade may land as soon
         * as it is ready.
         */
        const val UPGRADE_NOT_BEFORE_MS = 0L

        /** How often to recheck [CrossfadeController.isTransitioning] while an upgrade waits on one. */
        const val UPGRADE_CROSSFADE_POLL_MS = 250L

        /**
         * Longest an upgrade waits on a crossfade before giving up and
         * checking once more, authoritatively, right at the swap point. Well
         * past the longest transition either mode plans — 12s for a manual
         * crossfade, or a Automix's own beat-bounded overlap, plus its arm
         * lead — so this is a guard against something stuck, not a limit
         * expected to bind in the ordinary case.
         */
        const val UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS = 20_000L

        /**
         * A short stability window after a crossfade finishes. The incoming
         * deck has already been prepared and proved audible; tearing its source
         * down on the exact landing frame for a quality swap is what recreates
         * the "B was ready, then became an endless spinner" failure. Quality
         * discovery still runs immediately, but a proved replacement lands only
         * after B has stood alone for a few seconds.
         */
        const val UPGRADE_AFTER_CROSSFADE_MS = 3_000L

        /** Delay before the post-handoff stall guard starts observing B. */
        const val AUTOMIX_HANDOFF_WATCHDOG_ARM_MS = 450L
        /** The Automix-specific guard is only for the handoff, not the whole song. */
        const val AUTOMIX_HANDOFF_WATCHDOG_WINDOW_MS = 15_000L
        /** Only the first focus/ownership wobble may restore play intent automatically. */
        const val AUTOMIX_HANDOFF_INTENT_REPAIR_MS = 4_000L

        /** A prepared B may briefly rebuffer; beyond this it is treated as wedged. */
        const val AUTOMIX_HANDOFF_STALL_MS = 1_000L
        /** STATE_ENDED this far before metadata duration is a broken B handoff, not a real ending. */
        const val AUTOMIX_EARLY_EOS_GUARD_MS = 10_000L
        /** When runtime metadata is absent, ending inside this startup window is still suspicious. */
        const val AUTOMIX_EARLY_EOS_UNKNOWN_DURATION_MS = 20_000L

        /** READY + playWhenReady but no rendered audio is not a healthy handoff. */
        const val AUTOMIX_READY_NOT_PLAYING_MS = 900L

        /** Exclusive runway before B competes with C/quality network work. */
        // Optional work after a handoff has no deadline: if B cannot build a safe runway,
        // continuity wins and quality/C analysis stay deferred.
        const val AUTOMIX_POST_HANDOFF_SAFE_BUFFER_MS = 20_000L
        const val AUTOMIX_POST_HANDOFF_MIN_SAFE_BUFFER_MS = 6_000L
        const val AUTOMIX_POST_HANDOFF_END_GUARD_MS = 2_000L

        /** Poll cadence for the post-handoff stall guard. */
        const val AUTOMIX_HANDOFF_WATCHDOG_POLL_MS = 250L

        /** Mid-track no-progress recovery. Shorter after an explicit Play tap. */
        const val PLAYBACK_STALL_GRACE_MS = 3_000L
        const val PLAYBACK_STALL_AFTER_PLAY_TAP_MS = 1_000L
        const val PLAYBACK_STALL_GROWTH_GRACE_MS = 1_500L
        const val PLAYBACK_STALL_MAX_PROGRESS_CHECKS = 3
        const val PLAYBACK_STALL_BUFFER_GROWTH_MS = 1_000L
        const val PLAYBACK_STALL_PROGRESS_EPSILON_MS = 250L

        /**
         * How long a replacement gets to report a length before it is
         * disbelieved.
         *
         * This is silence, not patience: the swap has already cut the audio,
         * and the track sits in `STATE_BUFFERING` for the whole of it before
         * the old stream comes back. It was cut from eight seconds to two and
         * a half on the strength of "a replacement that works reports its
         * length in well under a tenth of this" — which was true of what the
         * swap landed on at the time, and is not true of a FLAC. Measured
         * here, an upgrade to a 16-bit Qobuz stream was still buffering its
         * first chunk when the window closed:
         *
         * ```
         *   upgrade reverted: replacement reports -9223372036854775807ms
         *     against 259141ms (state=2, buffered=5002ms)
         * ```
         *
         * — a working FLAC thrown away for being slower to open than a lossy
         * MP4, which is the one thing this feature exists to fetch. The
         * failure the short window was protecting against is caught by state
         * now rather than by the clock (see [watchUpgrade]), so the ceiling
         * only bounds the genuinely stuck case, and can afford to be long
         * enough for a large file over a phone connection.
         */
        const val UPGRADE_PROVE_MS = 10_000L
        const val UPGRADE_PROVE_STEP_MS = 200L

        /**
         * How long an upgrade gets to prove itself before the swap is dropped.
         *
         * Nothing like [UPGRADE_PROVE_MS], and for one reason: that window is
         * silence and this one is music. The audition runs on a player nobody
         * is listening to while the old stream plays through the whole of it,
         * so the only thing a longer ceiling costs is a decoder held open a
         * few seconds more. Generous enough for a cold hi-res FLAC over a
         * phone connection, since a stream slow to open is exactly the one
         * this feature exists to fetch and exactly the one the old
         * cut-then-wait order threw away.
         */
        const val UPGRADE_AUDITION_MS = 15_000L

        /**
         * How far past the listener an upgrade has to be buffered before it is
         * allowed to take over.
         *
         * This is the number that makes the swap inaudible. Everything inside
         * this window is on disk by the time the real player asks for it, so
         * the seam is a decoder init rather than a round trip to a CDN. It has
         * to cover the drift as well: the track keeps playing while the
         * audition buffers, so the swap lands some seconds past where the
         * audition started. Five seconds keeps a useful network cushion while
         * avoiding the old twelve-second quality-upgrade delay.
         */
        const val UPGRADE_PREBUFFER_MS = 5_000L

        /**
         * How much of the upgraded file's opening is fetched before the
         * audition starts — see [AudioCache.warmRange] for why the audition
         * cannot be relied on to leave it behind.
         *
         * A megabyte because a FLAC header is not a header: STREAMINFO is 34
         * bytes, but the seek table, the tags and an embedded cover in front of
         * the first audio frame routinely run to hundreds of kilobytes, and a
         * range that stops short of the first frame buys nothing at all.
         */
        const val UPGRADE_HEADER_BYTES = 1L * 1024 * 1024

        /**
         * Opening fetched after an upgrade so the track stays analysable. Four
         * megabytes is a little over twelve seconds of lossless — the shortest
         * window Automix's head pass accepts — and many times that for a
         * compressed rendition, which simply finishes sooner.
         */
        const val ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

        /**
         * The audition's own buffer, in time and in bytes.
         *
         * Both well past [FAR_BUFFER_MS]'s byte ceiling, and deliberately: this
         * player has to end up [UPGRADE_PREBUFFER_MS] ahead of a position that
         * keeps moving while it works. The prebuffer is intentionally short:
         * the old stream remains audible during proof, and a five-second lead is
         * enough to keep the swap off the network without making every upgrade
         * wait for a twelve-second runway.
         */
        const val AUDITION_BUFFER_MS = 40_000

        const val AUDITION_BUFFER_BYTES = 24 * 1024 * 1024

        /**
         * The pause between releasing the audition player and swapping onto
         * what it cached. Same reason as [RECOVERY_DELAY_MS] — Media3 lets go
         * of a cache entry as the source is released, not as the call returns —
         * and free here, because the old stream is still playing.
         */
        const val AUDITION_RELEASE_MS = 250L

        /**
         * How long the second look waits for the decoder to report its own
         * length before using the duration already carried by the queue item.
         * A long wait here only delayed catalogue matching; 1.5 seconds still
         * catches the normal prepared case without holding quality discovery.
         */
        const val DURATION_SETTLE_MS = 900L

        /**
         * How far the replacement's length may sit from the length already
         * known for this track. Anything past this is a different file, or a
         * broken one, and either way not what is being listened to.
         */
        const val UPGRADE_LENGTH_SLACK_MS = 3_000L

        /** How many times one track is picked up off the floor — see [recoverFrom]. */
        const val MAX_RECOVERIES = 2

        /**
         * The pause before a retry. Media3 refuses to remove a cache entry a
         * reader still holds, and the reader is let go asynchronously as the
         * failed source is released, so the discard needs a moment to land
         * before the same track is asked for again.
         */
        const val RECOVERY_DELAY_MS = 350L
        const val QUEUED_FALLBACK_RETRY_DELAY_MS = 60L
    }
}
