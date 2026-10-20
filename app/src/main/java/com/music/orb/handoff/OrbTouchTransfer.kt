package com.music.orb.handoff

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.music.orb.data.model.Song
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.social.OrbSupabase
import com.music.orb.data.social.SocialRepository
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.Serializable

/**
 * Premium-only, same-account playback handoff triggered by physically touching two devices.
 *
 * NFC is not required. BLE is only the local proximity/touch side-channel. Track/queue data
 * never travels in the advertisement: the actual handoff payload uses an RLS-protected Supabase
 * row whose user_id must match auth.uid().
 */
object OrbTouchTransfer {
    private const val PREFS = "orb_touch_transfer"
    private const val KEY_DEVICE_ID = "device_id"
    private const val MANUFACTURER_ID = 0xFFFF
    private const val PROTOCOL_VERSION: Byte = 2
    private const val FLAG_PLAYING = 1
    private const val FLAG_SENDER_READY = 2
        private const val PAYLOAD_BYTES = 18
    private const val ABSOLUTE_NEAR_RSSI = -40.0
    private const val IMPACT_ASSIST_RSSI = -50.0
    private const val BASELINE_NEAR_DELTA_DB = 14.0
    private const val NEAR_DWELL_MS = 400L
    private const val FAR_REARM_MS = 650L
    private const val COMPLETED_TRANSFER_COOLDOWN_MS = 5_000L
    private const val MAX_DISARM_MS = 10_000L
    private const val STARTUP_APPROACH_GAIN_DB = 1.0
    private const val STARTUP_APPROACH_DWELL_MS = 800L
    private const val REAPPROACH_GAIN_DB = 1.5
    private const val LIGHT_SEPARATION_DB = 1.5
    private const val LIGHT_SEPARATION_DWELL_MS = 200L
    private const val ABSENT_REARM_MS = 1_600L
    private const val IMPACT_ASSIST_WINDOW_MS = 900L
    private const val IMPACT_DEBOUNCE_MS = 850L
    private const val OFFER_POLL_MS = 220L
    private const val OFFER_TTL_MS = 18_000L
    private const val SESSION_STALE_MS = 35_000L
    private const val PEER_FRESH_MS = 3_000L
    private const val ADVERTISEMENT_REFRESH_MS = 60_000L
    private const val TAG = "OrbTouchTransfer"

    enum class Client { UI, PLAYBACK_SERVICE }

    /**
     * Per-peer proximity state.
     *
     * BLE RSSI cannot be converted into an exact physical distance across all Android hardware,
     * so Orb uses a conservative adaptive gate: an absolute "very close" threshold, a learned
     * far-field baseline + delta, hysteresis, and (when available) a brief accelerometer assist.
     * This is tuned for roughly phone-to-tablet handoff distance (~15 cm), not room proximity.
     */
    private data class PeerPresence(
        val deviceKey: String,
        var rssi: Int,
        var smoothedRssi: Double,
        var isPlaying: Boolean,
        var senderReady: Boolean,
        var lastSeenAtMs: Long,
        var baselineRssi: Double? = null,
        var nearSinceAtMs: Long = 0L,
        var farSinceAtMs: Long = 0L,
        var nearConfirmed: Boolean = false,
        var armed: Boolean = true,
        var nearPeakRssi: Double = -127.0,
        var disarmedAtElapsedMs: Long = 0L,
        var disarmedRssi: Double = -127.0,
        var initialApproachRequired: Boolean = true,
        var initialApproachMinimumRssi: Double = smoothedRssi,
        var initialApproachSinceElapsedMs: Long = 0L,
        var initialApproachLastSampleElapsedMs: Long = 0L,
        var requiresNewApproach: Boolean = false,
        var separatedForNewApproach: Boolean = false,
        var newApproachSeparationSinceElapsedMs: Long = 0L,
        var lastNewApproachSampleElapsedMs: Long = 0L,
        var reapproachMinimumRssi: Double = -127.0,
        var reapproachSinceElapsedMs: Long = 0L,
        var separationSinceElapsedMs: Long = 0L,
        var lastSeenElapsedMs: Long = SystemClock.elapsedRealtime(),
    )

    sealed interface Event {
        data class OutgoingOffer(val handoffId: String, val song: TouchSong) : Event
        data class IncomingOffer(val handoffId: String, val payload: TouchTransferPayload) : Event
        data class PauseSource(val handoffId: String, val song: TouchSong) : Event
        data class StartReceiver(val handoffId: String, val song: TouchSong, val positionMs: Long, val payload: TouchTransferPayload) : Event
        data class Failed(val message: String, val handoffId: String? = null) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var bluetoothManager: BluetoothManager? = null
    private var accountId: String? = null
    private var deviceTag: ByteArray = ByteArray(8)
    private var deviceKey: String = ""
    val transferDeviceKey: String get() = deviceKey
    private var playback = TouchPlaybackSnapshot()
    private var lastImpactAtMs = 0L
    private var pollJob: Job? = null
    private var rearmJob: Job? = null
    @Volatile private var nextTransferAllowedAtElapsedMs = 0L
    private var cooldownHandoffId: String? = null
    private var activeOutgoingId: String? = null
    private var activeIncomingId: String? = null
    private var activeOutgoingStartedAtMs = 0L
    private var activeIncomingStartedAtMs = 0L
    private var sourcePauseEventSentFor: String? = null
    private var receiverStartEventSentFor: String? = null
    @Volatile private var preparedIncomingId: String? = null
    private var incomingOfferLastEmitAtMs = 0L
    private var lastAdvertisedPlaying = false
    private var lastAdvertisementRefreshAtMs = 0L
    @Volatile private var running = false
    @Volatile private var uiActive = false
    @Volatile private var nowPlayingOpen = false
    @Volatile private var offerCreating = false
    private val clients = mutableSetOf<Client>()
    private val recentPeers = LinkedHashMap<String, PeerPresence>()

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            val now = System.currentTimeMillis()
            if (now - lastImpactAtMs < IMPACT_DEBOUNCE_MS) return
            val x = event.values.getOrNull(0) ?: return
            val y = event.values.getOrNull(1) ?: return
            val z = event.values.getOrNull(2) ?: return
            val magnitude = sqrt(x * x + y * y + z * z)
            val impact = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                magnitude >= 2.35f
            } else {
                abs(magnitude - SensorManager.GRAVITY_EARTH) >= 3.4f
            }
            if (impact) recordImpact(now)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertise failed: $errorCode")
            _events.tryEmit(Event.Failed("Não foi possível anunciar este aparelho para a transferência por aproximação."))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScan(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleScan)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _events.tryEmit(Event.Failed("Não foi possível procurar o outro aparelho por Bluetooth."))
        }
    }

    @SuppressLint("MissingPermission")
    fun supported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        // Before the Nearby Devices grant, avoid touching BluetoothAdapter APIs that themselves
        // require BLUETOOTH_CONNECT/ADVERTISE on Android 12+.
        if (!hasRuntimePermissions(context)) return true
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }

    fun hasRuntimePermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, userId: String, client: Client = Client.UI) {
        if (userId.isBlank()) return
        // Product rule: devices are discoverable only while Orb's UI is actually foreground.
        // PlaybackService/background callers can never keep BLE alive behind a locked screen.
        if (client != Client.UI || !uiActive) return
        if (!AppSettings.premiumEntitled.value || !AppSettings.touchTransferEnabled.value || SocialRepository.currentUserId() != userId) return
        if (!supported(context) || !hasRuntimePermissions(context)) return

        clients += client
        if (running && accountId == userId) return
        if (running && accountId != userId) stopInternal()

        val application = context.applicationContext
        appContext = application
        accountId = userId
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stableId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        deviceTag = sha256("orb-touch-device:$stableId").copyOfRange(0, 8)
        deviceKey = deviceTag.toHex()
        bluetoothManager = application.getSystemService(BluetoothManager::class.java)
        sensorManager = application.getSystemService(SensorManager::class.java)
        sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        running = true
        synchronized(recentPeers) { recentPeers.clear() }
        restartAdvertising()
        bluetoothManager?.adapter?.bluetoothLeScanner?.startScan(
            emptyList(),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build(),
            scanCallback,
        )
        pollJob = scope?.launch { pollHandoffs() }
        rearmJob = scope?.launch {
            while (isActive && running) {
                synchronized(recentPeers) {
                    val elapsed = SystemClock.elapsedRealtime()
                    recentPeers.values.forEach { checkPeerRearm(it, elapsed) }
                }
                delay(100L)
            }
        }
        Log.i(TAG, "started for $client")
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop(client: Client = Client.UI) {
        clients -= client
        if (clients.isNotEmpty()) return
        stopInternal()
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        if (!running) return
        running = false
        pollJob?.cancel()
        rearmJob?.cancel()
        runCatching { sensorManager?.unregisterListener(sensorListener) }
        val adapter = bluetoothManager?.adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        scope?.cancel()
        scope = null
        sensor = null
        sensorManager = null
        bluetoothManager = null
        accountId = null
        activeOutgoingId = null
        activeIncomingId = null
        activeOutgoingStartedAtMs = 0L
        activeIncomingStartedAtMs = 0L
        sourcePauseEventSentFor = null
        receiverStartEventSentFor = null
        incomingOfferLastEmitAtMs = 0L
        preparedIncomingId = null
        offerCreating = false
        synchronized(recentPeers) { recentPeers.clear() }
        Log.i(TAG, "stopped")
    }

    fun setUiActive(active: Boolean) {
        if (uiActive == active) return
        uiActive = active
        if (running) restartAdvertising()
    }

    /** Receiving stays available throughout Orb; sending requires its expanded player. */
    fun setNowPlayingOpen(open: Boolean) {
        if (nowPlayingOpen == open) return
        nowPlayingOpen = open
        if (running) restartAdvertising()
        // Once PauseSource was dispatched, the transfer itself collapses Now Playing.
        // Let that accepted transaction finish, but cancel a still-pending offer.
        val pending = activeOutgoingId
        if (!open && pending != null && sourcePauseEventSentFor != pending) {
            scope?.launch { cancel(pending) }
        }
    }

    private fun canOfferFromThisDevice(): Boolean =
        running && uiActive && nowPlayingOpen && playback.isPlaying

    fun isUiActive(): Boolean = uiActive

    fun updatePlayback(snapshot: TouchPlaybackSnapshot) {
        playback = snapshot
        if (!running) return
        if (snapshot.isPlaying != lastAdvertisedPlaying) {
            lastAdvertisedPlaying = snapshot.isPlaying
            restartAdvertising()
        }
    }

    private val rejectedHandoffs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failureNotified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun notifyIncomplete(handoffId: String) {
        rejectedHandoffs.add(handoffId)
        if (preparedIncomingId == handoffId) preparedIncomingId = null
        if (failureNotified.add(handoffId)) {
            nextTransferAllowedAtElapsedMs = maxOf(nextTransferAllowedAtElapsedMs,
                SystemClock.elapsedRealtime() + 3_000L)
            val message = appContext?.getString(com.music.orb.R.string.switch_incomplete_retry)
                ?: "A transferência não foi concluída. Tente novamente em alguns segundos."
            _events.tryEmit(Event.Failed(message, handoffId))
        }
    }

    fun canStartReceiver(handoffId: String): Boolean =
        handoffId !in rejectedHandoffs && preparedIncomingId == handoffId && activeIncomingId == handoffId

    suspend fun receiverPrepared(handoffId: String) {
        if (handoffId in rejectedHandoffs || activeIncomingId != handoffId) return
        preparedIncomingId = handoffId
        Log.i(TAG, "receiver audio prepared $handoffId")
        updateState(handoffId, "receiver_ready")
        incomingOfferLastEmitAtMs = 0L
    }
    suspend fun sourcePaused(handoffId: String, finalPositionMs: Long) {
        val userId = accountId ?: return
        val row = OrbSupabase.client.from("orb_touch_handoffs").select {
            filter { eq("id", handoffId); eq("user_id", userId) }
        }.decodeSingle<TouchHandoffRow>()
        check(row.state == "receiver_ready" && handoffId !in rejectedHandoffs && uiActive) {
            "Transfer no longer ready"
        }
        val value = row.payload.copy(continuation = com.music.orb.data.social.PlaybackContinuity.snapshot(row.payload.currentSong.videoId),
            castResumeAtMs = if(row.payload.castDeviceId!=null) System.currentTimeMillis() else 0L)
        runCatching {
            OrbSupabase.client.from("orb_touch_handoffs").update(
                {
                    set("payload", kotlinx.serialization.json.Json.encodeToJsonElement(value))
                    set("state", "source_paused")
                    set("handoff_position_ms", finalPositionMs.coerceAtLeast(0L))
                    set("updated_at", Instant.now().toString())
                },
            ) {
                filter {
                    eq("id", handoffId)
                    eq("user_id", userId)
                    eq("state", "receiver_ready")
                }
            }
        }.getOrThrow()
        com.music.orb.data.social.PlaybackContinuity.relinquish(row.payload.currentSong.videoId)
    }
    /** Commit only after both visual legs and silent receiver preparation succeeded. */
    suspend fun receiverCompleted(handoffId: String): Boolean {
        if (!canStartReceiver(handoffId) || !uiActive) return false
        if (!updateState(handoffId, "complete")) return false
        val userId = accountId ?: return false
        val row = OrbSupabase.client.from("orb_touch_handoffs").select {
            filter { eq("id", handoffId); eq("user_id", userId) }
        }.decodeSingle<TouchHandoffRow>()
        if (row.state != "complete" || row.receiverDeviceKey != deviceKey ||
            handoffId in rejectedHandoffs) return false
        beginCompletedTransferCooldown(handoffId)
        if (activeIncomingId == handoffId) {
            activeIncomingId = null
            activeIncomingStartedAtMs = 0L
        }
        incomingOfferLastEmitAtMs = 0L
        return true
    }

    suspend fun cancel(handoffId: String) {
        // Reject queued/stale events locally even if the cancellation write fails.
        notifyIncomplete(handoffId)
        updateState(handoffId, "cancelled")
        if (activeIncomingId == handoffId) {
            activeIncomingId = null
            activeIncomingStartedAtMs = 0L
        }
        if (activeOutgoingId == handoffId) {
            activeOutgoingId = null
            activeOutgoingStartedAtMs = 0L
        }
        if (sourcePauseEventSentFor == handoffId) sourcePauseEventSentFor = null
        if (receiverStartEventSentFor == handoffId) receiverStartEventSentFor = null
        incomingOfferLastEmitAtMs = 0L
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertising() {
        val context = appContext ?: return
        if (!running || !hasRuntimePermissions(context)) return
        val advertiser = bluetoothManager?.adapter?.bluetoothLeAdvertiser ?: return
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .addManufacturerData(MANUFACTURER_ID, advertisementPayload())
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
        lastAdvertisementRefreshAtMs = System.currentTimeMillis()
    }

    private fun advertisementPayload(): ByteArray {
        val flags = (if (playback.isPlaying) FLAG_PLAYING else 0) or
            (if (canOfferFromThisDevice()) FLAG_SENDER_READY else 0)
        return ByteBuffer.allocate(PAYLOAD_BYTES)
            .put(PROTOCOL_VERSION)
            .put(flags.toByte())
            .put(accountTagForNow())
            .put(deviceTag)
            .array()
    }

    private fun recordImpact(now: Long) {
        lastImpactAtMs = now
        // A physical tap can help devices with weak/awkward Bluetooth antenna placement cross
        // the near gate, but proximity itself is now the primary trigger.
        synchronized(recentPeers) {
            recentPeers.values.forEach { peer ->
                updatePeerProximity(peer, now)
                maybeCreateOfferForPeer(peer, now)
            }
        }
    }

    private fun handleScan(result: ScanResult) {
        synchronized(recentPeers) {
            if (!running || !uiActive) return
            val bytes = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
            if (bytes.size < PAYLOAD_BYTES || bytes[0] != PROTOCOL_VERSION) return

            val buffer = ByteBuffer.wrap(bytes)
            buffer.get()
            val flags = buffer.get().toInt() and 0xFF
            val peerAccount = ByteArray(8).also { buffer.get(it) }
            if (!matchesAccountTag(peerAccount)) return

            val peerDevice = ByteArray(8).also { buffer.get(it) }
            if (peerDevice.contentEquals(deviceTag)) return

            val peerKey = peerDevice.toHex()
            val now = System.currentTimeMillis()
            val peerPlaying = flags and FLAG_PLAYING != 0
            val peerSenderReady = flags and FLAG_SENDER_READY != 0
            val previous = recentPeers[peerKey]

            val peer = if (previous == null) {
                PeerPresence(
                    deviceKey = peerKey,
                    rssi = result.rssi,
                    smoothedRssi = result.rssi.toDouble(),
                    isPlaying = peerPlaying,
                    senderReady = peerSenderReady,
                    lastSeenAtMs = now,
                ).also {
                    recentPeers[peerKey] = it
                    if (it.initialApproachRequired) {
                        Log.i(TAG, "peer $peerKey already nearby at discovery; waiting for increased proximity")
                    }
                }
            } else {
                // Favor recent samples while still damping single-scan RSSI spikes.
                previous.smoothedRssi = previous.smoothedRssi * 0.68 + result.rssi * 0.32
                previous.rssi = result.rssi
                previous.isPlaying = peerPlaying
                previous.senderReady = peerSenderReady
                previous.lastSeenAtMs = now
                previous
            }

            peer.lastSeenElapsedMs = SystemClock.elapsedRealtime()
            checkPeerRearm(peer, peer.lastSeenElapsedMs)
            updatePeerProximity(peer, now)
            updateInitialApproach(peer, now, peer.lastSeenElapsedMs)
            updateNewApproach(peer, now, peer.lastSeenElapsedMs)
            maybeCreateOfferForPeer(peer, now)

            if (recentPeers.size > 12) {
                recentPeers.entries
                    .sortedBy { it.value.lastSeenAtMs }
                    .take(recentPeers.size - 12)
                    .forEach { recentPeers.remove(it.key) }
            }
        }
    }

    private fun adaptiveNearThreshold(peer: PeerPresence): Double {
        val baseline = peer.baselineRssi ?: return ABSOLUTE_NEAR_RSSI
        // BLE is not a centimeter ruler. For the Orb gesture we therefore require a large
        // rise over the learned "far" signal and also a strong absolute signal. This keeps
        // devices sitting on opposite sides of the same table from qualifying as "touching".
        return maxOf(-50.0, baseline + BASELINE_NEAR_DELTA_DB).coerceAtMost(-42.0)
    }

    private fun isNearCandidate(peer: PeerPresence, now: Long): Boolean {
        val rssi = peer.smoothedRssi

        // Without a learned far-field baseline, only an exceptionally strong signal qualifies.
        // A physical tap can assist awkward antenna layouts, but only when RSSI is already strong.
        val absoluteVeryClose = rssi >= ABSOLUTE_NEAR_RSSI
        val adaptiveVeryClose =
            peer.baselineRssi != null &&
                rssi >= adaptiveNearThreshold(peer)
        val impactAssist =
            now - lastImpactAtMs <= IMPACT_ASSIST_WINDOW_MS &&
                rssi >= IMPACT_ASSIST_RSSI

        return absoluteVeryClose || adaptiveVeryClose || impactAssist
    }

    private fun updatePeerProximity(peer: PeerPresence, now: Long) {
        val nearCandidate = isNearCandidate(peer, now)

        // Signal may still be strong at table distance. A consumed gesture cannot
        // restore proximity until the separation/approach sequence is complete.
        if (peer.requiresNewApproach && peer.separatedForNewApproach) {
            peer.nearConfirmed = false
            peer.nearSinceAtMs = 0L
            return
        }

        if (nearCandidate) {
            peer.farSinceAtMs = 0L
            peer.nearPeakRssi = maxOf(peer.nearPeakRssi, peer.smoothedRssi)
            if (peer.nearSinceAtMs == 0L) peer.nearSinceAtMs = now
            if (!peer.nearConfirmed && now - peer.nearSinceAtMs >= NEAR_DWELL_MS) {
                peer.nearConfirmed = true
                Log.i(
                    TAG,
                    "peer ${peer.deviceKey} entered handoff range " +
                        "(rssi=${"%.1f".format(peer.smoothedRssi)} dBm, " +
                        "threshold=${"%.1f".format(adaptiveNearThreshold(peer))})",
                )
            }
            return
        }

        peer.nearSinceAtMs = 0L

        // Learn the ordinary far-field signal only while outside the transfer zone.
        if (!peer.nearConfirmed && peer.smoothedRssi < -50.0) {
            peer.baselineRssi = peer.baselineRssi
                ?.let { old -> old * 0.88 + peer.smoothedRssi * 0.12 }
                ?: peer.smoothedRssi
        }

        val entryThreshold = adaptiveNearThreshold(peer)
        val exitThreshold = entryThreshold - 7.0
        val droppedFromTransferPeak =
            peer.nearPeakRssi > -120.0 &&
                peer.smoothedRssi <= peer.nearPeakRssi - 7.0

        // Re-arm from *relative separation* as well as absolute signal weakness.
        // This fixes the previous state where a strong-radio tablet could remain "near"
        // forever even after being moved 30–50 cm away.
        val clearlyFar =
            peer.smoothedRssi <= exitThreshold ||
                droppedFromTransferPeak

        if (clearlyFar) {
            if (peer.farSinceAtMs == 0L) peer.farSinceAtMs = now
            if (now - peer.farSinceAtMs >= FAR_REARM_MS) {
                if (peer.nearConfirmed || !peer.armed) {
                    Log.i(
                        TAG,
                        "peer ${peer.deviceKey} left handoff range; re-armed " +
                            "(rssi=${"%.1f".format(peer.smoothedRssi)} dBm)",
                    )
                }
                peer.nearConfirmed = false
                peer.armed = true
                peer.nearPeakRssi = -127.0
            }
        } else {
            peer.farSinceAtMs = 0L
        }
    }

    private fun disarmPeer(peer: PeerPresence) {
        synchronized(recentPeers) {
            // Repeated delivery of the same incoming offer must not extend the deadline.
            if (!peer.armed && peer.disarmedAtElapsedMs != 0L) return
            peer.armed = false
            peer.disarmedAtElapsedMs = SystemClock.elapsedRealtime()
            peer.disarmedRssi = peer.smoothedRssi
            peer.requiresNewApproach = true
            peer.separatedForNewApproach = false
            peer.newApproachSeparationSinceElapsedMs = 0L
            peer.lastNewApproachSampleElapsedMs = 0L
            peer.reapproachMinimumRssi = peer.smoothedRssi
            peer.reapproachSinceElapsedMs = 0L
            peer.separationSinceElapsedMs = 0L
        }
    }

    private fun checkPeerRearm(peer: PeerPresence, elapsed: Long) {
        if (peer.armed) return
        val deadlineReached = elapsed - peer.disarmedAtElapsedMs >= MAX_DISARM_MS
        val absent = elapsed - peer.lastSeenElapsedMs >= ABSENT_REARM_MS
        val sampleFresh = elapsed - peer.lastSeenElapsedMs <= 500L
        val slightlySeparated = sampleFresh &&
            peer.smoothedRssi <= peer.disarmedRssi - LIGHT_SEPARATION_DB
        if (slightlySeparated) {
            if (peer.separationSinceElapsedMs == 0L) peer.separationSinceElapsedMs = elapsed
        } else {
            peer.separationSinceElapsedMs = 0L
        }
        val separationConfirmed = slightlySeparated &&
            elapsed - peer.separationSinceElapsedMs >= LIGHT_SEPARATION_DWELL_MS
        if (!deadlineReached && !absent && !separationConfirmed) return
        peer.armed = true
        peer.nearConfirmed = false
        peer.nearSinceAtMs = 0L
        peer.farSinceAtMs = 0L
        peer.nearPeakRssi = -127.0
        peer.disarmedAtElapsedMs = 0L
        peer.separationSinceElapsedMs = 0L
        Log.i(TAG, "peer ${peer.deviceKey} re-armed: " + when {
            deadlineReached -> "10 second deadline"
            absent -> "absent for 1.6 seconds"
            else -> "1.5 dB separation for 200 ms"
        })
    }

    private fun transferCoolingDown(): Boolean =
        SystemClock.elapsedRealtime() < nextTransferAllowedAtElapsedMs

    @Synchronized
    private fun beginCompletedTransferCooldown(handoffId: String) {
        if (cooldownHandoffId == handoffId) return
        cooldownHandoffId = handoffId
        nextTransferAllowedAtElapsedMs =
            SystemClock.elapsedRealtime() + COMPLETED_TRANSFER_COOLDOWN_MS
        synchronized(recentPeers) {
            recentPeers.values.filter { it.disarmedRssi > -120.0 }.forEach { peer ->
                // Consume any gesture observed during preparation, on BOTH devices.
                // Keep a real separation already observed; completion is not separation.
                peer.requiresNewApproach = true
                peer.reapproachSinceElapsedMs = 0L
                peer.reapproachMinimumRssi = peer.smoothedRssi
                peer.nearConfirmed = false
                peer.nearSinceAtMs = 0L
            }
        }
        Log.i(TAG, "handoff $handoffId complete; new transfers blocked for 5 seconds and until a fresh approach")
    }

    private fun updateInitialApproach(peer: PeerPresence, now: Long, elapsed: Long) {
        if (!peer.initialApproachRequired) return
        if (peer.initialApproachLastSampleElapsedMs != 0L &&
            elapsed - peer.initialApproachLastSampleElapsedMs > 500L
        ) {
            peer.initialApproachSinceElapsedMs = 0L
        }
        peer.initialApproachLastSampleElapsedMs = elapsed
        peer.initialApproachMinimumRssi = minOf(peer.initialApproachMinimumRssi, peer.smoothedRssi)
        val increasedProximity =
            peer.smoothedRssi - peer.initialApproachMinimumRssi >= STARTUP_APPROACH_GAIN_DB &&
                isNearCandidate(peer, now)
        if (!increasedProximity) {
            peer.initialApproachSinceElapsedMs = 0L
            return
        }
        if (peer.initialApproachSinceElapsedMs == 0L) peer.initialApproachSinceElapsedMs = elapsed
        if (elapsed - peer.initialApproachSinceElapsedMs < STARTUP_APPROACH_DWELL_MS) return
        peer.initialApproachRequired = false
        peer.initialApproachSinceElapsedMs = 0L
        Log.i(TAG, "peer ${peer.deviceKey} initial approach confirmed after signal increase")
    }

    private fun recordSmallSeparation(peer: PeerPresence, elapsed: Long) {
        if (peer.separatedForNewApproach) return
        if (peer.lastNewApproachSampleElapsedMs != 0L &&
            elapsed - peer.lastNewApproachSampleElapsedMs > 500L
        ) {
            peer.newApproachSeparationSinceElapsedMs = 0L
        }
        peer.lastNewApproachSampleElapsedMs = elapsed
        if (peer.smoothedRssi > peer.disarmedRssi - LIGHT_SEPARATION_DB) {
            peer.newApproachSeparationSinceElapsedMs = 0L
            return
        }
        if (peer.newApproachSeparationSinceElapsedMs == 0L) {
            peer.newApproachSeparationSinceElapsedMs = elapsed
        }
        if (elapsed - peer.newApproachSeparationSinceElapsedMs >= LIGHT_SEPARATION_DWELL_MS) {
            peer.separatedForNewApproach = true
            peer.nearConfirmed = false
            peer.nearSinceAtMs = 0L
            peer.reapproachMinimumRssi = peer.smoothedRssi
            peer.reapproachSinceElapsedMs = 0L
            Log.i(TAG, "peer ${peer.deviceKey} small separation confirmed (1.5 dB for 200 ms)")
        }
    }

    private fun updateNewApproach(peer: PeerPresence, now: Long, elapsed: Long) {
        if (!peer.requiresNewApproach) return
        val sampleGap = peer.lastNewApproachSampleElapsedMs != 0L &&
            elapsed - peer.lastNewApproachSampleElapsedMs > 500L
        if (sampleGap) peer.reapproachSinceElapsedMs = 0L
        recordSmallSeparation(peer, elapsed)
        peer.lastNewApproachSampleElapsedMs = elapsed
        if (transferCoolingDown() || activeIncomingId != null || activeOutgoingId != null || offerCreating) {
            // A gesture made during the cooldown must not become an automatic queued transfer.
            peer.reapproachMinimumRssi = peer.smoothedRssi
            peer.reapproachSinceElapsedMs = 0L
            return
        }
        peer.reapproachMinimumRssi = minOf(peer.reapproachMinimumRssi, peer.smoothedRssi)
        // Missing packets or the 10s timer are not proof of physical separation.
        val gainedSignal = peer.smoothedRssi - peer.reapproachMinimumRssi >= REAPPROACH_GAIN_DB
        val approachingAgain = peer.armed && peer.separatedForNewApproach &&
            gainedSignal &&
            peer.smoothedRssi >= peer.disarmedRssi - 0.5 &&
            isNearCandidate(peer, now)
        if (!approachingAgain) {
            peer.reapproachSinceElapsedMs = 0L
            return
        }
        if (peer.reapproachSinceElapsedMs == 0L) peer.reapproachSinceElapsedMs = elapsed
        if (elapsed - peer.reapproachSinceElapsedMs < NEAR_DWELL_MS) return
        peer.requiresNewApproach = false
        peer.nearConfirmed = true
        peer.nearSinceAtMs = now
        peer.reapproachSinceElapsedMs = 0L
        Log.i(TAG, "peer ${peer.deviceKey} new approach confirmed after separation")
    }

    private fun maybeCreateOfferForPeer(peer: PeerPresence, now: Long) {
        if (!canOfferFromThisDevice() || !peer.nearConfirmed || !peer.armed || peer.requiresNewApproach || peer.initialApproachRequired) return
        if (transferCoolingDown()) return
        if (now - peer.lastSeenAtMs > PEER_FRESH_MS) return
        if (!playback.isPlaying || peer.isPlaying) return
        if (activeOutgoingId != null || activeIncomingId != null || offerCreating) return

        val payload = playback.toPayload() ?: return

        // Disarm immediately. Both sides are re-armed only after they are moved apart for a short
        // period, which prevents instant ping-pong while the devices are still next to each other.
        disarmPeer(peer)
        offerCreating = true
        scope?.launch {
            try {
                createOffer(peer.deviceKey, payload)
            } finally {
                offerCreating = false
            }
        } ?: run { offerCreating = false }
    }

    private suspend fun createOffer(receiverDeviceKey: String, payload: TouchTransferPayload) {
        val userId = accountId ?: return
        if (!canOfferFromThisDevice() || !AppSettings.premiumEntitled.value || !AppSettings.touchTransferEnabled.value || SocialRepository.currentUserId() != userId) return
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val row = TouchHandoffInsert(
            id = id,
            userId = userId,
            sourceDeviceKey = deviceKey,
            receiverDeviceKey = receiverDeviceKey,
            state = "offered",
            payload = payload,
            createdAt = now.toString(),
            updatedAt = now.toString(),
            expiresAt = now.plusMillis(OFFER_TTL_MS).toString(),
        )
        runCatching { OrbSupabase.client.from("orb_touch_handoffs").insert(row) }
            .onSuccess {
                activeOutgoingId = id
                activeOutgoingStartedAtMs = System.currentTimeMillis()
                // The player may have closed while the offer was being persisted.
                if (!canOfferFromThisDevice()) {
                    scope?.launch { cancel(id) }
                    return@onSuccess
                }
                Log.i(TAG, "handoff offered $id to $receiverDeviceKey")
                _events.tryEmit(Event.OutgoingOffer(id, payload.currentSong))
            }
            .onFailure { error ->
                Log.w(TAG, "failed to create handoff offer", error)
                _events.tryEmit(Event.Failed("Não foi possível iniciar a transferência por aproximação."))
            }
    }

    private suspend fun pollHandoffs() {
        while (currentCoroutineContext().isActive && running) {
            val userId = accountId
            val loopNow = System.currentTimeMillis()

            // Never let a failed/abandoned handoff permanently block future transfers.
            if (activeOutgoingId != null &&
                activeOutgoingStartedAtMs > 0L &&
                loopNow - activeOutgoingStartedAtMs >= SESSION_STALE_MS
            ) {
                Log.w(TAG, "stale outgoing handoff cleared")
                activeOutgoingId?.let { cancel(it) }
                activeOutgoingId = null
                activeOutgoingStartedAtMs = 0L
                sourcePauseEventSentFor = null
            }
            if (activeIncomingId != null &&
                activeIncomingStartedAtMs > 0L &&
                loopNow - activeIncomingStartedAtMs >= SESSION_STALE_MS
            ) {
                Log.w(TAG, "stale incoming handoff cleared")
                activeIncomingId?.let { cancel(it) }
                activeIncomingId = null
                activeIncomingStartedAtMs = 0L
                receiverStartEventSentFor = null
                incomingOfferLastEmitAtMs = 0L
            }

            if (loopNow - lastAdvertisementRefreshAtMs >= ADVERTISEMENT_REFRESH_MS) {
                restartAdvertising()
            }
            if (!uiActive || userId == null || !AppSettings.premiumEntitled.value || !AppSettings.touchTransferEnabled.value || SocialRepository.currentUserId() != userId) {
                delay(OFFER_POLL_MS)
                continue
            }
            runCatching {
                OrbSupabase.client.from("orb_touch_handoffs")
                    .select {
                        filter { eq("user_id", userId) }
                        order("created_at", Order.DESCENDING)
                        range(0L..11L)
                    }
                    .decodeList<TouchHandoffRow>()
            }.onSuccess { rows ->
                val now = System.currentTimeMillis()
                rows.forEach { row ->
                    val expiresAt = row.expiresAt.toEpochMillisOrZero()
                    if (expiresAt <= now && row.state != "complete") {
                        if (activeOutgoingId == row.id || activeIncomingId == row.id) notifyIncomplete(row.id)
                        if (activeOutgoingId == row.id) {
                            activeOutgoingId = null
                            activeOutgoingStartedAtMs = 0L
                            sourcePauseEventSentFor = null
                        }
                        if (activeIncomingId == row.id) {
                            activeIncomingId = null
                            activeIncomingStartedAtMs = 0L
                            receiverStartEventSentFor = null
                        }
                        return@forEach
                    }

                    if (row.id in rejectedHandoffs) return@forEach
                    when {
                        row.receiverDeviceKey == deviceKey &&
                            row.state == "offered" -> {
                            // Existing accepted transfers may finish; only new offers are blocked.
                            if (activeIncomingId != row.id && transferCoolingDown()) return@forEach
                            // The sender already selected this exact device from a very recent BLE
                            // sighting and both devices are authenticated as the same Orb account.
                            // Requiring a second impact on the tablet was too brittle: tablets often
                            // sit flat on a desk and barely register the physical touch. Instead, the
                            // receiver confirms that the source is still physically nearby via BLE.
                            if (preparedIncomingId == row.id) {
                                // Retry only the state acknowledgement, never Media3 preparation.
                                // The expected-state filter prevents a stale read from regressing the handoff.
                                updateState(row.id, "receiver_ready")
                                return@forEach
                            }
                            val sourcePeer = synchronized(recentPeers) { recentPeers[row.sourceDeviceKey] }
                            // Do not recognize a new sender unless its latest BLE packet
                            // explicitly says Now Playing is open and playback is active.
                            if (activeIncomingId != row.id && sourcePeer?.senderReady != true) return@forEach
                            // Once accepted, the active handoff has its own proximity confirmation.
                            // Rearming the next gesture must not invalidate this transaction.
                            val alreadyAccepted = activeIncomingId == row.id
                            val sourceStillNearby = alreadyAccepted || (sourcePeer != null &&
                                now - sourcePeer.lastSeenAtMs <= PEER_FRESH_MS &&
                                sourcePeer.nearConfirmed && !sourcePeer.requiresNewApproach &&
                                !sourcePeer.initialApproachRequired)

                            if (sourceStillNearby) {
                                if (activeIncomingId == null || activeIncomingId == row.id) {
                                    activeIncomingId = row.id
                                    if (activeIncomingStartedAtMs == 0L) {
                                        activeIncomingStartedAtMs = now
                                    }
                                    // The receiver also disarms this peer. A reverse handoff is
                                    // allowed only after both devices separate and approach again.
                                    if (!alreadyAccepted) sourcePeer?.let { disarmPeer(it) }
                                    // Retry delivery while the DB row is still offered. This prevents
                                    // a one-shot SharedFlow event from being lost if the Activity
                                    // collector is being recreated exactly when the devices touch.
                                    if (now - incomingOfferLastEmitAtMs >= 650L) {
                                        incomingOfferLastEmitAtMs = now
                                        Log.i(TAG, "receiver accepting offered handoff ${row.id}")
                                        _events.tryEmit(Event.IncomingOffer(row.id, row.payload))
                                    }
                                }
                            } else {
                                Log.d(TAG, "offer ${row.id} waiting for source BLE proximity")
                            }
                        }

                        row.sourceDeviceKey == deviceKey &&
                            row.state == "receiver_ready" &&
                            sourcePauseEventSentFor != row.id -> {
                            if (!canOfferFromThisDevice()) {
                                cancel(row.id)
                                return@forEach
                            }
                            sourcePauseEventSentFor = row.id
                            _events.tryEmit(Event.PauseSource(row.id, row.payload.currentSong))
                        }

                        row.receiverDeviceKey == deviceKey &&
                            row.state == "source_paused" && canStartReceiver(row.id) &&
                            receiverStartEventSentFor != row.id -> {
                            receiverStartEventSentFor = row.id
                            _events.tryEmit(
                                Event.StartReceiver(
                                    row.id,
                                    row.payload.currentSong,
                                    row.handoffPositionMs ?: row.payload.positionMs,
                                    row.payload,
                                ),
                            )
                        }

                        row.sourceDeviceKey == deviceKey &&
                            row.state in setOf("complete", "cancelled", "expired") -> {
                            if (activeOutgoingId == row.id) {
                                if (row.state != "complete") notifyIncomplete(row.id)
                                if (row.state == "complete") beginCompletedTransferCooldown(row.id)
                                activeOutgoingId = null
                                activeOutgoingStartedAtMs = 0L
                            }
                            if (sourcePauseEventSentFor == row.id) sourcePauseEventSentFor = null
                        }

                        row.receiverDeviceKey == deviceKey &&
                            row.state in setOf("complete", "cancelled", "expired") -> {
                            if (activeIncomingId == row.id) {
                                if (row.state != "complete") notifyIncomplete(row.id)
                                if (row.state == "complete") beginCompletedTransferCooldown(row.id)
                                activeIncomingId = null
                                activeIncomingStartedAtMs = 0L
                            }
                            if (receiverStartEventSentFor == row.id) receiverStartEventSentFor = null
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "handoff polling failed", it)
            }
            delay(OFFER_POLL_MS)
        }
    }

    private suspend fun updateState(handoffId: String, state: String): Boolean {
        val userId = accountId ?: return false
        return runCatching {
            OrbSupabase.client.from("orb_touch_handoffs").update(
                {
                    set("state", state)
                    set("updated_at", Instant.now().toString())
                },
            ) {
                filter {
                    eq("id", handoffId)
                    eq("user_id", userId)
                    if (state == "receiver_ready") eq("state", "offered")
                    if (state == "complete") eq("state", "source_paused")
                }
            }
        }.onSuccess {
            Log.i(TAG, "handoff $handoffId -> $state")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "failed to update handoff $handoffId -> $state", error)
        }.isSuccess
    }


    /** Five-minute rotating tag: same-account devices still match without broadcasting a stable id. */
    private fun accountTagForNow(): ByteArray {
        val userId = accountId.orEmpty()
        val bucket = System.currentTimeMillis() / 300_000L
        return sha256("orb-touch-account:$userId:$bucket").copyOfRange(0, 8)
    }

    private fun matchesAccountTag(candidate: ByteArray): Boolean {
        val userId = accountId ?: return false
        val bucket = System.currentTimeMillis() / 300_000L
        return (-1L..1L).any { delta ->
            sha256("orb-touch-account:$userId:${bucket + delta}")
                .copyOfRange(0, 8)
                .contentEquals(candidate)
        }
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun String?.toEpochMillisOrZero(): Long = this
        ?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L) }
        ?: 0L
}

@Serializable
data class TouchPlaybackSnapshot(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val queueIndex: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<TouchSong> = emptyList(),
) {
    fun toPayload(): TouchTransferPayload? {
        if (!isPlaying || queue.isEmpty()) return null
        val index = queueIndex.coerceIn(0, queue.lastIndex)
        val room = com.music.orb.data.rooms.RoomRepository.playbackRoom.value
        return TouchTransferPayload(queue, index, positionMs.coerceAtLeast(0L), repeatMode, queue[index],
            continuation = com.music.orb.data.social.PlaybackContinuity.snapshot(queue[index].videoId),
            castDeviceId = com.music.orb.cast.OrbCastBridge.switchDeviceId,
            roomId = room?.id,
            roomSourceDevice = room?.let { com.music.orb.data.rooms.RoomRepository.deviceId })
    }
}

@Serializable
data class TouchTransferPayload(
    val queue: List<TouchSong>,
    @SerialName("queue_index") val queueIndex: Int,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("repeat_mode") val repeatMode: Int,
    @SerialName("current_song") val currentSong: TouchSong,
    val continuation: com.music.orb.data.social.PlaybackContinuation? = null,
    val roomId: String? = null,
    val roomSourceDevice: String? = null,
    val castDeviceId: String? = null,
    val castResumeAtMs: Long = 0L,
)

@Serializable
data class TouchSong(
    @SerialName("video_id") val videoId: String,
    val title: String,
    val artist: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("duration_text") val durationText: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("source_quality") val sourceQuality: String? = null,
    @SerialName("queue_pinned") val queuePinned: Boolean = false,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("source_playlist_id") val sourcePlaylistId: String? = null,
    @SerialName("source_playlist_title") val sourcePlaylistTitle: String? = null,
    @SerialName("source_playlist_artwork_url") val sourcePlaylistArtworkUrl: String? = null,
    @SerialName("preserve_album_release") val preserveAlbumRelease: Boolean = false,
) {
    fun toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        sourceQuality = sourceQuality,
        queuePinned = queuePinned,
        isExplicit = isExplicit,
        releaseYear = releaseYear,
        sourcePlaylistId = sourcePlaylistId,
        sourcePlaylistTitle = sourcePlaylistTitle,
        sourcePlaylistArtworkUrl = sourcePlaylistArtworkUrl,
        preserveAlbumRelease = preserveAlbumRelease,
    )

    companion object {
        fun from(song: Song): TouchSong = TouchSong(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText,
            artistId = song.artistId,
            albumId = song.albumId,
            albumName = song.albumName,
            sourceQuality = song.sourceQuality,
            queuePinned = song.queuePinned,
            isExplicit = song.isExplicit,
            releaseYear = song.releaseYear,
            sourcePlaylistId = song.sourcePlaylistId,
            sourcePlaylistTitle = song.sourcePlaylistTitle,
            sourcePlaylistArtworkUrl = song.sourcePlaylistArtworkUrl,
            preserveAlbumRelease = song.preserveAlbumRelease,
        )
    }
}

fun Song.toTouchSong(): TouchSong = TouchSong.from(this)

@Serializable
private data class TouchHandoffInsert(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("source_device_key") val sourceDeviceKey: String,
    @SerialName("receiver_device_key") val receiverDeviceKey: String,
    val state: String,
    val payload: TouchTransferPayload,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class TouchHandoffRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("source_device_key") val sourceDeviceKey: String,
    @SerialName("receiver_device_key") val receiverDeviceKey: String? = null,
    val state: String,
    val payload: TouchTransferPayload,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("handoff_position_ms") val handoffPositionMs: Long? = null,
)
