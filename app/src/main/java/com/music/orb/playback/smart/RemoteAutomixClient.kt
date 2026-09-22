package com.music.orb.playback.smart

import android.util.Log
import com.music.orb.BuildConfig
import com.music.orb.data.Http
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Remote authority for Automix planning.
 *
 * Audio playback never waits on this class. The controller asks as soon as A
 * and B both have derived analysis, while A is still playing. A completed
 * server plan is consumed on a later 250 ms controller tick. A network/server
 * failure is explicit and lets the local planner act only as a transport
 * fallback; an in-flight request never silently turns into Crossfade.
 *
 * Only derived musical metadata is sent to /plan -- no playback file and no
 * raw PCM. The server may replace it with its own cached full analysis when it
 * has one for the same trackId.
 */
object RemoteAutomixClient {
    private const val TAG = "OrbRemoteAutomix"
    private val ENDPOINT = BuildConfig.MODULE_INDEX_URL.trimEnd('/') + "/api/automix/plan"
    private const val API_VERSION = 5
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
    private const val MAX_CACHE_ENTRIES = 96

    private val client: OkHttpClient = Http.client.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    data class Decision(
        val plan: TransitionPlan? = null,
        val pending: Boolean = false,
        val failed: Boolean = false,
    )

    private sealed class State {
        object Pending : State()
        object Failed : State()
        data class Ready(val plan: TransitionPlan) : State()
    }

    private data class Entry(
        val state: State,
        val createdAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the current server verdict for this exact analysis pair and starts
     * one request when none exists yet.
     */
    fun decisionFor(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outgoingTrack: TransitionTrackInfo,
        incomingTrack: TransitionTrackInfo,
    ): Decision {
        val key = signature(outgoing, incoming, outgoingTrack, incomingTrack)
        val now = System.currentTimeMillis()
        val existing = entries[key]
        if (existing != null && now - existing.createdAtMs <= CACHE_TTL_MS) {
            return existing.toDecision()
        }
        if (existing != null) entries.remove(key, existing)

        if (entries.size >= MAX_CACHE_ENTRIES) {
            entries.entries
                .sortedBy { it.value.createdAtMs }
                .take(MAX_CACHE_ENTRIES / 4)
                .forEach { entries.remove(it.key, it.value) }
        }

        val pending = Entry(State.Pending, now)
        val raced = entries.putIfAbsent(key, pending)
        if (raced == null) {
            request(key, outgoing, incoming, outgoingTrack, incomingTrack)
            return Decision(pending = true)
        }
        return raced.toDecision()
    }

    /** Warm-service restart should not inherit stale backend verdicts forever. */
    fun clear() {
        entries.clear()
    }

    private fun Entry.toDecision(): Decision = when (val value = state) {
        State.Pending -> Decision(pending = true)
        State.Failed -> Decision(failed = true)
        is State.Ready -> Decision(plan = value.plan)
    }

    private fun request(
        key: String,
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outgoingTrack: TransitionTrackInfo,
        incomingTrack: TransitionTrackInfo,
    ) {
        val payload = JSONObject()
            .put("version", API_VERSION)
            .put("outgoing", analysisJson(outgoing, outgoingTrack))
            .put("incoming", analysisJson(incoming, incomingTrack))
            .toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                entries[key] = Entry(State.Failed, System.currentTimeMillis())
                Log.w(TAG, "plan failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        entries[key] = Entry(State.Failed, System.currentTimeMillis())
                        Log.w(TAG, "plan HTTP ${it.code}")
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val plan = runCatching {
                        parsePlan(JSONObject(body), outgoing, incoming, outgoingTrack, incomingTrack)
                    }.onFailure { error ->
                        Log.w(TAG, "invalid plan: ${error.message}")
                    }.getOrNull()

                    if (plan == null) {
                        entries[key] = Entry(State.Failed, System.currentTimeMillis())
                    } else {
                        entries[key] = Entry(State.Ready(plan), System.currentTimeMillis())
                        Log.d(
                            TAG,
                            "plan ${outgoingTrack.id} -> ${incomingTrack.id}: " +
                                "${plan.transitionStyle} ${plan.transitionStart}-${plan.transitionEnd}s " +
                                "rates=${plan.outgoingPlaybackRate}/${plan.incomingPlaybackRate} " +
                                "handoff=${plan.handoffFraction} pair=${"%.2f".format(plan.pairCompatibility)} " +
                                "phrase=${"%.2f".format(plan.phraseAlignment)} " +
                                "keys=${plan.outgoingTransitionKey}->${plan.incomingTransitionKey} " +
                                "anchors=${plan.outgoingAnchor}->${plan.incomingAnchor} " +
                                "reason=${plan.reason}",
                        )
                    }
                }
            }
        })
    }

    private fun parsePlan(
        root: JSONObject,
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outgoingTrack: TransitionTrackInfo,
        incomingTrack: TransitionTrackInfo,
    ): TransitionPlan? {
        val value = root.optJSONObject("plan") ?: root
        val rawStyle = value.optString("style", root.optString("style", "")).uppercase()
        val reason = value.optString("reason", root.optString("reason", "server-plan"))

        if (rawStyle == "NO_TRANSITION") {
            val end = trackDurationSeconds(outgoing, outgoingTrack)
            return TransitionPlan(
                blocked = true,
                markerVisible = false,
                transitionStart = end,
                transitionEnd = end,
                keyCompatibility = value.finiteDouble("keyCompatibility", 0.0),
                tempoCompatibility = value.finiteDouble("tempoCompatibility", 0.0),
                phraseAlignment = value.finiteDouble("phraseAlignment", 0.0),
                pairCompatibility = value.finiteDouble("pairCompatibility", 0.0),
                energyCompatibility = value.finiteDouble("energyCompatibility", 0.0),
                overlapVocalClash = value
                    .finiteDouble("overlapVocalClash", value.finiteDouble("vocalOverlap", 0.0))
                    .coerceIn(0.0, 1.0),
                spanCompatibility = value.finiteDouble("spanCompatibility", 0.0),
                requestedTransitionBeats = value.optInt("requestedTransitionBeats", 0).coerceAtLeast(0),
                outgoingAnchor = value.optString("outgoingAnchor", ""),
                incomingAnchor = value.optString("incomingAnchor", ""),
                outgoingTransitionKey = value.optString("outgoingTransitionKey", ""),
                incomingTransitionKey = value.optString("incomingTransitionKey", ""),
                reason = "remote:$reason",
                policyReasons = listOf("remote-authoritative"),
            )
        }

        val style = when (rawStyle) {
            "DJ_BLEND" -> TransitionStyle.DJ_BLEND
            "DJ_FILTER" -> TransitionStyle.DJ_FILTER
            "EQ_SWAP" -> TransitionStyle.EQ_SWAP
            "PHRASE_CUT" -> TransitionStyle.PHRASE_CUT
            "CUT" -> TransitionStyle.CUT
            else -> return null
        }

        val currentDuration = trackDurationSeconds(outgoing, outgoingTrack)
        val nextDuration = trackDurationSeconds(incoming, incomingTrack)
        if (currentDuration <= 0.0 || nextDuration <= 0.0) return null

        var end = value.finiteDouble("transitionEnd", currentDuration)
            .coerceIn(0.0, currentDuration)
        var start = value.finiteDouble("transitionStart", end)
            .coerceIn(0.0, end)
        if (end <= start) return null

        // Remote timing is authoritative musically, but the phone owns transport
        // safety. Refuse absurd windows rather than holding two decoders open.
        val maxSpan = when (style) {
            TransitionStyle.PHRASE_CUT, TransitionStyle.CUT -> 1.5
            else -> 14.0
        }
        if (end - start > maxSpan) start = end - maxSpan

        val cue = value.finiteDouble("incomingCueTime", 0.0)
            .coerceIn(0.0, (nextDuration - 0.25).coerceAtLeast(0.0))
        val handoff = value.finiteDouble("incomingHandoffTime", cue)
            .coerceIn(cue, nextDuration)

        val outgoingRate = value.finiteDouble("outgoingPlaybackRate", 1.0)
            .coerceIn(0.96, 1.04)
        val incomingRate = value.finiteDouble("incomingPlaybackRate", 1.0)
            .coerceIn(0.96, 1.04)
        val handoffFraction = value.finiteDouble("handoffFraction", 0.66)
            .coerceIn(0.10, 0.95)
        val bassSwapFraction = value.finiteDouble("bassSwapFraction", handoffFraction)
            .coerceIn(0.10, 0.95)
        val filterSweep = value.finiteDouble("filterSweep", 0.0)
            .coerceIn(0.0, 1.0)
        val transitionBeats = value.optInt("transitionBeats", 0).coerceAtLeast(0)
        val overlapVocalClash = value
            .finiteDouble("overlapVocalClash", value.finiteDouble("vocalOverlap", 0.0))
            .coerceIn(0.0, 1.0)

        return TransitionPlan(
            markerVisible = true,
            transitionStart = start,
            transitionEnd = end,
            fadeSeconds = end - start,
            transitionStyle = style,
            incomingCueTime = cue,
            incomingHandoffTime = handoff,
            outgoingPlaybackRate = outgoingRate,
            incomingPlaybackRate = incomingRate,
            transitionBeats = transitionBeats,
            bassSwap = value.optBoolean("bassSwap", style == TransitionStyle.EQ_SWAP),
            handoffFraction = handoffFraction,
            bassSwapFraction = bassSwapFraction,
            filterSweep = filterSweep,
            vocalOverlap = overlapVocalClash,
            outgoingBpm = outgoing.bpm,
            incomingBpm = incoming.bpm,
            keyCompatibility = value.finiteDouble("keyCompatibility", 0.0),
            tempoCompatibility = value.finiteDouble("tempoCompatibility", 0.0),
            phraseAlignment = value.finiteDouble("phraseAlignment", 0.0),
            pairCompatibility = value.finiteDouble("pairCompatibility", 0.0),
            energyCompatibility = value.finiteDouble("energyCompatibility", 0.0),
            overlapVocalClash = overlapVocalClash,
            spanCompatibility = value.finiteDouble("spanCompatibility", 0.0),
            requestedTransitionBeats = value.optInt("requestedTransitionBeats", 0).coerceAtLeast(0),
            outgoingAnchor = value.optString("outgoingAnchor", ""),
            incomingAnchor = value.optString("incomingAnchor", ""),
            outgoingTransitionKey = value.optString("outgoingTransitionKey", ""),
            incomingTransitionKey = value.optString("incomingTransitionKey", ""),
            policyReasons = listOf("remote-authoritative"),
            reason = "remote:$reason",
        )
    }

    private fun analysisJson(
        analysis: TrackAnalysis,
        track: TransitionTrackInfo,
    ): JSONObject {
        val duration = trackDurationSeconds(analysis, track)
        return JSONObject()
            .put("trackId", analysis.trackId.ifBlank { track.id })
            .put("duration", duration)
            .put("bpm", analysis.bpm)
            .put("beatInterval", analysis.beatInterval)
            .put("beatConfidence", analysis.beatConfidence)
            .put("downbeats", doubles(analysis.downbeats))
            .put("phraseBoundaries", doubles(analysis.phraseBoundaries))
            .put("firstBeat", analysis.firstBeat)
            .put("key", analysis.key)
            .put("keyConfidence", analysis.keyConfidence)
            .putNullable("audibleStartTime", analysis.audibleStartTime)
            .putNullable("pickupTime", analysis.pickupTime)
            .put("introEndTime", analysis.introEndTime)
            .put("contentEndTime", analysis.contentEndTime)
            .put("outroStartTime", analysis.outroStartTime)
            .put("mixInTime", analysis.mixInTime)
            .put("mixOutTime", analysis.mixOutTime)
            .put("mixInCandidates", candidates(analysis.mixInCandidates))
            .put("mixOutCandidates", candidates(analysis.mixOutCandidates))
            .put("energyCurve", curve(analysis.energyCurve))
            .put("lowEnergyCurve", curve(analysis.lowEnergyCurve))
            .put("vocalActivityMask", doubles(analysis.vocalActivityMask))
            .put("vocalProbability", analysis.vocalProbability)
    }

    private fun signature(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outgoingTrack: TransitionTrackInfo,
        incomingTrack: TransitionTrackInfo,
    ): String = buildString {
        append(outgoing.trackId.ifBlank { outgoingTrack.id })
        append(':').append((outgoing.bpm * 100).roundToInt())
        append(':').append(outgoing.key)
        append(':').append((outgoing.keyConfidence * 100).roundToInt())
        append(':').append((outgoing.mixOutTime * 10).roundToInt())
        append(':').append(outgoing.energyCurve.size)
        append("->")
        append(incoming.trackId.ifBlank { incomingTrack.id })
        append(':').append((incoming.bpm * 100).roundToInt())
        append(':').append(incoming.key)
        append(':').append((incoming.keyConfidence * 100).roundToInt())
        append(':').append((incoming.mixInTime * 10).roundToInt())
        append(':').append(incoming.energyCurve.size)
    }

    private fun trackDurationSeconds(
        analysis: TrackAnalysis,
        track: TransitionTrackInfo,
    ): Double = analysis.duration
        .takeIf { it.isFinite() && it > 0.0 }
        ?: (track.durationMs / 1000.0).takeIf { it > 0.0 }
        ?: 0.0

    private fun JSONObject.finiteDouble(name: String, fallback: Double): Double =
        optDouble(name, fallback).takeIf { it.isFinite() } ?: fallback

    private fun JSONObject.putNullable(name: String, value: Double?): JSONObject {
        if (value != null && value.isFinite()) put(name, value)
        return this
    }

    private fun doubles(values: List<Double>): JSONArray = JSONArray().apply {
        values.filter { it.isFinite() }.forEach { value -> put(value) }
    }

    private fun curve(values: List<EnergySample>): JSONArray = JSONArray().apply {
        values.forEach { point ->
            if (point.time.isFinite() && point.energy.isFinite()) {
                put(JSONObject().put("time", point.time).put("energy", point.energy))
            }
        }
    }

    private fun candidates(values: List<MixCandidate>): JSONArray = JSONArray().apply {
        values.forEach { point ->
            if (point.time.isFinite()) {
                put(
                    JSONObject()
                        .put("time", point.time)
                        .put("score", point.score)
                        .put("type", point.type),
                )
            }
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
