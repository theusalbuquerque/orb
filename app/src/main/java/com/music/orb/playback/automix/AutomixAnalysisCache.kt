package com.music.orb.playback.automix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import com.music.orb.playback.SmallLruMap

/** Small versioned disk cache for locally-computed musical analysis. */
class AutomixAnalysisCache(context: Context) {
    private val directory = File(context.cacheDir, "automix-analysis-v${AutomixAnalysis.CURRENT_VERSION}").apply { mkdirs() }
    private val memory = SmallLruMap<String, AutomixAnalysis>(MEMORY_ENTRIES)

    fun get(mediaId: String): AutomixAnalysis? {
        memory[mediaId]?.let { return it }
        val file = fileFor(mediaId)
        if (!file.isFile) return null
        val restored = runCatching { decode(JSONObject(file.readText())) }.getOrNull()
            ?.takeIf { it.version == AutomixAnalysis.CURRENT_VERSION && it.mediaId == mediaId }
        if (restored != null) memory[mediaId] = restored
        return restored
    }

    fun put(analysis: AutomixAnalysis) {
        memory[analysis.mediaId] = analysis
        runCatching {
            val tmp = File(directory, fileFor(analysis.mediaId).name + ".tmp")
            tmp.writeText(encode(analysis).toString())
            if (!tmp.renameTo(fileFor(analysis.mediaId))) {
                fileFor(analysis.mediaId).writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun fileFor(mediaId: String): File = File(directory, sha256(mediaId) + ".json")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun encode(a: AutomixAnalysis) = JSONObject().apply {
        put("mediaId", a.mediaId)
        put("version", a.version)
        put("analyzedAtMs", a.analyzedAtMs)
        put("analyzedDurationMs", a.analyzedDurationMs)
        put("completeAnalysis", a.completeAnalysis)
        put("tempoBpm", a.tempoBpm.toDouble())
        put("tempoConfidence", a.tempoConfidence.toDouble())
        put("beatModelConfidence", a.beatModelConfidence.toDouble())
        put("firstBeatMs", a.firstBeatMs)
        put("beatIntervalMs", a.beatIntervalMs.toDouble())
        put("beatsMs", JSONArray(a.beatsMs))
        put("barsMs", JSONArray(a.barsMs))
        put("tatumsMs", JSONArray(a.tatumsMs))
        put("tempoCurveBpm", JSONArray(a.tempoCurveBpm))
        put("keyPitchClass", a.keyPitchClass ?: JSONObject.NULL)
        put("minorMode", a.minorMode ?: JSONObject.NULL)
        put("keyConfidence", a.keyConfidence.toDouble())
        put("loudnessCurve", JSONArray(a.loudnessCurve))
        put("energyCurve", JSONArray(a.energyCurve))
        put("vocalProbabilityCurve", JSONArray(a.vocalProbabilityCurve))
        put("vocalModelConfidence", a.vocalModelConfidence.toDouble())
        put("sections", JSONArray().apply { a.sections.forEach { put(encodeSection(it)) } })
        put("phrases", JSONArray().apply { a.phrases.forEach { put(encodePhrase(it)) } })
        put("introCandidatesMs", JSONArray(a.introCandidatesMs))
        put("outroCandidatesMs", JSONArray(a.outroCandidatesMs))
        put("dropCandidatesMs", JSONArray(a.dropCandidatesMs))
    }

    private fun encodeSection(s: AutomixSection) = JSONObject().apply {
        put("startMs", s.startMs); put("endMs", s.endMs); put("energy", s.energy.toDouble())
        put("tempoBpm", s.tempoBpm.toDouble()); put("tempoConfidence", s.tempoConfidence.toDouble())
        put("vocalProbability", s.vocalProbability.toDouble()); put("isDrop", s.isDrop)
    }

    private fun encodePhrase(p: AutomixPhrase) = JSONObject().apply {
        put("startMs", p.startMs); put("endMs", p.endMs); put("energy", p.energy.toDouble())
        put("vocalProbability", p.vocalProbability.toDouble())
    }

    private fun decode(o: JSONObject): AutomixAnalysis = AutomixAnalysis(
        mediaId = o.getString("mediaId"),
        version = o.getInt("version"),
        analyzedAtMs = o.getLong("analyzedAtMs"),
        analyzedDurationMs = o.getLong("analyzedDurationMs"),
        completeAnalysis = o.optBoolean("completeAnalysis", false),
        tempoBpm = o.getDouble("tempoBpm").toFloat(),
        tempoConfidence = o.getDouble("tempoConfidence").toFloat(),
        beatModelConfidence = o.optDouble("beatModelConfidence", 0.0).toFloat(),
        firstBeatMs = o.getLong("firstBeatMs"),
        beatIntervalMs = o.getDouble("beatIntervalMs").toFloat(),
        beatsMs = o.getJSONArray("beatsMs").toLongList(),
        barsMs = o.getJSONArray("barsMs").toLongList(),
        tatumsMs = o.optJSONArray("tatumsMs")?.toLongList().orEmpty(),
        tempoCurveBpm = o.optJSONArray("tempoCurveBpm")?.toFloatList().orEmpty(),
        keyPitchClass = if (o.isNull("keyPitchClass")) null else o.getInt("keyPitchClass"),
        minorMode = if (o.isNull("minorMode")) null else o.getBoolean("minorMode"),
        keyConfidence = o.getDouble("keyConfidence").toFloat(),
        loudnessCurve = o.getJSONArray("loudnessCurve").toFloatList(),
        energyCurve = o.getJSONArray("energyCurve").toFloatList(),
        vocalProbabilityCurve = o.optJSONArray("vocalProbabilityCurve")?.toFloatList().orEmpty(),
        vocalModelConfidence = o.optDouble("vocalModelConfidence", 0.0).toFloat(),
        sections = o.optJSONArray("sections")?.toSections().orEmpty(),
        phrases = o.optJSONArray("phrases")?.toPhrases().orEmpty(),
        introCandidatesMs = o.getJSONArray("introCandidatesMs").toLongList(),
        outroCandidatesMs = o.optJSONArray("outroCandidatesMs")?.toLongList().orEmpty(),
        dropCandidatesMs = o.optJSONArray("dropCandidatesMs")?.toLongList().orEmpty(),
    )

    private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }
    private fun JSONArray.toFloatList(): List<Float> = List(length()) { getDouble(it).toFloat() }
    private fun JSONArray.toSections(): List<AutomixSection> = List(length()) { i ->
        getJSONObject(i).let { o -> AutomixSection(o.getLong("startMs"), o.getLong("endMs"), o.getDouble("energy").toFloat(), o.getDouble("tempoBpm").toFloat(), o.getDouble("tempoConfidence").toFloat(), o.getDouble("vocalProbability").toFloat(), o.optBoolean("isDrop", false)) }
    }
    private fun JSONArray.toPhrases(): List<AutomixPhrase> = List(length()) { i ->
        getJSONObject(i).let { o -> AutomixPhrase(o.getLong("startMs"), o.getLong("endMs"), o.getDouble("energy").toFloat(), o.getDouble("vocalProbability").toFloat()) }
    }

    private companion object {
        const val MEMORY_ENTRIES = 12
    }
}
