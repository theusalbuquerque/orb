package com.music.orb.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Session-only Wi-Fi endpoint. No paths, source credentials or arbitrary URLs are accepted. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CastAudioServer(context: Context, private val factory: DataSource.Factory) : AutoCloseable {
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val address = network.allNetworks.firstNotNullOfOrNull { candidate ->
        if (network.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            network.getLinkProperties(candidate)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
        else null
    } ?: error("CAST_WIFI_REQUIRED")
    private val server = ServerSocket(0, 8, address)
    private val workers = Executors.newFixedThreadPool(4)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val sources = ConcurrentHashMap<String, Uri>()
    private val types = ConcurrentHashMap<String, String>()
    private val directories = ConcurrentHashMap<String, Uri>()
    private fun local(path: String) = "http://${address.hostAddress}:${server.localPort}$path"
    private fun resolve(base: Uri, reference: String): Uri =
        Uri.parse(java.net.URI(base.toString()).resolve(reference).toString())
    private fun directory(base: Uri): String {
        val path = "/" + UUID.randomUUID().toString().replace("-", "") + "/"
        directories[path] = base
        return local(path)
    }
    private fun adaptive(type: String) = type.contains("mpegurl", true) || type.contains("dash", true)
    private fun manifest(uri: Uri, type: String): ByteArray {
        val source = factory.createDataSource()
        val bytes = java.io.ByteArrayOutputStream()
        var base = uri
        try {
            source.open(DataSpec.Builder().setUri(uri).build())
            base = source.uri ?: uri
            val buffer = ByteArray(16384)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (bytes.size() + read > 4 * 1024 * 1024) error("CAST_MANIFEST_TOO_LARGE")
                bytes.write(buffer, 0, read)
            }
        } finally { source.close() }
        val text = bytes.toString("UTF-8")
        if (type.contains("mpegurl", true)) {
            return text.lineSequence().joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    !line.startsWith("#") -> publish(resolve(base, line.trim()))
                    else -> Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                        "URI=\"${publish(resolve(base, match.groupValues[1]))}\""
                    }
                }
            }.toByteArray(Charsets.UTF_8)
        }
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "CAST_MANIFEST_XML" }
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        val doc = builder.parse(java.io.ByteArrayInputStream(bytes.toByteArray()))
        fun rewrite(element: org.w3c.dom.Element, inherited: Uri) {
            val children = (0 until element.childNodes.length).mapNotNull {
                element.childNodes.item(it) as? org.w3c.dom.Element
            }
            val bases = children.filter { (it.localName ?: it.tagName) == "BaseURL" }
            val effective = bases.firstOrNull()?.textContent?.trim()?.let { resolve(inherited, it) } ?: inherited
            bases.forEach {
                val resolved = resolve(inherited, it.textContent.trim())
                it.textContent = if (resolved.path?.endsWith("/") == true) directory(resolved) else publish(resolved)
            }
            // Explicit segment templates stay relative to an opaque local directory endpoint.
            for (attribute in listOf("media", "initialization", "sourceURL", "index")) {
                if (element.hasAttribute(attribute)) {
                    val ref = element.getAttribute(attribute)
                    if (ref.isNotEmpty()) {
                        val split = ref.lastIndexOf('/')
                        val parent = resolve(effective, ref.substring(0, split + 1))
                        element.setAttribute(attribute, directory(parent) + ref.substring(split + 1))
                    }
                }
            }
            children.filter { (it.localName ?: it.tagName) != "BaseURL" }.forEach { rewrite(it, effective) }
        }
        rewrite(doc.documentElement, base)
        if ((0 until doc.documentElement.childNodes.length).none {
                val node = doc.documentElement.childNodes.item(it)
                node is org.w3c.dom.Element && (node.localName ?: node.tagName) == "BaseURL"
            }) {
            val node = doc.createElementNS(doc.documentElement.namespaceURI, "BaseURL")
            node.textContent = directory(resolve(base, "."))
            doc.documentElement.insertBefore(node, doc.documentElement.firstChild)
        }
        val out = java.io.ByteArrayOutputStream()
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
            javax.xml.transform.dom.DOMSource(doc), javax.xml.transform.stream.StreamResult(out))
        return out.toByteArray()
    }
    @Volatile private var closed = false
    init {
        Thread({
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if(sockets.size>=8) {socket.close();continue}
                sockets.add(socket)
                workers.execute {
                    runCatching { serve(socket) }.onFailure {
                        android.util.Log.w("OrbCast", "Audio endpoint failed", it)
                    }
                    sockets.remove(socket); runCatching { socket.close() }
                }
            }
        }, "orb-cast-audio").apply { isDaemon=true; start() }
    }
    fun publish(uri: Uri, type: String? = null): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        sources["/$token"] = uri
        if (type != null) types[uri.toString()] = type
        return "http://${address.hostAddress}:${server.localPort}/$token"
    }
    fun contentType(uri: Uri, fallback: String): String {
        types[uri.toString()]?.let { return it }
        val source=factory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(uri).build())
            val declared=source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type",true) }
                ?.value?.firstOrNull()?.substringBefore(';')
            if(declared!=null && declared!="application/octet-stream") declared else {
                val bytes=ByteArray(12)
                var count=0
                while(count<bytes.size) {
                    val read=source.read(bytes,count,bytes.size-count)
                    if(read<=0) break
                    count+=read
                }
                val signature=String(bytes,0,count,Charsets.ISO_8859_1)
                when {
                    signature.startsWith("#EXTM3U") -> "application/vnd.apple.mpegurl"
                    signature.startsWith("fLaC") -> "audio/flac"
                    signature.startsWith("OggS") -> "audio/ogg"
                    signature.startsWith("RIFF") -> "audio/wav"
                    signature.startsWith("ID3") -> "audio/mpeg"
                    count>=8 && signature.substring(4,8)=="ftyp" -> "audio/mp4"
                    count>=4 && bytes[0]==0x1a.toByte() && bytes[1]==0x45.toByte() -> "audio/webm"
                    else -> fallback
                }
            }
        } finally { source.close() }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout=15000
        val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val first=input.readLine()?.split(' ') ?: return
        val output=socket.getOutputStream()
        val headers=mutableMapOf<String,String>()
        var count=0
        while(true) {
            val line=input.readLine() ?: return
            if(line.isEmpty()) break
            if(++count>50 || line.length>8192) return
            val colon=line.indexOf(':')
            if(colon>0) headers[line.substring(0,colon).lowercase()]=line.substring(colon+1).trim()
        }
        fun error(code: String) { output.write("HTTP/1.1 $code\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        val path = first.getOrNull(1) ?: return
        val uri = sources[path] ?: directories.entries.firstOrNull { path.startsWith(it.key) }
            ?.let { resolve(it.value, path.removePrefix(it.key)) }
            ?: run { error("404 Not Found"); return }
        val method=first[0]
        if(method=="OPTIONS") {
            output.write("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        if(method !in listOf("GET","HEAD")) { error("405 Method Not Allowed"); return }
        val declared = types[uri.toString()] ?: contentType(uri, "application/octet-stream")
        if (adaptive(declared)) {
            val body = manifest(uri, declared)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: $declared\r\n" +
                "Access-Control-Allow-Origin: *\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            if (method != "HEAD") output.write(body)
            output.flush()
            return
        }
        val range=headers["range"]
        val match=range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        if(range!=null && match==null) { error("416 Range Not Satisfiable");return }
        val start=match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end=match?.groupValues?.get(2)?.toLongOrNull()
        if(end!=null && end<start) {error("416 Range Not Satisfiable");return}
        val source=factory.createDataSource()
        try {
            val available=source.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
            val length=if(end!=null) {
                if(available==C.LENGTH_UNSET.toLong()) end-start+1 else minOf(available,end-start+1)
            } else available
            val contentType=source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type",true) }?.value?.firstOrNull() ?: "application/octet-stream"
            val partial=range!=null
            val header=buildString {
                append("HTTP/1.1 ${if(partial) "206 Partial Content" else "200 OK"}\r\n")
                append("Content-Type: $contentType\r\nAccept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\nConnection: close\r\n")
                if(length>=0) append("Content-Length: $length\r\n")
                if(partial && length>=0) append("Content-Range: bytes $start-${start+length-1}/${if(available>=0) (start+available).toString() else "*"}\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray(Charsets.US_ASCII))
            if(method=="HEAD") return
            val buffer=ByteArray(32768)
            var remaining=length
            while(!closed && (remaining>0 || remaining==C.LENGTH_UNSET.toLong())) {
                val read=source.read(buffer,0,if(remaining<0) buffer.size else minOf(remaining,buffer.size.toLong()).toInt())
                if(read<0) break
                output.write(buffer,0,read)
                if(remaining>=0) remaining-=read
            }
            output.flush()
        } finally { source.close() }
    }
    override fun close() {
        closed=true
        runCatching {server.close()}
        sockets.forEach {runCatching {it.close()}}
        workers.shutdownNow()
        sources.clear(); types.clear(); directories.clear()
    }
}
