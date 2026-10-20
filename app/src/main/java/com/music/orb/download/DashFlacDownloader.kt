package com.music.orb.download

import com.music.orb.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.OutputStream
import java.io.StringReader
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.coroutineContext

/**
 * Saves the FLAC representation that the Orb player already uses via DASH.
 *
 * TIDAL delivers FLAC frames inside fragmented ISO-BMFF (MP4) segments. The
 * initialization segment followed by the media fragments is a lossless MP4
 * file, NOT a raw .flac. Neither the segments nor the container are transcoded.
 * The caller saves it as .m4a, verifies its real audio codec via Android's
 * extractor and only then advertises the download as Lossless.
 *
 * Do not treat an arbitrary MPD or AAC rendition as Lossless. Only explicitly
 * FLAC-labelled, non-encrypted SegmentTemplate audio representations qualify.
 */
internal object DashFlacDownloader {
    private const val MAX_SEGMENTS = 8_000
    private const val MAX_MANIFEST_BYTES = 1_000_000L
    private const val BUFFER_BYTES = 64 * 1024

    internal data class Plan(
        val initialization: String,
        val segments: List<String>,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
    )

    /** Pure parser: unit-testable without a network or Android UI. */
    internal fun plan(manifestUrl: String, xml: String): Plan? = runCatching {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) ||
            xml.contains("<!ENTITY", ignoreCase = true)
        ) return@runCatching null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android XML parser implementations differ in which security
            // flags they support; reject DTDs above even if one flag is absent.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val reps = doc.getElementsByTagNameNS("*", "Representation")
        val plans = mutableListOf<Plan>()
        for (i in 0 until reps.length) {
            val rep = reps.item(i) as? Element ?: continue
            val adaptation = rep.parentNode as? Element ?: continue
            val codec = rep.getAttribute("codecs").ifBlank { adaptation.getAttribute("codecs") }
            val id = rep.getAttribute("id")
            if (!codec.contains("flac", ignoreCase = true) &&
                !id.startsWith("FLAC", ignoreCase = true)
            ) continue
            if (adaptation.getAttribute("contentType").let { it.isNotBlank() && it != "audio" }) continue
            if (adaptation.getElementsByTagNameNS("*", "ContentProtection").length > 0) continue
            val template = directChild(rep, "SegmentTemplate") ?: directChild(adaptation, "SegmentTemplate") ?: continue
            val init = template.getAttribute("initialization")
            val media = template.getAttribute("media")
            if (init.isBlank() || media.isBlank()) continue
            val timeline = directChild(template, "SegmentTimeline") ?: continue
            val starts = mutableListOf<Long>()
            var time = 0L
            val children = timeline.childNodes
            for (n in 0 until children.length) {
                val s = children.item(n) as? Element ?: continue
                if (s.localName != "S" && s.tagName != "S") continue
                val duration = s.getAttribute("d").toLongOrNull()?.takeIf { it > 0 } ?: break
                s.getAttribute("t").toLongOrNull()?.let { time = it }
                val repetitions = s.getAttribute("r").toIntOrNull() ?: 0
                if (repetitions !in 0..MAX_SEGMENTS || starts.size + repetitions + 1 > MAX_SEGMENTS) {
                    starts.clear()
                    break
                }
                repeat(repetitions + 1) {
                    starts += time
                    time += duration
                }
            }
            if (starts.isEmpty()) continue
            val first = template.getAttribute("startNumber").toLongOrNull()?.takeIf { it >= 0 } ?: 1L
            // The real MPD can include relative BaseURL nodes; resolve each level.
            var base = URI(manifestUrl)
            for (parent in listOf(doc.documentElement, adaptation, rep)) {
                val baseNode = directChild(parent, "BaseURL")
                val value = baseNode?.textContent?.trim()
                if (!value.isNullOrBlank()) base = base.resolve(value)
            }
            fun expand(pattern: String, number: Long, timestamp: Long): String {
                val tokens = Regex("""\$(RepresentationID|Number(?:%0([1-9])d)?|Time)\$""")
                val replaced = tokens.replace(pattern) { match ->
                    when {
                        match.groupValues[1] == "RepresentationID" -> id
                        match.groupValues[1] == "Time" -> timestamp.toString()
                        else -> {
                            val digits = match.groupValues[2].toIntOrNull()
                            if (digits != null) number.toString().padStart(digits, '0') else number.toString()
                        }
                    }
                }
                return base.resolve(replaced.replace("$$", "$"))
                    .toString()
                    .takeIf { it.startsWith("https://", ignoreCase = true) }
                    ?: error("Non-HTTPS audio segment")
            }
            val parts = starts.mapIndexed { index, timestamp -> expand(media, first + index, timestamp) }
            val bitsFromId = Regex("FLAC,(\\d+),(\\d+)", RegexOption.IGNORE_CASE).find(id)
            val bits = bitsFromId?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: rep.getAttribute("bitsPerSample").toIntOrNull()
            val rate = bitsFromId?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: rep.getAttribute("audioSamplingRate").toIntOrNull()
            plans += Plan(expand(init, first, 0L), parts, rate, bits)
        }
        plans.maxWithOrNull(compareBy<Plan> { it.bitDepth ?: 0 }.thenBy { it.sampleRateHz ?: 0 })
    }.getOrNull()

    private fun directChild(element: Element, name: String): Element? {
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE &&
                (child.localName == name || child.nodeName == name)
            ) return child as Element
        }
        return null
    }

    suspend fun fetch(
        manifestUrl: String,
        headers: Map<String, String>,
        output: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(manifestUrl).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val xml = Http.client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Cannot retrieve Lossless manifest (HTTP ${response.code})")
            val body = response.body ?: error("Empty Lossless manifest")
            if ((body.contentLength()).let { it > MAX_MANIFEST_BYTES }) error("Lossless manifest too large")
            val text = body.string()
            if (text.length > MAX_MANIFEST_BYTES) error("Lossless manifest too large")
            text
        }
        val plan = plan(manifestUrl, xml) ?: error("Lossless DASH manifest has no FLAC segments")
        val urls = listOf(plan.initialization) + plan.segments
        // Segment count is a safe denominator when signed CDNs omit lengths.
        var written = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        urls.forEachIndexed { index, url ->
            coroutineContext.ensureActive()
            val segmentRequest = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            Http.client.newCall(segmentRequest).execute().use { response ->
                if (!response.isSuccessful) error("Lossless segment ${index + 1}/${urls.size} HTTP ${response.code}")
                val body = response.body ?: error("Missing Lossless audio segment")
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            onProgress(index + 1L, urls.size.toLong())
        }
        output.flush()
        if (written == 0L) error("Lossless audio returned no data")
        written
    }
}
