package com.music.orb.data.rooms

import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AutomixVersion
import com.music.orb.playback.smart.TransitionGainPoint
import com.music.orb.playback.smart.TransitionLoopTarget
import com.music.orb.playback.smart.TransitionPlan
import com.music.orb.playback.smart.TransitionStyle
import com.music.orb.playback.smart.TransitionTempoPoint
import org.json.JSONArray
import org.json.JSONObject

/** Host-authored Automix recipe. Guests never construct or substitute a local recipe. */
internal data class RoomMixRecipe(val outgoingId: String, val incomingId: String,
                                  val plan: TransitionPlan,
                                  val phaseOffsetMs: Double = 0.0,
                                  val beatLockEnabled: Boolean = false)

internal data class RoomMixState(
    val roomId: String,
    val queueVersion: Long,
    val enabled: Boolean,
    val version: String,
    val outgoingId: String? = null,
    val incomingId: String? = null,
    val plan: TransitionPlan? = null,
    val history: List<RoomMixRecipe> = emptyList(),
    val playbackSpeed: Float = 1f,
) {
    fun recipeFor(room: ListeningRoom, outgoing: String, incoming: String): RoomMixRecipe? {
        if (!enabled || room.id != roomId || room.queueVersion != queueVersion) return null
        return (history.lastOrNull { it.outgoingId == outgoing && it.incomingId == incoming }
            ?: plan?.takeIf { outgoingId == outgoing && incomingId == incoming }
                ?.let { RoomMixRecipe(outgoing, incoming, it) })
            ?.takeIf { !it.plan.blocked && it.plan.fadeMs > 0L }
    }
    fun planFor(room: ListeningRoom, outgoing: String, incoming: String): TransitionPlan? =
        recipeFor(room, outgoing, incoming)?.plan
}

internal object RoomMixAuthority {
    @Volatile private var hostPlan: RoomMixState? = null
    @Volatile private var guest: RoomMixState? = null
    @Volatile private var guestReceivedAtMs: Long = 0L

    fun clear() { hostPlan = null; guest = null; guestReceivedAtMs = 0L }

    fun onHostPlan(room: ListeningRoom, outgoingId: String, incomingId: String,
                   plan: TransitionPlan, version: String,
                   phaseOffsetMs: Double, beatLockEnabled: Boolean) {
        if (!room.host || plan.blocked || plan.fadeMs <= 0L) return
        // Keep recent host recipes: the guest may intentionally be 5s behind and
        // still need A->B after the host has started planning B->C.
        val previous = hostPlan?.takeIf { it.roomId == room.id &&
            it.queueVersion == room.queueVersion && it.version == version }?.history.orEmpty()
        val history = (previous.filterNot {
            it.outgoingId == outgoingId && it.incomingId == incomingId
        } + RoomMixRecipe(outgoingId, incomingId, plan,
            phaseOffsetMs.takeIf { it.isFinite() } ?: 0.0, beatLockEnabled)).takeLast(4)
        hostPlan = RoomMixState(room.id, room.queueVersion, true, version,
            outgoingId, incomingId, plan, history)
    }

    fun hostState(room: ListeningRoom): RoomMixState {
        val enabled = AppSettings.smartFadeEnabled.value && AppSettings.internetAvailable.value
        val version = if (AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
            AppSettings.automix25Available.value) "2.5" else "2.0"
        if (!enabled) hostPlan = null
        val remembered = hostPlan?.takeIf { it.roomId == room.id &&
            it.queueVersion == room.queueVersion && it.version == version && enabled }
        return (remembered ?: RoomMixState(room.id, room.queueVersion, enabled, version))
            .copy(playbackSpeed = AppSettings.playbackSpeed.value)
    }

    fun receiveGuest(room: ListeningRoom, payload: JSONObject?) {
        guest = payload?.let { RoomMixCodec.decode(room.id, it) }
            ?.takeIf { !room.host && it.queueVersion == room.queueVersion }
        guestReceivedAtMs = System.currentTimeMillis()
    }

    /** Briefly retain a valid host recipe across a cellular RPC timeout. */
    fun onGuestFetchFailure(room: ListeningRoom) {
        if (guest?.roomId != room.id || guest?.queueVersion != room.queueVersion ||
            System.currentTimeMillis() - guestReceivedAtMs > 12_000L) {
            guest = null
        }
    }

    fun guestState(room: ListeningRoom): RoomMixState? =
        if (room.host || System.currentTimeMillis() - guestReceivedAtMs > 12_000L) null
        else guest?.takeIf { it.roomId == room.id && it.queueVersion == room.queueVersion }
}

/** Strict, versioned JSON payload. Never transport tokens, stream URLs or user preferences. */
internal object RoomMixCodec {
    private const val FORMAT = 1
    fun encode(state: RoomMixState): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("queueVersion", state.queueVersion)
        put("enabled", state.enabled)
        put("version", state.version)
        put("playbackSpeed", state.playbackSpeed.toDouble().coerceIn(0.5, 2.0))
        if (state.enabled) {
            put("plans", JSONArray().apply {
                state.history.takeLast(4).forEach { recipe ->
                    put(JSONObject().put("outgoingId", recipe.outgoingId)
                        .put("incomingId", recipe.incomingId)
                        .put("phaseOffsetMs", recipe.phaseOffsetMs)
                        .put("beatLock", recipe.beatLockEnabled)
                        .put("plan", encodePlan(recipe.plan)))
                }
            })
        }
    }

    fun decode(roomId: String, data: JSONObject): RoomMixState? = runCatching {
        if (data.optInt("format") != FORMAT || !data.has("queueVersion")) return@runCatching null
        val version = data.optString("version")
        if (version != "2.0" && version != "2.5") return@runCatching null
        val enabled = data.optBoolean("enabled")
        val outgoingId = data.optString("outgoingId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val incomingId = data.optString("incomingId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val plan = if (enabled && outgoingId != null && incomingId != null) {
            data.optJSONObject("plan")?.let(::decodePlan)
        } else null
        val plans = if (enabled) data.optJSONArray("plans")?.let { array ->
            require(array.length() <= 4)
            (0 until array.length()).mapNotNull { i ->
                val candidate = array.getJSONObject(i)
                val outgoing = candidate.optString("outgoingId")
                val incoming = candidate.optString("incomingId")
                val recipe = candidate.optJSONObject("plan")?.let(::decodePlan)
                if (outgoing.matches(Regex("[A-Za-z0-9_-]{11}")) &&
                    incoming.matches(Regex("[A-Za-z0-9_-]{11}")) && recipe != null) {
                    RoomMixRecipe(outgoing, incoming, recipe,
                        candidate.optDouble("phaseOffsetMs", 0.0)
                            .takeIf { it.isFinite() && it in -10_000.0..10_000.0 } ?: 0.0,
                        candidate.optBoolean("beatLock"))
                } else null
            }
        }.orEmpty() else emptyList()
        RoomMixState(roomId, data.getLong("queueVersion"), enabled, version,
            outgoingId, incomingId, plan, plans,
            data.optDouble("playbackSpeed", 1.0).takeIf { it.isFinite() && it in 0.5..2.0 }
                ?.toFloat() ?: 1f)
    }.getOrNull()

    private fun encodePlan(p: TransitionPlan): JSONObject = JSONObject().apply {
        put("start", p.transitionStart); put("end", p.transitionEnd)
        put("fade", p.fadeSeconds); put("style", p.transitionStyle.name)
        put("cue", p.incomingCueTime); put("handoffTime", p.incomingHandoffTime)
        put("outRate", p.outgoingPlaybackRate); put("inRate", p.incomingPlaybackRate)
        put("loop", p.loopTarget.name); put("loopStart", p.loopStartTime)
        put("loopEnd", p.loopEndTime); put("loopRepeats", p.loopRepeats)
        put("handoffStart", p.handoffStartSeconds); put("handoffDuration", p.handoffDuration)
        put("pickup", p.pickupSeconds); put("beats", p.transitionBeats)
        put("bassSwap", p.bassSwap); put("handoffFraction", p.handoffFraction)
        put("bedPosition", p.bedPosition); put("bassFraction", p.bassSwapFraction)
        put("sweep", p.filterSweep); put("pitch", p.incomingPitchSemitones)
        put("harmonic", p.harmonicLockScore); put("vocalOverlap", p.vocalOverlap)
        put("outBpm", p.outgoingBpm); put("inBpm", p.incomingBpm)
        put("gain", JSONArray().apply { p.gainEnvelope.take(32).forEach {
            put(JSONArray().put(it.progress).put(it.incomingGain).put(it.outgoingGain))
        } })
        put("tempo", JSONArray().apply { p.tempoEnvelope.take(32).forEach {
            put(JSONArray().put(it.progress).put(it.outgoingRate).put(it.incomingRate)
                .put(it.outgoingBpm).put(it.incomingBpm).put(it.confidence))
        } })
    }

    private fun decodePlan(j: JSONObject): TransitionPlan? = runCatching {
        fun number(key: String, default: Double = 0.0): Double = j.optDouble(key, default).also {
            require(it.isFinite())
        }
        val start = number("start")
        val end = number("end")
        val fade = number("fade")
        require(start >= 0 && end > start && fade in 0.1..91.0 && start < 86_400)
        val style = TransitionStyle.valueOf(j.getString("style"))
        val gain = j.optJSONArray("gain")?.let { a ->
            require(a.length() <= 32)
            (0 until a.length()).map { i ->
                val p = a.getJSONArray(i)
                TransitionGainPoint(p.getDouble(0), p.getDouble(1), p.getDouble(2))
            }
        }.orEmpty()
        val tempo = j.optJSONArray("tempo")?.let { a ->
            require(a.length() <= 32)
            (0 until a.length()).map { i ->
                val p = a.getJSONArray(i)
                TransitionTempoPoint(p.getDouble(0), p.getDouble(1), p.getDouble(2),
                    p.getDouble(3), p.getDouble(4), p.optDouble(5, 0.0))
            }
        }.orEmpty()
        require(gain.all { it.progress.isFinite() && it.incomingGain.isFinite() && it.outgoingGain.isFinite() })
        require(tempo.all { it.progress.isFinite() && it.incomingRate.isFinite() && it.outgoingRate.isFinite() })
        TransitionPlan(
            markerVisible = true, transitionStart = start, transitionEnd = end,
            fadeSeconds = fade, transitionStyle = style, incomingCueTime = number("cue"),
            incomingHandoffTime = number("handoffTime"), outgoingPlaybackRate = number("outRate",1.0),
            incomingPlaybackRate = number("inRate",1.0),
            loopTarget = TransitionLoopTarget.valueOf(j.optString("loop", "NONE")),
            loopStartTime = number("loopStart"), loopEndTime = number("loopEnd"),
            loopRepeats = j.optInt("loopRepeats").coerceIn(0, 12),
            handoffStartSeconds = number("handoffStart"), handoffDuration = number("handoffDuration"),
            pickupSeconds = number("pickup"), transitionBeats = j.optInt("beats"),
            bassSwap = j.optBoolean("bassSwap"), handoffFraction = number("handoffFraction",.66),
            bedPosition = number("bedPosition"), bassSwapFraction = number("bassFraction",.7),
            filterSweep = number("sweep"), gainEnvelope = gain, tempoEnvelope = tempo,
            incomingPitchSemitones = number("pitch"), harmonicLockScore = number("harmonic"),
            vocalOverlap = number("vocalOverlap"), outgoingBpm = number("outBpm"),
            incomingBpm = number("inBpm"), reason = "room-host-authored",
        )
    }.getOrNull()
}
