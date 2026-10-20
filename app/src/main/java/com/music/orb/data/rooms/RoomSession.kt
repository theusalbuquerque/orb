package com.music.orb.data.rooms

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.settings.AppSettings
import com.music.orb.playback.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Owned by PlaybackService, so room synchronization continues with the screen locked. */
internal object RoomSession {
    var receiverPlaybackHeld = false
    val guestPaused = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var resumeGuest = false
    fun setGuestPaused(paused: Boolean) {
        guestPaused.value=paused
        resumeGuest=!paused
        if(paused) com.music.orb.cast.OrbCastBridge.pauseGuestOutput()
    }
    private var job: Job? = null
    @Volatile private var privateHandoffVersion = 0L
    fun onPrivatePlayback() {
        privateHandoffVersion++
        readyRoom.value = null
    }
    private val readyRoom = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    suspend fun awaitReady(id: String) {
        kotlinx.coroutines.withTimeout(8000) { readyRoom.first { it == id } }
    }
    fun attach(context: Context, player: () -> Player?, onStart: () -> Unit, isMixing: () -> Boolean = { false }) {
        detach()
        RoomRepository.init(context)
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { RoomRepository.restore() } catch (_: Exception) { }
            var previousRoom: String? = null
            var observedHandoffVersion = privateHandoffVersion
            var lastQueueVersion = -1L
            var sessionUser: String? = null
            var failures = 0
            var lastHeartbeatMs = 0L
            var lastCycleStartedMs = 0L
            var lastGuestSeekMs = 0L
            var observedHostIndex = -1
            var previousHostIndex = -1
            var observedHostHandoffMs = 0L
            var lastPublishedMix = ""
            while (isActive) {
                // Throttle *all* iterations, including early continue paths. On mobile
                // a slow RPC must never trigger an immediate burst of new RPCs.
                val interval = RoomSyncPolicy.cycleInterval(AppSettings.meteredConnection.value == true, failures)
                val sinceCycle = android.os.SystemClock.elapsedRealtime() - lastCycleStartedMs
                if (lastCycleStartedMs != 0L && sinceCycle < interval) delay(interval - sinceCycle)
                val now = android.os.SystemClock.elapsedRealtime()
                lastCycleStartedMs = now
                if (now-lastHeartbeatMs >= 10000L) {
                    lastHeartbeatMs=now
                    try {
                        RoomRepository.heartbeat()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        // A temporary presence failure is retried on the next heartbeat.
                    }
                }
                if (observedHandoffVersion != privateHandoffVersion) {
                    observedHandoffVersion = privateHandoffVersion
                    previousRoom = null
                    lastQueueVersion = -1L
                    readyRoom.value = null
                    lastGuestSeekMs = 0L
                    observedHostIndex = -1
                    previousHostIndex = -1
                    observedHostHandoffMs = 0L
                    lastPublishedMix = ""
                    RoomMixAuthority.clear()
                }
                val room = RoomRepository.playbackRoom.value
                val live = player()
                if (room == null || live == null) {
                    if (previousRoom != null) {
                        onStart() // Discard a remaining room Automix deck on departure.
                        if (!RoomRepository.hasPersonalPlayback) {
                            live?.pause()
                            live?.playbackParameters = PlaybackParameters.DEFAULT
                        }
                    }
                    previousRoom = null
                    lastPublishedMix = ""
                    RoomMixAuthority.clear()
                    observedHostIndex = -1
                    previousHostIndex = -1
                    observedHostHandoffMs = 0L
                    guestPaused.value=false;resumeGuest=false
                    readyRoom.value = null
                    delay(400)
                    continue
                }
                if (room.playbackElsewhere) {
                    RoomRepository.releaseToOtherDevice(room.id)
                    live.pause()
                    delay(400)
                    continue
                }
                if (previousRoom != room.id) {
                    guestPaused.value=false;resumeGuest=false
                    readyRoom.value = null
                    onStart()
                    live.pause()
                    live.clearMediaItems()
                    lastQueueVersion = -1
                    lastGuestSeekMs = 0L
                    observedHostIndex = room.index // An initial join is not a delayed handoff.
                    previousHostIndex = -1
                    observedHostHandoffMs = 0L
                    sessionUser = SocialRepository.currentUserId()
                    RoomMixAuthority.clear()
                    lastPublishedMix = ""
                    previousRoom = room.id
                }
                if (sessionUser == null || sessionUser != SocialRepository.currentUserId()) {
                    live.pause()
                    RoomRepository.clearLocal()
                    continue
                }
                if(RoomRepository.queueEditing.value) {delay(100);continue}
                try {
                    if (live.shuffleModeEnabled) live.shuffleModeEnabled = false
                    if (live.repeatMode != Player.REPEAT_MODE_OFF) live.repeatMode = Player.REPEAT_MODE_OFF
                    val ids = (0 until live.mediaItemCount).map { live.getMediaItemAt(it).mediaId }
                    val desiredIds = room.queue.map { it.videoId }
                    if (lastQueueVersion != room.queueVersion || ids != desiredIds) {
                        val oldId = live.currentMediaItem?.mediaId
                        val preservedIndex = desiredIds.indexOf(oldId)
                        val preserveHost = room.host && preservedIndex >= 0
                        val destinationIndex = if (preserveHost) preservedIndex else room.index
                        val destinationPosition = if (preserveHost) live.currentPosition else
                            RoomSyncPolicy.targetPosition(room.expectedPosition(), AppSettings.meteredConnection.value == true)
                        val shouldPlay = if (preserveHost) live.playWhenReady else room.playing
                        if (ids.isNotEmpty() && desiredIds.size >= ids.size && desiredIds.take(ids.size) == ids) {
                            live.addMediaItems(room.queue.drop(ids.size).map { it.toMediaItem() })
                        } else if (room.queue.isEmpty()) {
                            live.pause()
                            live.clearMediaItems()
                        } else {
                            // The controller's second player may still be fading out.
                            // Abort that transition before replacing the shared queue.
                            onStart()
                            live.setMediaItems(room.queue.map { it.toMediaItem() }, destinationIndex.coerceIn(0, room.queue.lastIndex), destinationPosition)
                            live.prepare()
                            lastGuestSeekMs = android.os.SystemClock.elapsedRealtime()
                        }
                        live.playWhenReady = !receiverPlaybackHeld && shouldPlay && room.queue.isNotEmpty() && (room.host || !guestPaused.value)
                        lastQueueVersion = room.queueVersion
                    }
                    readyRoom.value = room.id
                    if (receiverPlaybackHeld) {
                        live.pause()
                        delay(100)
                        continue
                    }
                    if (room.host) {
                        val cast=com.music.orb.cast.OrbCastBridge.roomPlayback()
                        if(com.music.orb.cast.OrbCastBridge.active && cast==null) {
                            // A local pause while Cast loads is not a shared-room pause.
                            delay(200);continue
                        }
                        RoomRepository.publish(room.id, cast?.index ?: live.currentMediaItemIndex.coerceAtLeast(0),
                            cast?.positionMs ?: live.currentPosition,
                            // Audio-focus interruptions can suppress playback while playWhenReady stays true.
                            // Publish that suppression as a shared pause; buffering alone remains playing.
                            cast?.playing ?: (live.playWhenReady &&
                                live.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                                live.playbackState != Player.STATE_ENDED), room.queueVersion)
                        // Keep the room clock timely: a slow Automix-plan RPC must not
                        // delay the normal host position/pause broadcast.
                        val snapshot = RoomMixAuthority.hostState(room)
                        try {
                            val serialized = RoomMixCodec.encode(snapshot).toString()
                            if (serialized != lastPublishedMix) {
                                RoomRepository.publishMix(room, snapshot)
                                lastPublishedMix = serialized
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            // Bad/unavailable mix payloads must never stop the room clock.
                            // Retry the normal host-authoritative publication next cycle.
                        }
                    } else {
                        val next = RoomRepository.refresh() ?: continue
                        // An Automix handoff can swap ExoPlayers while the network RPC
                        // is awaiting its response. Never seek/pause a stale outgoing deck.
                        if (live !== player() || RoomRepository.playbackRoom.value?.id != next.id) continue
                        if (next.playbackElsewhere) {
                            RoomRepository.releaseToOtherDevice(next.id)
                            live.pause()
                            continue
                        }
                        if (next.queueVersion != lastQueueVersion) continue
                        try {
                            RoomMixAuthority.receiveGuest(next, RoomRepository.fetchMix(next.id))
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            // Never fall back to the guest's own Automix preferences.
                            RoomMixAuthority.onGuestFetchFailure(next)
                        }
                        com.music.orb.cast.OrbCastBridge.followRoom(next)
                        if (next.queue.isNotEmpty()) {
                            val metered = AppSettings.meteredConnection.value == true
                            val observedNow = android.os.SystemClock.elapsedRealtime()
                            if (observedHostIndex != next.index) {
                                previousHostIndex = observedHostIndex
                                observedHostIndex = next.index
                                observedHostHandoffMs = observedNow
                            }
                            val hostPosition = next.expectedPosition()
                            var target = RoomSyncPolicy.targetPosition(hostPosition, metered)
                            val currentIndex = live.currentMediaItemIndex
                            if (live.duration > 0 && currentIndex == next.index) {
                                target = target.coerceAtMost((live.duration - 150L).coerceAtLeast(0L))
                            }
                            val mixing = isMixing()
                            val buffering = live.playbackState == Player.STATE_BUFFERING ||
                                live.playbackState == Player.STATE_IDLE
                            val drift = target - live.currentPosition
                            val holdingPrior = RoomSyncPolicy.holdPreviousTrack(
                                currentIndex, next.index, hostPosition, metered, next.playing) ||
                                RoomSyncPolicy.holdObservedHandoff(
                                    currentIndex, previousHostIndex, next.index,
                                    observedNow - observedHostHandoffMs, metered, next.playing)
                            val trackChanged = currentIndex != next.index
                            val shouldSeek = !mixing && !holdingPrior && (trackChanged || resumeGuest ||
                                RoomSyncPolicy.correctSameTrack(
                                    drift, metered, buffering, mixing,
                                    android.os.SystemClock.elapsedRealtime() - lastGuestSeekMs))
                            if (shouldSeek) {
                                if (trackChanged) onStart() // Never seek through an active DJ blend.
                                live.seekTo(next.index, target)
                                if (live.playerError != null) live.prepare()
                                lastGuestSeekMs = android.os.SystemClock.elapsedRealtime()
                                resumeGuest = false
                            } else if (!mixing && !holdingPrior && !buffering) {
                                resumeGuest = false
                            }
                            live.playWhenReady = next.playing && !guestPaused.value
                            // Automix owns playbackParameters (tempo and beat sync). Room
                            // sync must not overwrite the DJ pitch/tempo during a blend.
                            if (!mixing && RoomMixAuthority.guestState(next)?.enabled != true) {
                                val speed = if (!next.playing || trackChanged || abs(drift) < 250L ||
                                    abs(drift) > RoomSyncPolicy.MOBILE_SEEK_THRESHOLD_MS) 1f
                                else if (drift > 0) 1.015f else 0.985f
                                if (abs(live.playbackParameters.speed - speed) > 0.005f) {
                                    live.playbackParameters = PlaybackParameters(speed)
                                }
                            }
                        }
                        if (live.playerError != null) RoomRepository.issue.value = "ROOM_TRACK_UNAVAILABLE"
                        else if (!next.hostOnline) RoomRepository.issue.value = "ROOM_HOST_OFFLINE"
                    }
                    failures = 0
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    val reason = (exception as? RoomException)?.reason ?: "ROOM_NETWORK"
                    failures++
                    if (reason == "ROOM_HOST_ONLY") {
                        runCatching { RoomRepository.refresh() }
                        continue
                    }
                    if (reason in setOf("ROOM_CLOSED", "ROOM_NOT_MEMBER", "ROOM_SIGN_IN")) {
                        live.pause()
                        RoomRepository.clearLocal()
                        RoomRepository.issue.value = reason
                    } else {
                        RoomRepository.issue.value = reason
                        // Do not let disconnected guests carry on with an unrelated timeline.
                        if (!room.host && failures >= 6) live.pause()
                    }
                }
                // The next cycle is paced at the loop entrance (including early continues).
            }
        }
    }
    fun detach() {
        job?.cancel()
        job = null
    }
}
