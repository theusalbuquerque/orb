package com.music.orb.playback.smart

import android.os.SystemClock
import android.util.Log
import com.music.orb.data.Http
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AutomixVersion
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thin network seam for Orb's remote Automix intelligence.
 *
 * The Render service performs the expensive decode/feature extraction and searches complete
 * A -> B transition recipes. Buffering, final safety validation, DSP execution and the actual
 * two-player handoff stay on the phone. Transition-family selection is server-authoritative:
 * the device may reject a malformed/technically impossible directive, but it never substitutes
 * another Automix style of its own. If the 2.5 backend is unavailable, playback advances
 * normally with NO_TRANSITION rather than disguising a generic Crossfade as Automix.
 */
/**
 * Full server-authored transition recipe. The phone performs only transport/timeline validation
 * before execution. Musical family/style choice belongs exclusively to the server.
 */
data class RemoteTransitionDirective(
    val style: TransitionStyle,
    val confidence: Double,
    val reason: String,
    val transitionStart: Double,
    val transitionEnd: Double,
    val incomingCueTime: Double,
    val incomingHandoffTime: Double,
    val outgoingPlaybackRate: Double,
    val incomingPlaybackRate: Double,
    val handoffFraction: Double,
    val bassSwap: Boolean,
    val bassSwapFraction: Double,
    val filterSweep: Double,
    val gainEnvelope: List<TransitionGainPoint>,
    val tempoEnvelope: List<TransitionTempoPoint> = emptyList(),
    val incomingPitchSemitones: Double = 0.0,
    val harmonicLockScore: Double = 0.0,
    val protectedOutgoing: Boolean = false,
    val outgoingReleaseTime: Double = 0.0,
    val incomingImpactTime: Double = 0.0,
    val planner: String = "",
    val curveCompatibility: Double = 0.0,
    val onsetCurveFit: Double = 0.0,
    val harmonicCurveFit: Double = 0.0,
    val spectralCurveFit: Double = 0.0,
    val beatPhaseFit: Double = 0.0,
    val beatPhaseErrorMs: Double = 0.0,
    val localTempoCompatibility: Double = 0.0,
    val outgoingLocalBpm: Double = 0.0,
    val incomingLocalBpm: Double = 0.0,
    val curveCueShiftMs: Double = 0.0,
    val curveAlignmentScore: Double = 0.0,
    val transitionBeats: Int = 0,
    val requestedTransitionBeats: Int = 0,
    val keyCompatibility: Double = 0.0,
    val tempoCompatibility: Double = 0.0,
    val phraseAlignment: Double = 0.0,
    val pairCompatibility: Double = 0.0,
    val energyCompatibility: Double = 0.0,
    val overlapVocalClash: Double = 0.0,
    val spanCompatibility: Double = 0.0,
    val outgoingAnchor: String = "",
    val incomingAnchor: String = "",
    val outgoingTransitionKey: String = "",
    val incomingTransitionKey: String = "",
    /** Explicit server verdict that no musical transition should be applied. */
    val blocked: Boolean = false,
    val serverAuthoritative: Boolean = true,
)

internal object RemoteAutomixClient {
    private const val TAG = "OrbRemoteAutomix"
    private const val BASE_URL = "https://orb-4mrh.onrender.com"
    private const val VERSION = 7
    private const val REQUIRED_PLANNER_REVISION = "mix-v8"
    private const val REQUIRED_ANALYSIS_SCHEMA = 4
    private const val MAX_REMOTE_AUDIO_BYTES = 24L * 1024L * 1024L
    private const val MAX_PLAN_CURVE_POINTS = 1800
    private const val FAILURE_COOLDOWN_MS = 60_000L
    private const val ENDPOINT_MISSING_COOLDOWN_MS = 10L * 60L * 1000L

    @Volatile private var confirmedAvailable = false
    @Volatile private var retryAfterMs = 0L

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Automix 2.5 is the only engine allowed to use the new remote planner.
     * Public Automix 2.0 remains local and unchanged.
     */
    fun isAvailable(): Boolean =
        AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
            AppSettings.automix25Available.value &&
            confirmedAvailable &&
            SystemClock.elapsedRealtime() >= retryAfterMs


    /**
     * Cheap startup probe. v5 is required because only v5 advertises the server-authoritative
     * planning contract. Older servers never trigger a local musical substitute.
     */
    fun probe(): Boolean {
        if (SystemClock.elapsedRealtime() < retryAfterMs) return false
        val request = Request.Builder()
            .url("$BASE_URL/api/automix/health")
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    endpointFailure(response.code)
                    return@use false
                }
                val root = JSONObject(response.body?.string().orEmpty())
                val serverVersion = root.optInt("version", 0)
                val advertised25 =
                    root.optString("automixVersion", "") == "2.5" ||
                        root.optString("analyzer", "") == "orb-remote-dsp-v7"
                val plannerRevision = root.optString("plannerRevision", "")
                val analysisSchema = root.optInt("analysisSchema", 0)
                val ok = root.optBoolean("ok", false) &&
                    serverVersion >= VERSION &&
                    advertised25 &&
                    plannerRevision == REQUIRED_PLANNER_REVISION &&
                    analysisSchema >= REQUIRED_ANALYSIS_SCHEMA
                confirmedAvailable = ok
                if (ok) {
                    retryAfterMs = 0L
                } else if (root.optBoolean("ok", false)) {
                    Log.d(
                        TAG,
                        "Remote Automix not ready: protocol=$serverVersion " +
                            "planner=$plannerRevision required=$REQUIRED_PLANNER_REVISION " +
                            "analysisSchema=$analysisSchema required=$REQUIRED_ANALYSIS_SCHEMA",
                    )
                }
                ok
            }
        }.onFailure { transientFailure(it) }.getOrDefault(false)
    }

    /**
     * Sends the immutable lightweight analysis rendition, never the Lossless playback file.
     * Analysis may still fall back locally; this does not grant the device authority to choose the
     * transition family. Musical planning remains server-only.
     */
    fun analyze(trackId: String, durationSeconds: Double, audioFile: File): TrackAnalysis? {
        if (!isAvailable() || !audioFile.isFile || audioFile.length() !in 1..MAX_REMOTE_AUDIO_BYTES) {
            return null
        }

        // A remote cache hit avoids re-uploading a track the service already knows.
        cachedAnalysis(trackId)?.let { return it }
        if (!isAvailable()) return null

        val audioType = "application/octet-stream".toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("track_id", trackId)
            .addFormDataPart("duration_seconds", durationSeconds.coerceAtLeast(0.0).toString())
            .addFormDataPart("version", VERSION.toString())
            .addFormDataPart("audio", audioFile.name, audioFile.asRequestBody(audioType))
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/api/automix/analyze")
            .header("Accept", "application/json")
            .post(body)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    requestFailure(response.code, contentSpecific = true)
                    return@use null
                }
                parseAnalysisEnvelope(JSONObject(response.body?.string().orEmpty()), trackId)
            }
        }.onFailure { transientFailure(it) }.getOrNull()
    }

    /**
     * Requests the server's complete A -> B recipe. The server searches all transition families
     * and returns the authoritative style, timing and choreography. The device only verifies that
     * the returned numbers can be executed safely on the current media timeline.
     */
    fun requestPlan(outgoing: TrackAnalysis, incoming: TrackAnalysis): RemoteTransitionDirective? {
        if (!isAvailable() || !outgoing.isUsable || !incoming.isUsable) return null
        // mix-v6 is curve-aware by contract. Never pretend it is active on a legacy/local-only
        // scalar analysis; TrackAnalyzer will replace those with schema-2 remote evidence.
        if (outgoing.analysisSchema < REQUIRED_ANALYSIS_SCHEMA ||
            incoming.analysisSchema < REQUIRED_ANALYSIS_SCHEMA
        ) return null
        val payload = JSONObject()
            .put("version", VERSION)
            .put("automixVersion", "2.5")
            .put("preview", true)
            .put("accountHash", AppSettings.automix25AccountHash.value)
            .put("outgoing", analysisSummary(outgoing))
            .put("incoming", analysisSummary(incoming))
        val request = Request.Builder()
            .url("$BASE_URL/api/automix/plan")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    requestFailure(response.code, contentSpecific = true)
                    return@use null
                }
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optInt("version", 0) < VERSION) return@use null
                val directive = parsePlan(root.optJSONObject("plan") ?: return@use null)
                if (directive != null) {
                    Log.d(
                        TAG,
                        "2.5 plan style=${directive.style} reason=${directive.reason} " +
                            "start=${directive.transitionStart} end=${directive.transitionEnd} " +
                            "cue=${directive.incomingCueTime} handoff=${directive.handoffFraction} " +
                            "rates=${directive.outgoingPlaybackRate}/${directive.incomingPlaybackRate} " +
                            "curve=${"%.2f".format(directive.curveCompatibility)} " +
                            "harm=${"%.2f".format(directive.harmonicCurveFit)} " +
                            "onset=${"%.2f".format(directive.onsetCurveFit)} " +
                            "phase=${"%.2f".format(directive.beatPhaseFit)}/${"%.0f".format(directive.beatPhaseErrorMs)}ms " +
                            "localBpm=${"%.1f".format(directive.outgoingLocalBpm)}/${"%.1f".format(directive.incomingLocalBpm)}",
                    )
                }
                directive
            }
        }.onFailure { transientFailure(it) }.getOrNull()
    }

    /** Compatibility seam for code/tests that only need the selected family. */
    fun chooseStyle(outgoing: TrackAnalysis, incoming: TrackAnalysis): TransitionStyle? =
        requestPlan(outgoing, incoming)?.style

    private fun parsePlan(plan: JSONObject): RemoteTransitionDirective? {
        if (!plan.optBoolean("serverAuthoritative", false)) return null
        val rawStyle = plan.optString("style").uppercase()
        val noTransition = rawStyle == "NO_TRANSITION"
        val style = if (noTransition) TransitionStyle.EQUAL_POWER else parseStyle(rawStyle) ?: return null
        val start = plan.optDouble("transitionStart", if (noTransition) 0.0 else Double.NaN)
        val end = plan.optDouble("transitionEnd", if (noTransition) start else Double.NaN)
        val cue = plan.optDouble("incomingCueTime", if (noTransition) 0.0 else Double.NaN)
        val handoff = plan.optDouble("incomingHandoffTime", if (noTransition) cue else Double.NaN)
        if (!start.isFinite() || !end.isFinite() || !cue.isFinite() || !handoff.isFinite()) return null
        return RemoteTransitionDirective(
            style = style,
            confidence = plan.optDouble("confidence", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            reason = plan.optString("reason", "remote-plan"),
            transitionStart = start.coerceAtLeast(0.0),
            transitionEnd = end.coerceAtLeast(0.0),
            incomingCueTime = cue.coerceAtLeast(0.0),
            incomingHandoffTime = handoff.coerceAtLeast(0.0),
            outgoingPlaybackRate = plan.optDouble("outgoingPlaybackRate", 1.0).takeIf { it.isFinite() } ?: 1.0,
            incomingPlaybackRate = plan.optDouble("incomingPlaybackRate", 1.0).takeIf { it.isFinite() } ?: 1.0,
            handoffFraction = plan.optDouble("handoffFraction", 0.5).takeIf { it.isFinite() }?.coerceIn(0.02, 0.995) ?: 0.5,
            bassSwap = plan.optBoolean("bassSwap", false),
            bassSwapFraction = plan.optDouble("bassSwapFraction", 0.7).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.7,
            filterSweep = plan.optDouble("filterSweep", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            gainEnvelope = plan.optGainEnvelope("gainEnvelope"),
            tempoEnvelope = plan.optTempoEnvelope("tempoEnvelope"),
            incomingPitchSemitones = plan.optDouble("incomingPitchSemitones", 0.0)
                .takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0,
            harmonicLockScore = plan.optDouble("harmonicLockScore", 0.0)
                .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            protectedOutgoing = plan.optBoolean("protectedOutgoing", false),
            outgoingReleaseTime = plan.optDouble("outgoingReleaseTime", 0.0).takeIf { it.isFinite() } ?: 0.0,
            incomingImpactTime = plan.optDouble("incomingImpactTime", 0.0).takeIf { it.isFinite() } ?: 0.0,
            planner = plan.optString("planner", ""),
            curveCompatibility = plan.optDouble("curveCompatibility", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            onsetCurveFit = plan.optDouble("onsetCurveFit", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            harmonicCurveFit = plan.optDouble("harmonicCurveFit", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            spectralCurveFit = plan.optDouble("spectralCurveFit", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            beatPhaseFit = plan.optDouble("beatPhaseFit", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            beatPhaseErrorMs = plan.optDouble("beatPhaseErrorMs", 0.0).takeIf { it.isFinite() } ?: 0.0,
            localTempoCompatibility = plan.optDouble("localTempoCompatibility", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            outgoingLocalBpm = plan.optDouble("outgoingLocalBpm", 0.0).takeIf { it.isFinite() } ?: 0.0,
            incomingLocalBpm = plan.optDouble("incomingLocalBpm", 0.0).takeIf { it.isFinite() } ?: 0.0,
            curveCueShiftMs = plan.optDouble("curveCueShiftMs", 0.0).takeIf { it.isFinite() } ?: 0.0,
            curveAlignmentScore = plan.optDouble("curveAlignmentScore", 0.0)
                .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            transitionBeats = plan.optInt("transitionBeats", 0).coerceAtLeast(0),
            requestedTransitionBeats = plan.optInt("requestedTransitionBeats", 0).coerceAtLeast(0),
            keyCompatibility = plan.optDouble("keyCompatibility", 0.0).takeIf { it.isFinite() } ?: 0.0,
            tempoCompatibility = plan.optDouble("tempoCompatibility", 0.0).takeIf { it.isFinite() } ?: 0.0,
            phraseAlignment = plan.optDouble("phraseAlignment", 0.0).takeIf { it.isFinite() } ?: 0.0,
            pairCompatibility = plan.optDouble("pairCompatibility", 0.0).takeIf { it.isFinite() } ?: 0.0,
            energyCompatibility = plan.optDouble("energyCompatibility", 0.0).takeIf { it.isFinite() } ?: 0.0,
            overlapVocalClash = plan.optDouble("overlapVocalClash", 0.0).takeIf { it.isFinite() } ?: 0.0,
            spanCompatibility = plan.optDouble("spanCompatibility", 0.0).takeIf { it.isFinite() } ?: 0.0,
            outgoingAnchor = plan.optString("outgoingAnchor", ""),
            incomingAnchor = plan.optString("incomingAnchor", ""),
            outgoingTransitionKey = plan.optString("outgoingTransitionKey", ""),
            incomingTransitionKey = plan.optString("incomingTransitionKey", ""),
            blocked = noTransition,
            serverAuthoritative = true,
        )
    }

    private fun parseStyle(raw: String): TransitionStyle? = when (raw.uppercase()) {
        "DJ_BLEND", "BLEND" -> TransitionStyle.DJ_BLEND
        "DJ_FILTER", "FILTER" -> TransitionStyle.DJ_FILTER
        "RUNWAY_BLEND", "RUNWAY" -> TransitionStyle.RUNWAY_BLEND
        "PHRASE_TAKEOVER", "TAKEOVER" -> TransitionStyle.PHRASE_TAKEOVER
        "EQ_SWAP" -> TransitionStyle.EQ_SWAP
        "PHRASE_CUT" -> TransitionStyle.PHRASE_CUT
        "CUT" -> TransitionStyle.CUT
        // Equal-power is the manual/simple Crossfade family, not Automix 2.5.
        // Reject outdated server recipes that try to send it.
        "GAPLESS" -> TransitionStyle.GAPLESS
        else -> null
    }

    /**
     * Cache-only analysis lookup for AutoPlay ordering. This never uploads or analyzes audio;
     * a cache miss remains a miss, so future-track DSP ordering is not violated.
     */
    fun cachedAnalysisForQueue(trackId: String): TrackAnalysis? {
        if (!isAvailable() || trackId.isBlank()) return null
        return cachedAnalysis(trackId)
    }

    private fun cachedAnalysis(trackId: String): TrackAnalysis? {
        val request = Request.Builder()
            .url("$BASE_URL/api/automix/analysis/${java.net.URLEncoder.encode(trackId, "UTF-8")}")
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    endpointFailure(response.code)
                    return@use null
                }
                parseAnalysisEnvelope(JSONObject(response.body?.string().orEmpty()), trackId)
            }
        }.onFailure { transientFailure(it) }.getOrNull()
    }

    private fun parseAnalysisEnvelope(root: JSONObject, trackId: String): TrackAnalysis? {
        val data = root.optJSONObject("analysis") ?: return null
        if (root.optInt("version", VERSION) < VERSION) return null
        if (data.optInt("analysisSchema", 0) < REQUIRED_ANALYSIS_SCHEMA) return null
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            analysisSchema = data.optInt("analysisSchema", 0),
            duration = data.optDoubleFinite("duration"),
            bpm = data.optDoubleFinite("bpm"),
            beatInterval = data.optDoubleFinite("beatInterval"),
            beatConfidence = data.optDoubleFinite("beatConfidence").coerceIn(0.0, 1.0),
            beats = data.optDoubleList("beats"),
            tempoCurve = data.optTempo("tempoCurve"),
            downbeats = data.optDoubleList("downbeats"),
            phraseBoundaries = data.optDoubleList("phraseBoundaries"),
            firstBeat = data.optDoubleFinite("firstBeat"),
            key = data.optString("key", ""),
            keyConfidence = data.optDoubleFinite("keyConfidence").coerceIn(0.0, 1.0),
            audibleStartTime = data.optNullableDouble("audibleStartTime"),
            pickupTime = data.optNullableDouble("pickupTime"),
            introEndTime = data.optDoubleFinite("introEndTime"),
            contentEndTime = data.optDoubleFinite("contentEndTime"),
            outroStartTime = data.optDoubleFinite("outroStartTime"),
            mixInTime = data.optDoubleFinite("mixInTime"),
            mixOutTime = data.optDoubleFinite("mixOutTime"),
            mixInCandidates = data.optCandidates("mixInCandidates"),
            mixOutCandidates = data.optCandidates("mixOutCandidates"),
            energyCurve = data.optEnergy("energyCurve"),
            lowEnergyCurve = data.optEnergy("lowEnergyCurve"),
            midEnergyCurve = data.optEnergy("midEnergyCurve"),
            highEnergyCurve = data.optEnergy("highEnergyCurve"),
            brightnessCurve = data.optEnergy("brightnessCurve"),
            onsetCurve = data.optEnergy("onsetCurve"),
            chromaCurve = data.optChroma("chromaCurve"),
            vocalActivityMask = data.optDoubleList("vocalActivityMask"),
            vocalProbability = data.optDoubleFinite("vocalProbability").coerceIn(0.0, 1.0),
        )
        return analysis.takeIf { it.isUsable && it.bpm in 40.0..220.0 }
    }

    /** First sustained vocal onset on the shared 500 ms analysis grid. */
    private fun firstSustainedVocalTime(a: TrackAnalysis): Double? {
        val mask = a.vocalActivityMask
        val curve = a.energyCurve
        if (mask.isEmpty() || mask.size != curve.size || curve.size < 3) return null
        var streak = 0
        for (index in mask.indices) {
            val vocal = mask[index]
            val time = curve[index].time
            if (vocal.isFinite() && time.isFinite() && vocal >= VOCAL_ACTIVE_THRESHOLD) {
                streak += 1
                if (streak >= 3) return curve[index - 2].time
            } else {
                streak = 0
            }
        }
        return null
    }

    /**
     * Musical evidence for the remote candidate search. Audio never leaves this path: the server
     * receives derived curves/landmarks and can search timing + style alternatives, while the
     * phone keeps final veto authority.
     */
    private fun analysisSummary(a: TrackAnalysis): JSONObject {
        val audibleStart = audibleStartOf(a)
        val duration = a.duration.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val firstVocal = firstSustainedVocalTime(a)
        val inferredIntroEnd = firstVocal
            ?: listOf(a.mixInTime, a.introEndTime)
                .filter { it.isFinite() && it > audibleStart }
                .maxOrNull()
        val introRunway = ((inferredIntroEnd ?: audibleStart) - audibleStart).coerceAtLeast(0.0)
        val introProbeEnd = (inferredIntroEnd ?: (audibleStart + 12.0))
            .coerceAtMost(if (duration > 0.0) duration else audibleStart + 12.0)
        val openingProbeEnd = (audibleStart + 12.0)
            .coerceAtMost(if (duration > 0.0) duration else audibleStart + 12.0)
        val contentEnd = a.contentEndTime
            .takeIf { it.isFinite() && it > audibleStart }
            ?: duration
        val tailStart = maxOf(
            audibleStart,
            a.outroStartTime.takeIf { it.isFinite() && it > 0.0 } ?: (contentEnd - 12.0),
            contentEnd - 16.0,
        )
        val introVocal = vocalActivityBetween(a, audibleStart, introProbeEnd)
            ?: a.vocalProbability
        val openingVocal = vocalActivityBetween(a, audibleStart, openingProbeEnd)
            ?: a.vocalProbability
        val tailVocal = vocalActivityBetween(a, tailStart, contentEnd)
            ?: a.vocalProbability

        fun meanCurve(curve: List<EnergySample>, from: Double, until: Double): Double {
            val values = curve.asSequence()
                .filter { it.time.isFinite() && it.energy.isFinite() && it.time >= from && it.time < until }
                .map { it.energy.coerceAtLeast(0.0) }
                .toList()
            return if (values.isEmpty()) 0.0 else values.average()
        }
        val introEnergy = meanCurve(a.energyCurve, audibleStart, introProbeEnd)
        val tailEnergy = meanCurve(a.energyCurve, tailStart, contentEnd)
        val introLowEnergy = meanCurve(a.lowEnergyCurve, audibleStart, introProbeEnd)
        val tailLowEnergy = meanCurve(a.lowEnergyCurve, tailStart, contentEnd)
        val introActivity = musicalActivityBetween(a, audibleStart, introProbeEnd) ?: 0.0
        val openingActivity = musicalActivityBetween(a, audibleStart, openingProbeEnd) ?: 0.0
        val tailActivity = musicalActivityBetween(a, tailStart, contentEnd) ?: 0.0

        return JSONObject()
            .put("trackId", a.trackId)
            .put("analysisSchema", a.analysisSchema)
            .put("duration", a.duration)
            .put("bpm", a.bpm)
            .put("beatInterval", a.beatInterval)
            .put("beatConfidence", a.beatConfidence)
            .put("key", a.key)
            .put("keyConfidence", a.keyConfidence)
            .put("vocalProbability", a.vocalProbability)
            .put("mixInTime", a.mixInTime)
            .put("mixOutTime", a.mixOutTime)
            .put("introEndTime", a.introEndTime)
            .put("outroStartTime", a.outroStartTime)
            .put("audibleStartTime", audibleStart)
            .put("contentEndTime", contentEnd)
            .put("firstSustainedVocalTime", firstVocal ?: JSONObject.NULL)
            .put("introRunwaySeconds", introRunway)
            .put("introVocalProbability", introVocal.coerceIn(0.0, 1.0))
            .put("openingVocalProbability", openingVocal.coerceIn(0.0, 1.0))
            .put("tailVocalProbability", tailVocal.coerceIn(0.0, 1.0))
            .put("introEnergy", introEnergy.coerceIn(0.0, 1.0))
            .put("tailEnergy", tailEnergy.coerceIn(0.0, 1.0))
            .put("introLowEnergy", introLowEnergy.coerceIn(0.0, 1.0))
            .put("tailLowEnergy", tailLowEnergy.coerceIn(0.0, 1.0))
            .put("introActivity", introActivity.coerceIn(0.0, 1.0))
            .put("openingActivity", openingActivity.coerceIn(0.0, 1.0))
            .put("tailActivity", tailActivity.coerceIn(0.0, 1.0))
            .put("energyStart", a.energyCurve.firstOrNull()?.energy ?: 0.0)
            .put("energyEnd", a.energyCurve.lastOrNull()?.energy ?: 0.0)
            // The plan endpoint can now reason from the same dense evidence even when this
            // particular analysis came from the phone instead of the server cache.
            .put("energyCurve", a.energyCurve.toJsonEnergy())
            .put("lowEnergyCurve", a.lowEnergyCurve.toJsonEnergy())
            .put("midEnergyCurve", a.midEnergyCurve.toJsonEnergy())
            .put("highEnergyCurve", a.highEnergyCurve.toJsonEnergy())
            .put("brightnessCurve", a.brightnessCurve.toJsonEnergy())
            .put("onsetCurve", a.onsetCurve.toJsonEnergy())
            .put("chromaCurve", a.chromaCurve.toJsonChroma())
            .put("tempoCurve", a.tempoCurve.toJsonTempo())
            .put("vocalActivityMask", a.vocalActivityMask.toJsonDoubles())
            .put("beats", a.beats.toJsonDoubles())
            .put("downbeats", a.downbeats.toJsonDoubles())
            .put("phraseBoundaries", a.phraseBoundaries.toJsonDoubles())
            .put("mixInCandidates", a.mixInCandidates.toJsonCandidates())
            .put("mixOutCandidates", a.mixOutCandidates.toJsonCandidates())
    }

    private fun List<EnergySample>.toJsonEnergy(): JSONArray = JSONArray().also { array ->
        take(MAX_PLAN_CURVE_POINTS).forEach { point ->
            if (point.time.isFinite() && point.energy.isFinite()) {
                array.put(JSONObject().put("time", point.time).put("energy", point.energy))
            }
        }
    }

    private fun List<TempoSample>.toJsonTempo(): JSONArray = JSONArray().also { array ->
        take(MAX_PLAN_CURVE_POINTS).forEach { point ->
            if (point.time.isFinite() && point.bpm.isFinite() && point.confidence.isFinite()) {
                array.put(
                    JSONObject()
                        .put("time", point.time)
                        .put("bpm", point.bpm)
                        .put("confidence", point.confidence),
                )
            }
        }
    }

    private fun List<ChromaSample>.toJsonChroma(): JSONArray = JSONArray().also { array ->
        take(MAX_PLAN_CURVE_POINTS).forEach { point ->
            if (point.time.isFinite() && point.chroma.size == 12 && point.chroma.all { it.isFinite() }) {
                array.put(
                    JSONObject()
                        .put("time", point.time)
                        .put("chroma", JSONArray(point.chroma)),
                )
            }
        }
    }

    private fun List<Double>.toJsonDoubles(): JSONArray = JSONArray().also { array ->
        take(MAX_PLAN_CURVE_POINTS).forEach { value -> if (value.isFinite()) array.put(value) }
    }

    private fun List<MixCandidate>.toJsonCandidates(): JSONArray = JSONArray().also { array ->
        take(16).forEach { point ->
            if (point.time.isFinite() && point.score.isFinite()) {
                array.put(JSONObject().put("time", point.time).put("score", point.score).put("type", point.type))
            }
        }
    }

    private fun requestFailure(code: Int, contentSpecific: Boolean) {
        // Malformed/unsupported audio is a property of this track, not proof that the Automix
        // service is down. Keep the capability live so the next pair can still use it.
        if (contentSpecific && (code == 400 || code == 413 || code == 422)) {
            Log.d(TAG, "Remote Automix refused this request (HTTP $code); local pair fallback")
            return
        }
        endpointFailure(code)
    }

    private fun endpointFailure(code: Int) {
        confirmedAvailable = false
        retryAfterMs = SystemClock.elapsedRealtime() +
            if (code == 404 || code == 405 || code == 501) ENDPOINT_MISSING_COOLDOWN_MS
            else FAILURE_COOLDOWN_MS
        Log.d(TAG, "Remote Automix unavailable (HTTP $code); local fallback active")
    }

    private fun transientFailure(error: Throwable) {
        confirmedAvailable = false
        retryAfterMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
        Log.d(TAG, "Remote Automix unavailable; local fallback active: ${error.message}")
    }

    private fun JSONObject.optDoubleFinite(name: String): Double =
        optDouble(name, 0.0).takeIf { it.isFinite() } ?: 0.0

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun JSONObject.optDoubleList(name: String): List<Double> =
        optJSONArray(name)?.toDoubleList().orEmpty()

    private fun JSONArray.toDoubleList(): List<Double> = buildList(length()) {
        for (i in 0 until length()) {
            optDouble(i, Double.NaN).takeIf { it.isFinite() }?.let(::add)
        }
    }

    private fun JSONObject.optEnergy(name: String): List<EnergySample> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (i in 0 until values.length()) {
                val point = values.optJSONObject(i) ?: continue
                val time = point.optDouble("time", Double.NaN)
                val energy = point.optDouble("energy", Double.NaN)
                if (time.isFinite() && energy.isFinite()) add(EnergySample(time, energy))
            }
        }
    }

    private fun JSONObject.optTempo(name: String): List<TempoSample> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (i in 0 until values.length()) {
                val point = values.optJSONObject(i) ?: continue
                val time = point.optDouble("time", Double.NaN)
                val bpm = point.optDouble("bpm", Double.NaN)
                val confidence = point.optDouble("confidence", Double.NaN)
                if (time.isFinite() && bpm.isFinite() && confidence.isFinite()) {
                    add(TempoSample(time, bpm, confidence.coerceIn(0.0, 1.0)))
                }
            }
        }
    }

    private fun JSONObject.optChroma(name: String): List<ChromaSample> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (i in 0 until values.length()) {
                val point = values.optJSONObject(i) ?: continue
                val time = point.optDouble("time", Double.NaN)
                val vector = point.optJSONArray("chroma")?.toDoubleList().orEmpty()
                if (time.isFinite() && vector.size == 12) add(ChromaSample(time, vector))
            }
        }
    }

    private fun JSONObject.optGainEnvelope(name: String): List<TransitionGainPoint> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (i in 0 until values.length()) {
                val point = values.optJSONObject(i) ?: continue
                val progress = point.optDouble("progress", Double.NaN)
                val incoming = point.optDouble("incomingGain", Double.NaN)
                val outgoing = point.optDouble("outgoingGain", Double.NaN)
                if (progress.isFinite() && incoming.isFinite() && outgoing.isFinite()) {
                    add(
                        TransitionGainPoint(
                            progress = progress.coerceIn(0.0, 1.0),
                            incomingGain = incoming.coerceIn(0.0, 1.0),
                            outgoingGain = outgoing.coerceIn(0.0, 1.0),
                        ),
                    )
                }
            }
        }.sortedBy { it.progress }
    }

    private fun JSONObject.optTempoEnvelope(name: String): List<TransitionTempoPoint> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (i in 0 until values.length()) {
                val point = values.optJSONObject(i) ?: continue
                val progress = point.optDouble("progress", Double.NaN)
                val outgoingRate = point.optDouble("outgoingRate", Double.NaN)
                val incomingRate = point.optDouble("incomingRate", Double.NaN)
                val outgoingBpm = point.optDouble("outgoingBpm", Double.NaN)
                val incomingBpm = point.optDouble("incomingBpm", Double.NaN)
                val confidence = point.optDouble("confidence", 0.0)
                if (
                    progress.isFinite() &&
                    outgoingRate.isFinite() &&
                    incomingRate.isFinite() &&
                    outgoingBpm.isFinite() &&
                    incomingBpm.isFinite()
                ) {
                    add(
                        TransitionTempoPoint(
                            progress = progress.coerceIn(0.0, 1.0),
                            outgoingRate = outgoingRate.coerceIn(0.94, 1.06),
                            incomingRate = incomingRate.coerceIn(0.94, 1.06),
                            outgoingBpm = outgoingBpm.coerceAtLeast(0.0),
                            incomingBpm = incomingBpm.coerceAtLeast(0.0),
                            confidence = confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                        ),
                    )
                }
            }
        }.sortedBy { it.progress }
    }

    private fun JSONObject.optCandidates(name: String): List<MixCandidate> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList(values.length()) {
            for (i in 0 until values.length()) {
                val point = values.optJSONObject(i) ?: continue
                val time = point.optDouble("time", Double.NaN)
                val score = point.optDouble("score", Double.NaN)
                if (time.isFinite() && score.isFinite()) {
                    add(MixCandidate(time, score, point.optString("type", "remote")))
                }
            }
        }
    }
}