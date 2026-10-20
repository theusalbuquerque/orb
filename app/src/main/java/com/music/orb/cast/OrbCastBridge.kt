package com.music.orb.cast

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.music.orb.R
import com.music.orb.data.rooms.RoomRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Keeps Orb's existing queue/notification controls as a silent timeline while Cast owns audio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OrbCastBridge(private val context: Context, private val player: Player,
    private val factory: DataSource.Factory) : AutoCloseable {
    companion object {
        data class RoomCastPlayback(val index: Int, val positionMs: Long, val playing: Boolean)
        fun roomPlayback(): RoomCastPlayback? = instance?.takeIf {active && !it.switchingOut}?.let { bridge ->
            val client=bridge.remote ?: return@let null
            val status=client.mediaStatus ?: return@let null
            val custom=status.mediaInfo?.customData ?: return@let null
            if(bridge.loadPending || custom.optLong("orbGeneration",-1)!=bridge.generation) return@let null
            RoomCastPlayback(custom.optInt("orbIndex",bridge.player.currentMediaItemIndex),client.approximateStreamPosition,
                bridge.desiredPlaying && status.playerState in setOf(MediaStatus.PLAYER_STATE_PLAYING,MediaStatus.PLAYER_STATE_BUFFERING))
        }
        fun pauseGuestOutput() {instance?.let {it.desiredPlaying=false;it.remote?.pause()}}
        fun followRoom(room: com.music.orb.data.rooms.ListeningRoom) {instance?.followRoomClock(room)}
        private var instance: OrbCastBridge? = null
        val switchDeviceId: String? get() = instance?.takeIf { active }?.manager?.currentCastSession?.castDevice?.deviceId
        val currentCastDeviceName: String? get() = instance?.takeIf { active }?.manager?.currentCastSession?.castDevice?.friendlyName
        fun transferToLocalOutput() { instance?.requestLocalOutput() }
        fun pauseForSwitch(): Long? = instance?.takeIf {active}?.let {
            it.switchingOut=true
            val position=it.remote?.approximateStreamPosition
            it.pauseLocal()
            position
        }
        fun cancelSwitchOut() { instance?.switchingOut=false }
        fun releaseForSwitch() { instance?.releaseSwitch() }
        suspend fun acceptSwitch(deviceId: String, position: Long) {
            requireNotNull(instance) { "CAST_NOT_READY" }.receiveSwitch(deviceId,position)
        }
        @Volatile var active=false
            private set
        private val _connected=MutableStateFlow(false)
        val connected=_connected.asStateFlow()
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val manager=CastContext.getSharedInstance(context).sessionManager
    private val retiringServers=mutableSetOf<CastAudioServer>()
    private var switchingOut=false
    private var incomingSwitchDevice: String?=null
    private var incomingSwitchPosition=0L
    private var switchReady: CompletableDeferred<Unit>?=null
    private var remote: RemoteMediaClient?=null
    private var server: CastAudioServer?=null
    private var loading: Job?=null
    private var queueLoading: Job?=null
    private var loadPending=false
    private var loadedIndex=C.INDEX_UNSET
    private var finishedSince=0L
    private var sentQueueIds: List<String> = emptyList()
    private var syncing=false
    private var loadedId: String?=null
    private var savedVolume=player.volume
    private var lastSentSeek=0L
    private var generation=0L
    private var desiredPlaying=false
    private var resumeLocalAfterDisconnect=false
    private var resumeLocalPositionMs=0L
    private var resumeLocalPlayWhenReady=false
    private var expectedInternalPauses=0
    private var resumeOnReceiverIndex=C.INDEX_UNSET
    private var resumeRequested=false
    private val callbacks=object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { syncFromReceiver() }
    }
    private val sessions=object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) { connect(session) }
        override fun onSessionStartFailed(session: CastSession, error: Int) { failed() }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) { disconnect() }
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { connect(session) }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { failed() }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Never unexpectedly resume audible playback on the phone during reconnection.
            syncing=true;pauseLocal();syncing=false
        }
    }
    private val local=object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if(!active || syncing) return
            if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && loadedIndex==player.currentMediaItemIndex &&
                sentQueueIds==(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}) return
            // The receiver, rather than the silent local decoder, decides when a song ends.
            if((reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) && loadedId!=null) {
                val previous=loadedIndex.takeIf {it in 0 until player.mediaItemCount && player.getMediaItemAt(it).mediaId==loadedId}
                if(previous!=null) {
                    syncing=true
                    try {
                        player.seekTo(previous,remote?.approximateStreamPosition ?: 0L)
                        pauseLocal()
                    } finally {syncing=false}
                    return
                }
            }
            loadCurrent()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // ExoPlayer may deliver the callback after the syncing block has ended.
            if(!playWhenReady && expectedInternalPauses>0) {expectedInternalPauses--;return}
            if(active && !syncing) {
                desiredPlaying=playWhenReady
                if(!playWhenReady) resumeOnReceiverIndex=C.INDEX_UNSET
                if(!loadPending && loadedId==null && playWhenReady) loadCurrent(true)
                else if(!loadPending && loadedId==player.currentMediaItem?.mediaId) {
                    if(playWhenReady) remote?.play() else remote?.pause()
                }
            }
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if(active && !syncing && loadedId==player.currentMediaItem?.mediaId && reason==Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex==newPosition.mediaItemIndex) {
                lastSentSeek=android.os.SystemClock.elapsedRealtime()
                remote?.seek(MediaSeekOptions.Builder().setPosition(newPosition.positionMs).build())
            }
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // Replacing the URI/quality of the same song is not a new Cast queue.
            if(active && !syncing && reason==Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED &&
                sentQueueIds!=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}) loadCurrent()
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            if(active && !syncing) remote?.queueSetRepeatMode(if(repeatMode==Player.REPEAT_MODE_ONE) MediaStatus.REPEAT_MODE_REPEAT_SINGLE else MediaStatus.REPEAT_MODE_REPEAT_OFF,null)
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if(active && !syncing) loadCurrent()
        }
        override fun onVolumeChanged(volume: Float) {
            if(active && !syncing && volume!=0f) {syncing=true;player.volume=0f;syncing=false}
        }
    }
    init {
        instance=this
        player.addListener(local)
        manager.addSessionManagerListener(sessions,CastSession::class.java)
        manager.currentCastSession?.takeIf {it.isConnected}?.let(::connect)
        scope.launch {while(isActive) {delay(1000);syncFromReceiver()}}
    }
    private fun pauseLocal() {
        if(player.playWhenReady) expectedInternalPauses++
        player.pause()
    }
    private fun connect(session: CastSession) {
        remote?.unregisterCallback(callbacks)
        remote=session.remoteMediaClient
        remote?.registerCallback(callbacks)
        if(!active) savedVolume=player.volume
        active=true;_connected.value=true
        syncing=true;player.volume=0f;syncing=false
        if(incomingSwitchDevice!=null) {
            if(session.castDevice?.deviceId!=incomingSwitchDevice) {
                switchReady?.completeExceptionally(IllegalStateException("CAST_WRONG_DEVICE"));return
            }
            syncing=true
            try {player.seekTo(incomingSwitchPosition)} finally {syncing=false}
            loadCurrent(true)
        } else loadCurrent(player.playWhenReady)
    }
    private suspend fun receiveSwitch(deviceId: String, position: Long) {
        val selector=requireNotNull(CastContext.getSharedInstance(context).mergedSelector) {"CAST_NOT_READY"}
        incomingSwitchDevice=deviceId;incomingSwitchPosition=position
        val ready=CompletableDeferred<Unit>();switchReady=ready
        val router=androidx.mediarouter.media.MediaRouter.getInstance(context)
        val callback=object: androidx.mediarouter.media.MediaRouter.Callback() {}
        val wifi=context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock=runCatching {wifi?.createMulticastLock("orb-switch-cast")?.apply {setReferenceCounted(false);acquire()}}.getOrNull()
        router.addCallback(selector,callback,androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        try {
            withTimeout(25000) {
                val current=manager.currentCastSession
                if(current?.isConnected==true && current.castDevice?.deviceId==deviceId) connect(current)
                else {
                    var route: androidx.mediarouter.media.MediaRouter.RouteInfo?=null
                    while(route==null) {
                        route=router.routes.firstOrNull {
                            it.isEnabled && it.matchesSelector(selector) &&
                                it.extras?.let(com.google.android.gms.cast.CastDevice::getFromBundle)?.deviceId==deviceId
                        }
                        if(route==null) delay(200)
                    }
                    requireNotNull(route).select()
                }
                ready.await()
            }
        } catch(exception: Exception) {
            ++generation;loading?.cancel();queueLoading?.cancel()
            manager.endCurrentSession(false)
            disconnect()
            throw exception
        } finally {
            incomingSwitchDevice=null;switchReady=null
            router.removeCallback(callback)
            runCatching {if(lock?.isHeld==true)lock.release()}
        }
    }
    private fun releaseSwitch() {
        if(!switchingOut) return
        // The old HTTP endpoint remains alive briefly until the new sender has loaded its own URL.
        val previousServer=server;server=null
        remote?.unregisterCallback(callbacks)
        remote=null
        manager.endCurrentSession(false)
        disconnect()
        previousServer?.let {retiringServers.add(it)}
        scope.launch {delay(60000);previousServer?.close();retiringServers.remove(previousServer)}
    }
    private fun loadCurrent(autoplay: Boolean = desiredPlaying) {
        val requestGeneration=++generation
        loading?.cancel();queueLoading?.cancel();queueLoading=null
        loadPending=true
        val item=player.currentMediaItem
        if(item==null) {remote?.stop();loadedId=null;loadPending=false;return}
        val uri=item.localConfiguration?.uri ?: run {loadPending=false;return}
        val position=player.currentPosition.coerceAtLeast(0)
        // Internal pauses (buffering/loading/end guards) must not replace the listener's intent.
        desiredPlaying=autoplay
        val startIndex=player.currentMediaItemIndex
        loadedIndex=startIndex
        sentQueueIds=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
        val upcoming=mutableListOf<Pair<Int,MediaItem>>()
        var cursor=player.currentTimeline.getNextWindowIndex(startIndex,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
        while(RoomRepository.playbackRoom.value?.host != false && cursor!=C.INDEX_UNSET && upcoming.size<player.mediaItemCount) {
            upcoming.add(cursor to player.getMediaItemAt(cursor))
            cursor=player.currentTimeline.getNextWindowIndex(cursor,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
        }
        loadedId=null
        syncing=true;pauseLocal();syncing=false
        loading=scope.launch {
            try {
                val endpoint=server ?: withContext(Dispatchers.IO) {CastAudioServer(context,factory)}.also {server=it}
                val type=withContext(Dispatchers.IO) {endpoint.contentType(uri,item.localConfiguration?.mimeType ?: "audio/mp4")}
                val metadata=MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(MediaMetadata.KEY_TITLE,item.mediaMetadata.title?.toString().orEmpty())
                    putString(MediaMetadata.KEY_ARTIST,item.mediaMetadata.artist?.toString().orEmpty())
                    item.mediaMetadata.artworkUri?.takeIf {it.scheme=="https" || it.scheme=="http"}?.let {addImage(WebImage(it))}
                }
                val info=MediaInfo.Builder(endpoint.publish(uri,type)).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(type).setMetadata(metadata).setCustomData(JSONObject().put("orbMediaId",item.mediaId).put("orbIndex",startIndex).put("orbGeneration",requestGeneration))
                    .build()
                val client=remote ?: return@launch
                val queue=MediaQueueData.Builder().setItems(listOf(MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(10.0).build()))
                    .setStartIndex(0).setRepeatMode(if(player.repeatMode==Player.REPEAT_MODE_ONE) MediaStatus.REPEAT_MODE_REPEAT_SINGLE else MediaStatus.REPEAT_MODE_REPEAT_OFF).build()
                client.load(MediaLoadRequestData.Builder().setQueueData(queue).setCurrentTime(position).setAutoplay(desiredPlaying).build())
                    .setResultCallback {result ->
                        if(requestGeneration!=generation || player.currentMediaItem?.mediaId!=item.mediaId || remote!==client) return@setResultCallback
                        if(result.status.isSuccess) {
                            finishedSince=0L;
                            loadPending=false;loadedId=item.mediaId;loadedIndex=startIndex
                            switchReady?.complete(Unit)
                            appendUpcoming(client,endpoint,upcoming,requestGeneration)
                            if(desiredPlaying) client.play() else client.pause()
                            syncFromReceiver()
                        } else loadFailed("load status=${result.status.statusCode}")
                    }
            } catch(exception: CancellationException) {throw exception} catch(exception: Exception) {loadFailed("load",exception)}
        }
    }
    private fun appendUpcoming(client: RemoteMediaClient, endpoint: CastAudioServer, upcoming: List<Pair<Int,MediaItem>>, requestGeneration: Long) {
        queueLoading=scope.launch {
            for((index,item) in upcoming) {
                var inserted=false
                for(attempt in 0..2) {
                    if(requestGeneration!=generation || remote!==client) return@launch
                    try {
                        val uri=item.localConfiguration?.uri ?: error("CAST_URI")
                        val type=withContext(Dispatchers.IO) {endpoint.contentType(uri,item.localConfiguration?.mimeType ?: "audio/mp4")}
                        val metadata=MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                            putString(MediaMetadata.KEY_TITLE,item.mediaMetadata.title?.toString().orEmpty())
                            putString(MediaMetadata.KEY_ARTIST,item.mediaMetadata.artist?.toString().orEmpty())
                            item.mediaMetadata.artworkUri?.takeIf {it.scheme in setOf("http","https")}?.let {addImage(WebImage(it))}
                        }
                        val info=MediaInfo.Builder(endpoint.publish(uri,type)).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                            .setContentType(type).setMetadata(metadata).setCustomData(JSONObject().put("orbMediaId",item.mediaId).put("orbIndex",index).put("orbGeneration",requestGeneration)).build()
                        if(requestGeneration!=generation || remote!==client) return@launch
                        val result=CompletableDeferred<Boolean>()
                        client.queueInsertItems(arrayOf(MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(10.0).build()),MediaQueueItem.INVALID_ITEM_ID,null)
                            .setResultCallback {result.complete(it.status.isSuccess)}
                        if(withTimeoutOrNull(20000) {result.await()} == true) {inserted=true;break}
                    } catch(exception: CancellationException) {throw exception} catch(exception: Exception) {android.util.Log.w("OrbCast","Queue preparation index=$index attempt=$attempt",exception)}
                    delay(2000)
                }
                if(!inserted) {android.util.Log.w("OrbCast","Queue append failed index=$index; retaining Cast session");return@launch}
            }
        }
    }
    private fun loadFailed(reason: String, exception: Exception? = null) {
        loadPending=false
        switchReady?.completeExceptionally(exception ?: IllegalStateException(reason))
        android.util.Log.w("OrbCast",reason,exception)
        // A media/load error is not a disconnected Cast session. Keep the route for retry.
        syncing=true;pauseLocal();syncing=false
        Toast.makeText(context,R.string.cast_failed,Toast.LENGTH_LONG).show()
    }
    private fun followRoomClock(room: com.music.orb.data.rooms.ListeningRoom) {
        if(!active || switchingOut || room.host || loadPending) return
        if(com.music.orb.data.rooms.RoomSession.guestPaused.value) {
            desiredPlaying=false
            remote?.takeIf {it.isPlaying}?.pause()
            return
        }
        val client=remote ?: return
        val status=client.mediaStatus ?: return
        val index=status.mediaInfo?.customData?.optInt("orbIndex",C.INDEX_UNSET) ?: return
        if(index!=room.index || loadedIndex!=room.index) {
            syncing=true
            try {pauseLocal();player.seekTo(room.index,room.expectedPosition())} finally {syncing=false}
            loadCurrent(room.playing)
            return
        }
        desiredPlaying=room.playing
        if(abs(client.approximateStreamPosition-room.expectedPosition())>1000L && room.playing)
            client.seek(MediaSeekOptions.Builder().setPosition(room.expectedPosition()).build())
        if(room.playing && status.playerState==MediaStatus.PLAYER_STATE_PAUSED) client.play()
        else if(!room.playing && status.playerState==MediaStatus.PLAYER_STATE_PLAYING) client.pause()
    }
    private fun syncFromReceiver() {
        val client=remote ?: return
        if(switchingOut) return
        RoomRepository.playbackRoom.value?.takeIf { !it.host }?.let {followRoomClock(it)}
        if(!active || loadPending) return
        val status=client.mediaStatus ?: return
        // Ignore a delayed status belonging to the previous load.
        val receiverId=status.mediaInfo?.customData?.optString("orbMediaId")
        val custom=status.mediaInfo?.customData ?: return
        if(custom.optLong("orbGeneration",-1L)!=generation) return
        val receiverIndex=custom.optInt("orbIndex",C.INDEX_UNSET)
        if(receiverIndex !in 0 until player.mediaItemCount || player.getMediaItemAt(receiverIndex).mediaId!=receiverId) return
        if(receiverIndex!=loadedIndex) {
            resumeRequested=false
            resumeOnReceiverIndex=if(desiredPlaying) receiverIndex else C.INDEX_UNSET
            loadedIndex=receiverIndex;loadedId=receiverId
            syncing=true
            try {pauseLocal();player.seekTo(receiverIndex,client.approximateStreamPosition)} finally {syncing=false}
        }
        if(loadedId!=player.currentMediaItem?.mediaId) return
        if(status.playerState==MediaStatus.PLAYER_STATE_IDLE && status.idleReason==MediaStatus.IDLE_REASON_FINISHED) {
            if(RoomRepository.isGuest()) return // Only the host advances the shared room timeline.
            if(client.isLoadingNextItem) {finishedSince=0L;return}
            val now=android.os.SystemClock.elapsedRealtime()
            if(finishedSince==0L) {finishedSince=now;return}
            if(now-finishedSince<2000L) return
            finishedSince=0L
            val autoplay=desiredPlaying
            loadedId=null
            val nextIndex=if(player.repeatMode==Player.REPEAT_MODE_ONE) player.currentMediaItemIndex
                else player.nextMediaItemIndex
            syncing=true
            try {
                pauseLocal()
                if(nextIndex!=C.INDEX_UNSET) player.seekTo(nextIndex,0L)
            } finally {syncing=false}
            // Load explicitly: repeat-one need not emit a media-item transition, and
            // the silent player may already be paused after reaching its own end.
            if(nextIndex!=C.INDEX_UNSET) loadCurrent(autoplay) else desiredPlaying=false
            return
        }
        finishedSince=0L
        syncing=true
        try {
            player.volume=0f
            if(android.os.SystemClock.elapsedRealtime()-lastSentSeek>1500 && abs(player.currentPosition-client.approximateStreamPosition)>1500)
                player.seekTo(client.approximateStreamPosition)
            when(status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> {
                    resumeOnReceiverIndex=C.INDEX_UNSET
                    if(RoomRepository.isGuest() && com.music.orb.data.rooms.RoomSession.guestPaused.value) {
                        desiredPlaying=false;pauseLocal()
                    } else {desiredPlaying=true;player.play()}
                }
                MediaStatus.PLAYER_STATE_PAUSED -> {
                    if(RoomRepository.isGuest()) {pauseLocal();return}
                    if(desiredPlaying && resumeOnReceiverIndex==receiverIndex) {
                        if(!resumeRequested) {resumeRequested=true;client.play()}
                    } else if(!client.isLoadingNextItem) desiredPlaying=false
                    pauseLocal()
                }
                MediaStatus.PLAYER_STATE_BUFFERING -> pauseLocal()
            }
        } finally {syncing=false}
    }
    private fun failed() {
        Toast.makeText(context,R.string.cast_failed,Toast.LENGTH_LONG).show()
        manager.endCurrentSession(true)
        disconnect()
    }
    private fun requestLocalOutput() {
        if (!active) return
        resumeLocalAfterDisconnect=true
        resumeLocalPositionMs=remote?.approximateStreamPosition ?: player.currentPosition
        resumeLocalPlayWhenReady=desiredPlaying
        manager.endCurrentSession(true)
        // Some Cast implementations deliver the session-ended callback asynchronously.
        // If the session has already ended, complete the handoff immediately.
        if (manager.currentCastSession?.isConnected != true && active) disconnect()
    }
    private fun disconnect() {
        if (!active && !resumeLocalAfterDisconnect) return
        ++generation
        loading?.cancel();loading=null;queueLoading?.cancel();queueLoading=null;loadPending=false;loadedIndex=C.INDEX_UNSET;finishedSince=0L
        remote?.unregisterCallback(callbacks);remote=null
        server?.close();server=null
        desiredPlaying=false
        switchingOut=false
        sentQueueIds=emptyList()
        loadedId=null;active=false;_connected.value=false
        val resumeLocal=resumeLocalAfterDisconnect
        val resumePosition=resumeLocalPositionMs
        val resumePlaying=resumeLocalPlayWhenReady
        resumeLocalAfterDisconnect=false
        resumeLocalPositionMs=0L
        resumeLocalPlayWhenReady=false
        syncing=true
        try {
            pauseLocal()
            player.volume=savedVolume
            if (resumeLocal) {
                player.seekTo(resumePosition.coerceAtLeast(0L))
                if (resumePlaying) player.play() else player.pause()
            }
        } finally {syncing=false}
    }
    override fun close() {
        manager.endCurrentSession(true)
        disconnect()
        manager.removeSessionManagerListener(sessions,CastSession::class.java)
        player.removeListener(local)
        retiringServers.forEach {it.close()};retiringServers.clear()
        if(instance===this)instance=null
        scope.cancel()
    }
}
