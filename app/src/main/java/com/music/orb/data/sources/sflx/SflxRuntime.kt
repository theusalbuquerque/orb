package com.music.orb.data.sources.sflx

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.orb.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Compatibility host for SpotiFLAC .sflx JavaScript extensions.
 *
 * SFLX code stays inside QuickJS. The only Android capabilities exported to it
 * are the ones declared in manifest.json: network, key/value storage and the
 * extension's own private file sandbox. Network redirects are re-validated so
 * an allowed host cannot bounce the extension to an undeclared one.
 */
internal class SflxRuntime(
    private val context: Context,
    private val providerId: String = SflxPackageManager.TIDAL_PROVIDER_ID,
) {
    companion object {
        private const val TAG = "SflxRuntime"
        private const val MAX_HTTP_BODY = 4 * 1024 * 1024
        private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
        private const val MAX_JS_BINARY_BYTES = 64L * 1024L * 1024L
        /** Keep this in lockstep with the SpotiFLAC Mobile host contract. */
        private val SUPPORTED_RUNTIME_FEATURES = mapOf(
            "signedsession" to 3,
            "sessionrefresh" to 1,
            "sessiongrant" to 1,
            "downloadsegments" to 1,
            "patternedfiletransform" to 1,
            "preparedcontext" to 1,
        )

        internal fun unsupportedFeatures(manifest: SflxManifest): List<String> =
            manifest.requiredRuntimeFeatures.filter { feature ->
                val name = feature.substringBefore('@').trim().lowercase(Locale.US)
                val requestedVersion = feature.substringAfter('@', "1").trim().toIntOrNull() ?: Int.MAX_VALUE
                val supportedVersion = SUPPORTED_RUNTIME_FEATURES[name]
                supportedVersion == null || requestedVersion > supportedVersion ||
                    ((name == "signedsession" || name == "sessiongrant" || name == "sessionrefresh") &&
                        manifest.signedSession == null)
            }
    }

    data class RuntimeTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val coverUrl: String?,
        val explicit: Boolean?,
        val releaseYear: Int?,
        val quality: String?,
    )

    data class RuntimeStream(
        val url: String,
        val codec: String?,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val headers: Map<String, String>,
        val quality: String?,
        val durationSec: Int?,
    )

    private val lock = Mutex()
    private var quickJs: QuickJs? = null
    private var loadedVersion: String? = null
    private var manifest: SflxManifest? = null
    private val rememberedTracks = LinkedHashMap<String, RuntimeTrack>()
    private val cookies = LinkedHashMap<String, MutableList<Cookie>>()
    private var loggedUnsupportedVersion: String? = null

    /** Required host features this runtime cannot satisfy at the requested version. */
    fun unsupportedRuntimeFeatures(manifest: SflxManifest): List<String> = unsupportedFeatures(manifest)

    suspend fun ensureLoaded(): Result<SflxManifest> = lock.withLock {
        withContext(Dispatchers.Default) {
            runCatching {
                val installed = SflxPackageManager.loadInstalled(context, providerId)
                    ?: error("$providerId SFLX is not installed")
                if (quickJs != null && loadedVersion == installed.manifest.version) {
                    return@runCatching installed.manifest
                }
                closeEngine()
                load(installed)
                installed.manifest
            }
        }
    }

    suspend fun search(query: String, limit: Int): List<RuntimeTrack> = lock.withLock {
        withContext(Dispatchers.Default) {
            val installed = SflxPackageManager.loadInstalled(context, providerId) ?: return@withContext emptyList()
            if (quickJs == null || loadedVersion != installed.manifest.version) {
                closeEngine()
                load(installed)
            }
            val qjs = quickJs ?: return@withContext emptyList()

            val raw = when {
                hasFunction(qjs, "searchTracks") -> call(qjs, "searchTracks", listOf(JSONObject.quote(query), limit.toString()))
                hasFunction(qjs, "customSearch") -> call(
                    qjs,
                    "customSearch",
                    listOf(JSONObject.quote(query), JSONObject(mapOf("limit" to limit)).toString()),
                )
                else -> return@withContext emptyList()
            }
            val tracks = parseTracks(raw).take(limit)
            tracks.forEach { rememberedTracks[it.id] = it }
            trimRememberedTracks()
            tracks
        }
    }

    suspend fun resolve(trackId: String): RuntimeStream? = lock.withLock {
        withContext(Dispatchers.Default) {
            val installed = SflxPackageManager.loadInstalled(context, providerId) ?: return@withContext null
            val unsupported = unsupportedRuntimeFeatures(installed.manifest)
            if (unsupported.isNotEmpty()) {
                if (loggedUnsupportedVersion != installed.manifest.version) {
                    loggedUnsupportedVersion = installed.manifest.version
                    TrackLog.w(
                        TAG,
                        "$providerId SFLX ${installed.manifest.version} requires unsupported " +
                                "runtime features (${unsupported.joinToString()})",
                    )
                }
                return@withContext null
            }
            if (quickJs == null || loadedVersion != installed.manifest.version) {
                closeEngine()
                load(installed)
            }
            val qjs = quickJs ?: return@withContext null
            // The runtime track cache is only an optimization. It is cleared when the
            // QuickJS engine is rebuilt and it is never populated by durable
            // LosslessKnowledgeStore matches or by SflxTidalSource's metadata fallback.
            // tidal-web 1.2.0 exposes download(trackId, ...) but our compatibility call
            // also needs title/artist metadata. Rehydrate it from the extension itself
            // instead of making a cold runtime look "disabled" until the user toggles
            // the source and happens to trigger a fresh search.
            val item = rememberedTracks[trackId] ?: hydrateTrack(qjs, trackId)

            val qualities = preferredLosslessQualities(installed.manifest)
            for (quality in qualities) {
                val raw = when {
                    hasFunction(qjs, "getDownloadUrl") -> call(
                        qjs,
                        "getDownloadUrl",
                        listOf(JSONObject.quote(trackId), JSONObject.quote(quality)),
                    )
                    else -> null
                }
                raw?.let(::parseStream)?.let { stream ->
                    if (looksLossless(stream)) return@withContext stream
                }
            }

            // Some providers expose availability as the lookup step and return
            // a provider-native id that must then be handed to getDownloadUrl.
            if (hasFunction(qjs, "checkAvailability") && item != null) {
                val availability = call(
                    qjs,
                    "checkAvailability",
                    listOf("null", JSONObject.quote(item.title), JSONObject.quote(item.artist)),
                )
                val availableId = parseAvailabilityId(availability)
                if (!availableId.isNullOrBlank() && hasFunction(qjs, "getDownloadUrl")) {
                    for (quality in qualities) {
                        val raw = call(
                            qjs,
                            "getDownloadUrl",
                            listOf(JSONObject.quote(availableId), JSONObject.quote(quality)),
                        )
                        raw?.let(::parseStream)?.let { stream ->
                            if (looksLossless(stream)) return@withContext stream
                        }
                    }
                }
            }

            // Last compatibility path: providers that implement download()
            // rather than returning a remote URL. This may fully cache the FLAC
            // before playback, but still gives SourceResolver a real second
            // Lossless route rather than lying about an unsupported stream.
            if (hasFunction(qjs, "download") && item != null && installed.manifest.permissions.file) {
                for (quality in qualities) {
                    val relative = "stream-cache/${safeName(trackId)}-$quality.flac"
                    val raw = callDownload(qjs, item, trackId, quality, relative)
                    val downloaded = parseDownloadedStream(raw, installed.root, quality, item)
                    if (downloaded != null && looksLossless(downloaded)) return@withContext downloaded
                }
            }
            null
        }
    }

    fun close() {
        quickJs?.close()
        quickJs = null
        loadedVersion = null
        manifest = null
        cookies.clear()
        rememberedTracks.clear()
    }

    private suspend fun load(installed: SflxPackageManager.InstalledPackage) {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 768 * 1024L
        try {
            bindNative(qjs, installed)
            qjs.evaluate<String>(BOOTSTRAP)
            qjs.evaluate<String>(installed.indexJs)
            val registered = qjs.evaluate<String>("typeof __orb_sflx_extension === 'object' && __orb_sflx_extension !== null ? 'yes' : 'no'")
            require(registered == "yes") { "SFLX extension did not call registerExtension()" }
            quickJs = qjs
            loadedVersion = installed.manifest.version
            manifest = installed.manifest
            if (installed.manifest.requiredRuntimeFeatures.isNotEmpty()) {
                TrackLog.d(
                    TAG,
                    "$providerId SFLX requests runtime features: ${installed.manifest.requiredRuntimeFeatures.joinToString()}",
                )
            }

            if (hasFunction(qjs, "initialize")) {
                val initialized = call(qjs, "initialize", listOf("{}"))
                TrackLog.d(TAG, "$providerId SFLX initialize: ${initialized?.take(180)}")
            }
            TrackLog.d(TAG, "Loaded ${installed.manifest.name} ${installed.manifest.version}")
        } catch (error: Throwable) {
            qjs.close()
            throw error
        }
    }

    private suspend fun closeEngine() {
        quickJs?.let { qjs ->
            runCatching {
                if (hasFunction(qjs, "cleanup")) call(qjs, "cleanup", emptyList())
            }
            qjs.close()
        }
        quickJs = null
        loadedVersion = null
        manifest = null
    }

    private fun bindNative(qjs: QuickJs, installed: SflxPackageManager.InstalledPackage) {
        val permissions = installed.manifest.permissions
        val storage = context.getSharedPreferences(
            "orb_sflx_${installed.manifest.name}",
            Context.MODE_PRIVATE,
        )
        val filesRoot = File(installed.root, "data").also { it.mkdirs() }
        val credentials = encryptedCredentials(installed.manifest.name)
        val signedSession = installed.manifest.signedSession?.let { config ->
            SflxSignedSession(
                context = context,
                extensionId = installed.manifest.name,
                config = config,
                permissions = permissions,
            )
        }

        qjs.define("console") {
            function("log", logBinding("DEBUG"))
            function("info", logBinding("INFO"))
            function("warn", logBinding("WARN"))
            function("error", logBinding("ERROR"))
        }

        qjs.define("__orbSflxNative") {
            function("http", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString()?.uppercase(Locale.US) ?: "GET"
                    val url = args.getOrNull(1)?.toString().orEmpty()
                    val headers = args.getOrNull(2)?.toString().orEmpty()
                    val body = args.getOrNull(3)?.toString()?.takeUnless { it == "null" }
                    return performHttp(method, url, headers, body, permissions)
                }
            })
            function("storageGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.storage) { "SFLX storage permission denied" }
                    return storage.getString(args.getOrNull(0)?.toString().orEmpty(), null)
                        ?: "__ORB_SFLX_NULL__"
                }
            })
            function("storageSet", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    require(permissions.storage) { "SFLX storage permission denied" }
                    storage.edit().putString(
                        args.getOrNull(0)?.toString().orEmpty(),
                        args.getOrNull(1)?.toString().orEmpty(),
                    ).apply()
                }
            })
            function("storageRemove", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    require(permissions.storage) { "SFLX storage permission denied" }
                    storage.edit().remove(args.getOrNull(0)?.toString().orEmpty()).apply()
                }
            })
            function("storageClear", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    require(permissions.storage) { "SFLX storage permission denied" }
                    storage.edit().clear().apply()
                }
            })
            function("credentialGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    credentials.getString(args.getOrNull(0)?.toString().orEmpty(), null) ?: "__ORB_SFLX_NULL__"
            })
            function("credentialSet", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    credentials.edit().putString(
                        args.getOrNull(0)?.toString().orEmpty(),
                        args.getOrNull(1)?.toString().orEmpty(),
                    ).apply()
                }
            })
            function("credentialRemove", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    credentials.edit().remove(args.getOrNull(0)?.toString().orEmpty()).apply()
                }
            })
            function("credentialClear", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) { credentials.edit().clear().apply() }
            })
            function("clearCookies", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) { cookies.clear() }
            })
            if (signedSession != null) {
                function("sessionSignedFetch", object : FunctionBinding<String> {
                    override fun invoke(args: Array<Any?>): String {
                        val method = args.getOrNull(0)?.toString().orEmpty()
                        val path = args.getOrNull(1)?.toString().orEmpty()
                        val body = args.getOrNull(2)?.toString()?.takeUnless { it == "null" }
                        val headers = args.getOrNull(3)?.toString().orEmpty()
                        return signedSession.signedFetch(method, path, body, headers).json.toString()
                    }
                })
                function("sessionCompleteGrant", object : FunctionBinding<String> {
                    override fun invoke(args: Array<Any?>): String =
                        signedSession.completeGrant(args.getOrNull(0)?.toString().orEmpty()).toString()
                })
                function("sessionStatus", object : FunctionBinding<String> {
                    override fun invoke(args: Array<Any?>): String = signedSession.status().toString()
                })
                function("sessionClear", object : FunctionBinding<String> {
                    override fun invoke(args: Array<Any?>): String = signedSession.clear().toString()
                })
            }
            function("hash", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val algorithm = when (args.getOrNull(0)?.toString()?.lowercase()) {
                        "md5" -> "MD5"
                        else -> "SHA-256"
                    }
                    return MessageDigest.getInstance(algorithm)
                        .digest(args.getOrNull(1)?.toString().orEmpty().toByteArray())
                        .joinToString("") { "%02x".format(it) }
                }
            })
            function("hmac", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val algorithm = when (args.getOrNull(0)?.toString()?.lowercase()) {
                        "sha1", "hmacsha1" -> "HmacSHA1"
                        else -> "HmacSHA256"
                    }
                    val key = args.getOrNull(1)?.toString().orEmpty().toByteArray()
                    val message = args.getOrNull(2)?.toString().orEmpty().toByteArray()
                    val base64 = args.getOrNull(3)?.toString()?.toBooleanStrictOrNull() == true
                    val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }.doFinal(message)
                    return if (base64) Base64.encodeToString(mac, Base64.NO_WRAP)
                    else mac.joinToString("") { "%02x".format(it) }
                }
            })
            function("hmacBytes", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val algorithm = when (args.getOrNull(0)?.toString()?.lowercase()) {
                        "sha1", "hmacsha1" -> "HmacSHA1"
                        else -> "HmacSHA256"
                    }
                    fun bytes(raw: Any?): ByteArray {
                        val array = runCatching { JSONArray(raw?.toString().orEmpty()) }.getOrElse { JSONArray() }
                        return ByteArray(array.length()) { index -> (array.optInt(index) and 0xff).toByte() }
                    }
                    val key = bytes(args.getOrNull(1))
                    val message = bytes(args.getOrNull(2))
                    val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }.doFinal(message)
                    return JSONArray(mac.map { it.toInt() and 0xff }).toString()
                }
            })
            function("base64Encode", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    Base64.encodeToString(args.getOrNull(0)?.toString().orEmpty().toByteArray(), Base64.NO_WRAP)
            })
            function("base64Decode", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String =
                    String(Base64.decode(args.getOrNull(0)?.toString().orEmpty(), Base64.DEFAULT))
            })
            function("fileExists", object : FunctionBinding<Boolean> {
                override fun invoke(args: Array<Any?>): Boolean {
                    require(permissions.file) { "SFLX file permission denied" }
                    return sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty()).isFile
                }
            })
            function("fileRead", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val file = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    require(file.length() <= MAX_HTTP_BODY) { "SFLX file is too large to read into JavaScript" }
                    return file.readText()
                }
            })
            function("fileReadBytes", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val file = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    if (!file.isFile) {
                        return JSONObject().put("success", false).put("error", "file not found").toString()
                    }
                    require(file.length() <= MAX_JS_BINARY_BYTES) { "SFLX file is too large to read into JavaScript" }
                    val options = runCatching {
                        JSONObject(args.getOrNull(1)?.toString().orEmpty().ifBlank { "{}" })
                    }.getOrElse { JSONObject() }
                    val encoding = options.optString("encoding", "base64").lowercase(Locale.US)
                    require(encoding == "base64") { "SFLX file.readBytes currently supports base64 encoding only" }
                    val bytes = file.readBytes()
                    return JSONObject()
                        .put("success", true)
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("bytesRead", bytes.size)
                        .toString()
                }
            })
            function("fileWrite", object : FunctionBinding<Boolean> {
                override fun invoke(args: Array<Any?>): Boolean {
                    require(permissions.file) { "SFLX file permission denied" }
                    val file = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    file.parentFile?.mkdirs()
                    file.writeText(args.getOrNull(1)?.toString().orEmpty())
                    return true
                }
            })
            function("fileWriteBytes", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val file = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    val data = args.getOrNull(1)?.toString().orEmpty()
                    val options = runCatching {
                        JSONObject(args.getOrNull(2)?.toString().orEmpty().ifBlank { "{}" })
                    }.getOrElse { JSONObject() }
                    val encoding = options.optString("encoding", "base64").lowercase(Locale.US)
                    require(encoding == "base64") { "SFLX file.writeBytes currently supports base64 encoding only" }
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    require(bytes.size.toLong() <= MAX_JS_BINARY_BYTES) { "SFLX binary write is too large" }
                    val append = options.optBoolean("append", false)
                    val existing = if (append && file.isFile) file.length() else 0L
                    require(existing + bytes.size <= MAX_FILE_BYTES) { "SFLX file exceeds the file safety limit" }
                    file.parentFile?.mkdirs()
                    FileOutputStream(file, append).use { it.write(bytes) }
                    return JSONObject()
                        .put("success", true)
                        .put("bytesWritten", bytes.size)
                        .put("path", relativeDataPath(file))
                        .toString()
                }
            })
            function("fileDelete", object : FunctionBinding<Boolean> {
                override fun invoke(args: Array<Any?>): Boolean {
                    require(permissions.file) { "SFLX file permission denied" }
                    return sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty()).delete()
                }
            })
            function("fileDownload", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val url = args.getOrNull(0)?.toString().orEmpty()
                    val output = sandboxFile(filesRoot, args.getOrNull(1)?.toString().orEmpty())
                    val headers = args.getOrNull(2)?.toString().orEmpty()
                    val response = performHttpToFile(url, headers, output, permissions)
                    return response.toString()
                }
            })
            function("fileDownloadSegments", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val segments = args.getOrNull(0)?.toString().orEmpty()
                    val output = sandboxFile(filesRoot, args.getOrNull(1)?.toString().orEmpty())
                    val options = args.getOrNull(2)?.toString().orEmpty()
                    return performHttpSegmentsToFile(segments, output, options, permissions).toString()
                }
            })
            function("fileGetSize", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val file = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    return if (file.isFile) {
                        JSONObject().put("success", true).put("size", file.length()).toString()
                    } else {
                        JSONObject().put("success", false).put("error", "file not found").toString()
                    }
                }
            })
            function("fileCopy", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val source = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    val target = sandboxFile(filesRoot, args.getOrNull(1)?.toString().orEmpty())
                    require(source.isFile) { "SFLX copy source does not exist" }
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    return JSONObject().put("success", true).put("path", relativeDataPath(target)).toString()
                }
            })
            function("fileMove", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val source = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    val target = sandboxFile(filesRoot, args.getOrNull(1)?.toString().orEmpty())
                    require(source.isFile) { "SFLX move source does not exist" }
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    val moved = source.renameTo(target) || runCatching {
                        source.copyTo(target, overwrite = true)
                        source.delete()
                    }.isSuccess
                    require(moved) { "Could not move SFLX file" }
                    return JSONObject().put("success", true).put("path", relativeDataPath(target)).toString()
                }
            })
            function("fileTransformPatternedBlocks", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(permissions.file) { "SFLX file permission denied" }
                    val input = sandboxFile(filesRoot, args.getOrNull(0)?.toString().orEmpty())
                    val output = sandboxFile(filesRoot, args.getOrNull(1)?.toString().orEmpty())
                    val options = args.getOrNull(2)?.toString().orEmpty()
                    return performPatternedFileTransform(input, output, options).toString()
                }
            })
            function("localTime", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val now = java.util.Calendar.getInstance()
                    val tz = java.util.TimeZone.getDefault()
                    return JSONObject()
                        .put("timestamp", System.currentTimeMillis())
                        .put("timezone", tz.id)
                        .put("timezoneOffset", tz.getOffset(System.currentTimeMillis()) / 60000)
                        .put("year", now.get(java.util.Calendar.YEAR))
                        .put("month", now.get(java.util.Calendar.MONTH) + 1)
                        .put("day", now.get(java.util.Calendar.DAY_OF_MONTH))
                        .put("hour", now.get(java.util.Calendar.HOUR_OF_DAY))
                        .put("minute", now.get(java.util.Calendar.MINUTE))
                        .put("second", now.get(java.util.Calendar.SECOND))
                        .toString()
                }
            })
        }
    }

    private fun logBinding(level: String) = object : FunctionBinding<Unit> {
        override fun invoke(args: Array<Any?>) {
            val message = "[SFLX][$level] " + args.joinToString(" ") { it?.toString() ?: "null" }
            when (level) {
                "ERROR" -> TrackLog.e(TAG, message)
                "WARN" -> TrackLog.w(TAG, message)
                else -> TrackLog.d(TAG, message)
            }
        }
    }

    private fun performHttp(
        method: String,
        url: String,
        headersJson: String,
        body: String?,
        permissions: SflxPermissions,
    ): String {
        var current = parseAllowedUrl(url, permissions)
        var currentMethod = method
        var currentBody = body
        repeat(6) {
            val request = buildRequest(currentMethod, current, headersJson, currentBody)
            httpClient.newCall(request).execute().use { response ->
                rememberCookies(current, response.headers.values("Set-Cookie"))
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: return responseJson(response, "")
                    current = parseAllowedUrl(current.resolve(location)?.toString() ?: error("Invalid redirect"), permissions)
                    if (response.code == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    return@repeat
                }
                val responseBody = response.body
                val contentLength = responseBody?.contentLength() ?: 0L
                require(contentLength < 0 || contentLength <= MAX_HTTP_BODY) { "SFLX HTTP response too large" }
                val text = responseBody?.string().orEmpty()
                require(text.toByteArray().size <= MAX_HTTP_BODY) { "SFLX HTTP response too large" }
                return responseJson(response, text)
            }
        }
        error("Too many SFLX HTTP redirects")
    }

    private fun performHttpToFile(
        url: String,
        headersJson: String,
        output: File,
        permissions: SflxPermissions,
    ): JSONObject {
        var current = parseAllowedUrl(url, permissions)
        repeat(6) {
            val request = buildRequest("GET", current, headersJson, null)
            httpClient.newCall(request).execute().use { response ->
                rememberCookies(current, response.headers.values("Set-Cookie"))
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: return JSONObject().put("success", false).put("statusCode", response.code)
                    current = parseAllowedUrl(current.resolve(location)?.toString() ?: error("Invalid redirect"), permissions)
                    return@repeat
                }
                if (!response.isSuccessful) {
                    return JSONObject().put("success", false).put("statusCode", response.code)
                }
                output.parentFile?.mkdirs()
                val body = response.body ?: return JSONObject().put("success", false).put("statusCode", response.code)
                val length = body.contentLength()
                require(length < 0 || length <= MAX_FILE_BYTES) { "SFLX download exceeds the file safety limit" }
                var written = 0L
                output.outputStream().use { sink ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            require(written <= MAX_FILE_BYTES) { "SFLX download exceeds the file safety limit" }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                return JSONObject()
                    .put("success", true)
                    .put("path", relativeDataPath(output))
                    .put("size", written)
                    .put("bytesWritten", written)
                    .put("bytes_written", written)
                    .put("statusCode", response.code)
            }
        }
        error("Too many SFLX download redirects")
    }

    private fun performHttpSegmentsToFile(
        segmentsJson: String,
        output: File,
        optionsJson: String,
        permissions: SflxPermissions,
    ): JSONObject {
        val options = runCatching { JSONObject(optionsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val globalHeaders = options.optJSONObject("headers") ?: JSONObject()
        val parsed: Any = runCatching { JSONArray(segmentsJson) }.getOrElse {
            val obj = JSONObject(segmentsJson.ifBlank { "{}" })
            obj.optJSONArray("segments") ?: JSONArray()
        }
        val segments = parsed as? JSONArray ?: JSONArray()
        require(segments.length() in 1..20_000) { "SFLX downloadSegments received an invalid segment list" }

        output.parentFile?.mkdirs()
        val staged = File(output.parentFile ?: context.filesDir, ".${output.name}.segments.partial")
        if (staged.exists()) staged.delete()
        var total = 0L
        var lastStatus = 200
        try {
            FileOutputStream(staged, false).use { sink ->
                for (index in 0 until segments.length()) {
                    val raw = segments.opt(index)
                    val segment = raw as? JSONObject
                    val url = when (raw) {
                        is String -> raw
                        is JSONObject -> listOf(
                            raw.optString("url"),
                            raw.optString("uri"),
                            raw.optString("href"),
                        ).firstOrNull { it.isNotBlank() }.orEmpty()
                        else -> ""
                    }
                    require(url.isNotBlank()) { "SFLX downloadSegments segment $index has no URL" }

                    val headers = JSONObject()
                    globalHeaders.keys().forEach { key -> headers.put(key, globalHeaders.opt(key)) }
                    segment?.optJSONObject("headers")?.let { local ->
                        local.keys().forEach { key -> headers.put(key, local.opt(key)) }
                    }
                    if (segment != null && headers.optString("Range").isBlank()) {
                        val range = segment.optString("range").trim()
                        val rangeStart = segment.optLong("rangeStart", segment.optLong("start", -1L))
                        val rangeEnd = segment.optLong("rangeEnd", segment.optLong("end", -1L))
                        when {
                            range.isNotBlank() -> headers.put("Range", if (range.startsWith("bytes=")) range else "bytes=$range")
                            rangeStart >= 0 -> headers.put("Range", "bytes=$rangeStart-${if (rangeEnd >= rangeStart) rangeEnd else ""}")
                        }
                    }

                    val result = performHttpToStream(url, headers.toString(), sink, permissions, total)
                    total += result.bytesWritten
                    lastStatus = result.statusCode
                }
                sink.flush()
            }
            if (output.exists()) require(output.delete()) { "Could not replace SFLX segmented output" }
            if (!staged.renameTo(output)) {
                staged.copyTo(output, overwrite = true)
                staged.delete()
            }
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
        return JSONObject()
            .put("success", true)
            .put("path", relativeDataPath(output))
            .put("size", total)
            .put("bytesWritten", total)
            .put("bytes_written", total)
            .put("segments", segments.length())
            .put("segmentsDownloaded", segments.length())
            .put("segments_downloaded", segments.length())
            .put("resumed", false)
            .put("statusCode", lastStatus)
    }

    private data class StreamDownloadResult(val statusCode: Int, val bytesWritten: Long)

    private fun performHttpToStream(
        url: String,
        headersJson: String,
        sink: OutputStream,
        permissions: SflxPermissions,
        alreadyWritten: Long,
    ): StreamDownloadResult {
        var current = parseAllowedUrl(url, permissions)
        repeat(6) {
            val request = buildRequest("GET", current, headersJson, null)
            httpClient.newCall(request).execute().use { response ->
                rememberCookies(current, response.headers.values("Set-Cookie"))
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: error("SFLX segment redirect has no Location")
                    current = parseAllowedUrl(current.resolve(location)?.toString() ?: error("Invalid redirect"), permissions)
                    return@repeat
                }
                require(response.isSuccessful) { "SFLX segment download returned HTTP ${response.code}" }
                val body = response.body ?: error("SFLX segment response has no body")
                val length = body.contentLength()
                require(length < 0 || alreadyWritten + length <= MAX_FILE_BYTES) {
                    "SFLX segmented download exceeds the file safety limit"
                }
                var written = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        require(alreadyWritten + written <= MAX_FILE_BYTES) {
                            "SFLX segmented download exceeds the file safety limit"
                        }
                        sink.write(buffer, 0, read)
                    }
                }
                return StreamDownloadResult(response.code, written)
            }
        }
        error("Too many SFLX segment redirects")
    }

    private fun performPatternedFileTransform(
        input: File,
        output: File,
        optionsJson: String,
    ): JSONObject {
        require(input.isFile) { "SFLX patterned transform input does not exist" }
        require(input.length() <= MAX_FILE_BYTES) { "SFLX patterned transform input exceeds the file safety limit" }

        val options = runCatching { JSONObject(optionsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val operation = options.optString("operation", "decrypt").trim().lowercase(Locale.US)
        require(operation == "decrypt" || operation == "encrypt") { "Unsupported SFLX transform operation: $operation" }
        val algorithm = options.optString("algorithm").trim().lowercase(Locale.US)
        require(algorithm == "blowfish" || algorithm == "aes") { "Unsupported SFLX transform algorithm: $algorithm" }
        val mode = options.optString("mode", "cbc").trim().lowercase(Locale.US)
        require(mode == "cbc" || mode == "ctr") { "Unsupported SFLX transform mode: $mode" }
        val padding = options.optString("padding", "none").trim().lowercase(Locale.US)
        require(padding == "none" || padding == "nopadding") { "SFLX patterned transform only supports no padding" }

        val key = decodeTransformMaterial(
            value = options.optString("key"),
            encoding = options.optString("keyEncoding", options.optString("key_encoding", "utf8")),
            label = "key",
        )
        val iv = decodeTransformMaterial(
            value = options.optString("iv"),
            encoding = options.optString("ivEncoding", options.optString("iv_encoding", "utf8")),
            label = "iv",
        )
        require(key.isNotEmpty()) { "SFLX patterned transform requires a key" }
        require(iv.isNotEmpty()) { "SFLX patterned transform requires an IV" }

        val segmentSize = options.optInt("segmentSize", options.optInt("segment_size", 2048))
        val transformEvery = options.optInt("transformEvery", options.optInt("transform_every", 3))
        val transformOffset = options.optInt("transformOffset", options.optInt("transform_offset", 0))
        val transformPartial = options.optBoolean("transformPartial", options.optBoolean("transform_partial", false))
        require(segmentSize in 1..(16 * 1024 * 1024)) { "Invalid SFLX patterned segment size" }
        require(transformEvery in 1..100_000) { "Invalid SFLX patterned transform cadence" }
        require(transformOffset in 0 until transformEvery) { "Invalid SFLX patterned transform offset" }

        val jceAlgorithm = if (algorithm == "blowfish") "Blowfish" else "AES"
        val cipherMode = if (operation == "encrypt") Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        val secretKey = SecretKeySpec(key, jceAlgorithm)
        val blockSize = Cipher.getInstance("$jceAlgorithm/ECB/NoPadding").blockSize
        require(iv.size == blockSize) { "SFLX $algorithm/$mode IV must be $blockSize bytes" }

        output.parentFile?.mkdirs()
        val staged = File(output.parentFile ?: context.filesDir, ".${output.name}.${System.nanoTime()}.partial")
        var bytesProcessed = 0L
        var segmentsProcessed = 0
        var segmentsTransformed = 0

        try {
            input.inputStream().buffered().use { source ->
                FileOutputStream(staged, false).buffered().use { sink ->
                    val buffer = ByteArray(segmentSize)
                    var segmentIndex = 0
                    while (true) {
                        var count = 0
                        while (count < segmentSize) {
                            val read = source.read(buffer, count, segmentSize - count)
                            if (read <= 0) break
                            count += read
                        }
                        if (count <= 0) break

                        val selected = segmentIndex % transformEvery == transformOffset
                        val completeSegment = count == segmentSize
                        if (selected && (completeSegment || transformPartial)) {
                            val transformed = if (mode == "ctr") {
                                ctrTransform(buffer, count, secretKey, jceAlgorithm, iv)
                            } else {
                                require(count % blockSize == 0) {
                                    "SFLX CBC patterned segment must be aligned to $blockSize bytes"
                                }
                                val cipher = Cipher.getInstance("$jceAlgorithm/CBC/NoPadding")
                                cipher.init(cipherMode, secretKey, IvParameterSpec(iv))
                                cipher.doFinal(buffer, 0, count)
                            }
                            require(transformed.size == count) { "SFLX no-padding transform changed segment length" }
                            sink.write(transformed)
                            segmentsTransformed++
                        } else {
                            sink.write(buffer, 0, count)
                        }

                        bytesProcessed += count
                        require(bytesProcessed <= MAX_FILE_BYTES) { "SFLX patterned transform exceeds the file safety limit" }
                        segmentsProcessed++
                        segmentIndex++
                    }
                    sink.flush()
                }
            }
            if (output.exists()) require(output.delete()) { "Could not replace SFLX transformed output" }
            if (!staged.renameTo(output)) {
                staged.copyTo(output, overwrite = true)
                staged.delete()
            }
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }

        return JSONObject()
            .put("success", true)
            .put("path", relativeDataPath(output))
            .put("bytesProcessed", bytesProcessed)
            .put("bytes_processed", bytesProcessed)
            .put("segmentsProcessed", segmentsProcessed)
            .put("segments_processed", segmentsProcessed)
            .put("segmentsTransformed", segmentsTransformed)
            .put("segments_transformed", segmentsTransformed)
    }

    /** CTR is implemented from ECB so Blowfish/CTR works even on providers that do not expose that transformation. */
    private fun ctrTransform(
        input: ByteArray,
        count: Int,
        key: SecretKeySpec,
        algorithm: String,
        iv: ByteArray,
    ): ByteArray {
        val blockCipher = Cipher.getInstance("$algorithm/ECB/NoPadding")
        blockCipher.init(Cipher.ENCRYPT_MODE, key)
        val counter = iv.copyOf()
        val output = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val stream = blockCipher.doFinal(counter)
            val amount = minOf(stream.size, count - offset)
            for (index in 0 until amount) {
                output[offset + index] = (input[offset + index].toInt() xor stream[index].toInt()).toByte()
            }
            incrementCounter(counter)
            offset += amount
        }
        return output
    }

    private fun incrementCounter(counter: ByteArray) {
        for (index in counter.lastIndex downTo 0) {
            counter[index] = (counter[index].toInt() + 1).toByte()
            if (counter[index].toInt() and 0xff != 0) return
        }
    }

    private fun decodeTransformMaterial(value: String, encoding: String, label: String): ByteArray {
        require(value.isNotBlank()) { "SFLX patterned transform requires $label" }
        return when (encoding.trim().lowercase(Locale.US)) {
            "hex" -> {
                val normalized = value.trim().removePrefix("0x")
                require(normalized.length % 2 == 0 && normalized.matches(Regex("[0-9a-fA-F]+"))) {
                    "Invalid hexadecimal SFLX transform $label"
                }
                ByteArray(normalized.length / 2) { index ->
                    normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }
            "base64", "b64" -> Base64.decode(value, Base64.DEFAULT)
            "utf8", "utf-8", "text", "raw" -> value.toByteArray(Charsets.UTF_8)
            else -> error("Unsupported SFLX transform $label encoding: $encoding")
        }
    }

    private fun buildRequest(method: String, url: HttpUrl, headersJson: String, body: String?): Request {
        val builder = Request.Builder().url(url)
        val headers = runCatching { JSONObject(headersJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        headers.keys().forEach { key ->
            val value = headers.optString(key)
            if (key.equals("Host", true) || key.equals("Content-Length", true)) return@forEach
            if (value.isNotBlank()) builder.header(key, value)
        }
        val cookie = cookies[url.host]
            ?.filter { it.matches(url) }
            ?.joinToString("; ") { "${it.name}=${it.value}" }
        if (!cookie.isNullOrBlank() && headers.optString("Cookie").isBlank()) builder.header("Cookie", cookie)
        if (headers.optString("User-Agent").isBlank()) builder.header("User-Agent", "Mozilla/5.0 (Linux; Android) Orb/1.0 SFLX")

        val requestBody = if (method in setOf("POST", "PUT", "PATCH", "DELETE") && body != null) {
            val mediaType = headers.optString("Content-Type").toMediaTypeOrNull()
            body.toRequestBody(mediaType)
        } else null
        builder.method(method, requestBody)
        return builder.build()
    }

    private fun responseJson(response: okhttp3.Response, body: String): String {
        val headerJson = JSONObject()
        for (name in response.headers.names()) {
            val values = response.headers.values(name)
            headerJson.put(name, if (values.size <= 1) values.firstOrNull().orEmpty() else JSONArray(values))
        }
        val parsed = runCatching {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        }.getOrNull()
        return JSONObject()
            .put("statusCode", response.code)
            .put("status", response.code)
            .put("statusText", response.message)
            .put("ok", response.isSuccessful)
            .put("body", body)
            .put("headers", headerJson)
            .put("data", parsed ?: JSONObject.NULL)
            .toString()
    }

    private fun parseAllowedUrl(url: String, permissions: SflxPermissions): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: error("Invalid SFLX URL")
        require(parsed.scheme == "https" || (permissions.allowHttp && parsed.scheme == "http")) {
            "SFLX network access requires HTTPS"
        }
        require(parsed.username.isBlank() && parsed.password.isBlank()) { "Credentials in SFLX URLs are not allowed" }
        require(hostAllowed(parsed.host, permissions.network)) {
            "SFLX network permission denied for ${parsed.host}"
        }
        require(!isPrivateLiteral(parsed.host)) { "SFLX access to private/local addresses is blocked" }
        return parsed
    }

    private fun hostAllowed(host: String, allowed: List<String>): Boolean {
        val normalized = host.lowercase(Locale.US).trimEnd('.')
        return allowed.any { rule ->
            val r = rule.lowercase(Locale.US).trim().trimEnd('.')
            when {
                r.startsWith("*.") -> normalized.endsWith(".${r.removePrefix("*.")}")
                else -> normalized == r
            }
        }
    }

    private fun isPrivateLiteral(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        if (h == "localhost" || h == "::1") return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 169 && parts[1] == 254) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun rememberCookies(url: HttpUrl, values: List<String>) {
        if (values.isEmpty()) return
        val bucket = cookies.getOrPut(url.host) { mutableListOf() }
        values.mapNotNull { Cookie.parse(url, it) }.forEach { incoming ->
            bucket.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            bucket += incoming
        }
    }

    private fun encryptedCredentials(extensionName: String): SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "orb_sflx_credentials_${safeName(extensionName)}",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        TrackLog.w(TAG, "Encrypted SFLX credentials unavailable: ${it.message}")
        context.getSharedPreferences("orb_sflx_credentials_${safeName(extensionName)}_plain", Context.MODE_PRIVATE)
    }

    private fun sandboxFile(root: File, relative: String): File {
        require(relative.isNotBlank()) { "SFLX file path is blank" }
        require(!File(relative).isAbsolute) { "Absolute SFLX file paths are not allowed" }
        val base = root.canonicalFile
        val result = File(base, relative).canonicalFile
        require(result.path.startsWith(base.path + File.separator) || result == base) {
            "SFLX file path escapes its sandbox"
        }
        return result
    }

    private fun relativeDataPath(file: File): String {
        val marker = "${File.separator}data${File.separator}"
        return file.canonicalPath.substringAfterLast(marker, file.name).replace(File.separatorChar, '/')
    }

    private suspend fun hasFunction(qjs: QuickJs, name: String): Boolean =
        qjs.evaluate<String>("typeof __orb_sflx_extension[${JSONObject.quote(name)}]") == "function"

    private suspend fun call(qjs: QuickJs, name: String, args: List<String>): String? {
        val argsJs = args.joinToString(",")
        qjs.evaluate<String>(
            """
            var __orb_sflx_result = undefined;
            var __orb_sflx_error = undefined;
            (async function() {
              try {
                var value = await __orb_sflx_extension[${JSONObject.quote(name)}]($argsJs);
                __orb_sflx_result = typeof value === 'string' ? value : JSON.stringify(value);
              } catch (e) {
                __orb_sflx_error = e && e.message ? e.message : String(e);
              }
            })();
            'started';
            """.trimIndent(),
        )
        val error = qjs.evaluate<String>("__orb_sflx_error === undefined ? '' : String(__orb_sflx_error)")
        if (error.isNotBlank()) {
            TrackLog.w(TAG, "SFLX $name failed: ${error.take(500)}")
            return null
        }
        return qjs.evaluate<String>("__orb_sflx_result === undefined ? '' : String(__orb_sflx_result)")
            .takeIf { it.isNotBlank() }
    }

    private suspend fun callDownload(
        qjs: QuickJs,
        item: RuntimeTrack,
        trackId: String,
        quality: String,
        output: String,
    ): String? {
        val preparedContext = JSONObject()
            .put("track_id", trackId)
            .put("trackId", trackId)
            .put("track_name", item.title)
            .put("title", item.title)
            .put("artist_name", item.artist)
            .put("artist", item.artist)
            .put("album_name", item.album ?: JSONObject.NULL)
            .put("album", item.album ?: JSONObject.NULL)
            .put("duration_ms", item.durationMs ?: JSONObject.NULL)
            .put("durationMs", item.durationMs ?: JSONObject.NULL)
            .put("quality", item.quality ?: JSONObject.NULL)
            .put("explicit", item.explicit ?: JSONObject.NULL)
            .put("release_year", item.releaseYear ?: JSONObject.NULL)
            .put("cover_url", item.coverUrl ?: JSONObject.NULL)
            .put("isrc", JSONObject.NULL)
        val request = JSONObject(preparedContext.toString())
            .put("quality", quality)
            .put("output_path", output)
            .put("output_dir", output.substringBeforeLast('/', ""))
            .put("prepared_context", JSONObject(preparedContext.toString()))
            .put("preparedContext", JSONObject(preparedContext.toString()))
            .toString()
        qjs.evaluate<String>(
            """
            var __orb_sflx_result = undefined;
            var __orb_sflx_error = undefined;
            (async function() {
              try {
                var fn = __orb_sflx_extension.download;
                var value;
                if (fn.length <= 2) {
                  value = await fn(JSON.parse(${JSONObject.quote(request)}), function() {});
                } else {
                  value = await fn(
                    ${JSONObject.quote(trackId)},
                    ${JSONObject.quote(quality)},
                    ${JSONObject.quote(output)},
                    function() {}
                  );
                }
                __orb_sflx_result = typeof value === 'string' ? value : JSON.stringify(value);
              } catch (e) {
                __orb_sflx_error = e && e.message ? e.message : String(e);
              }
            })();
            'started';
            """.trimIndent(),
        )
        val error = qjs.evaluate<String>("__orb_sflx_error === undefined ? '' : String(__orb_sflx_error)")
        if (error.isNotBlank()) {
            TrackLog.w(TAG, "SFLX download failed: ${error.take(500)}")
            return null
        }
        return qjs.evaluate<String>("__orb_sflx_result === undefined ? '' : String(__orb_sflx_result)")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Rebuilds the minimum track context required by download() after a runtime
     * restart or a durable cached match. This makes rememberedTracks a cache, not
     * a hidden prerequisite for SFLX playback.
     */
    private suspend fun hydrateTrack(qjs: QuickJs, trackId: String): RuntimeTrack? {
        if (!hasFunction(qjs, "getTrack")) return null
        val raw = call(qjs, "getTrack", listOf(JSONObject.quote(trackId))) ?: return null
        val value = parseJsonValue(raw)
        val objectValue = when (value) {
            is JSONObject -> value.optJSONObject("track") ?: value.optJSONObject("data") ?: value
            else -> null
        } ?: return null
        val track = parseTrackObject(objectValue) ?: return null
        rememberedTracks[track.id] = track
        if (track.id != trackId) rememberedTracks[trackId] = track
        trimRememberedTracks()
        TrackLog.d(TAG, "Rehydrated TIDAL SFLX track context for $trackId")
        return track
    }

    private fun parseTracks(raw: String?): List<RuntimeTrack> {
        if (raw.isNullOrBlank()) return emptyList()
        val value = parseJsonValue(raw) ?: return emptyList()
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("tracks") ?: value.optJSONArray("results") ?: return emptyList()
            else -> return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::parseTrackObject)?.let(::add)
            }
        }
    }

    private fun parseTrackObject(item: JSONObject): RuntimeTrack? {
        val id = firstString(item, "id", "track_id", "trackId") ?: return null
        val title = firstString(item, "name", "title", "track_name") ?: return null
        val artist = artistText(item)
        if (artist.isBlank()) return null
        val durationMs = firstLong(item, "duration_ms", "durationMs")
            ?: firstLong(item, "duration")?.let { if (it < 10_000) it * 1000 else it }
        val release = firstString(item, "release_date", "releaseDate", "date")
        return RuntimeTrack(
            id = id,
            title = title,
            artist = artist,
            album = firstString(item, "album_name", "albumName", "album"),
            durationMs = durationMs,
            coverUrl = imageText(item),
            explicit = firstBoolean(item, "explicit", "is_explicit", "isExplicit"),
            releaseYear = release?.take(4)?.toIntOrNull() ?: firstLong(item, "year")?.toInt(),
            quality = firstString(item, "quality", "audio_quality", "audioQuality", "format"),
        )
    }

    private fun parseAvailabilityId(raw: String?): String? {
        val obj = raw?.let(::parseJsonValue) as? JSONObject ?: return null
        if (obj.has("available") && !obj.optBoolean("available", true)) return null
        return firstString(obj, "track_id", "trackId", "id", "tidal_id", "tidalId")
            ?: obj.optJSONObject("track")?.let { firstString(it, "id", "track_id", "trackId") }
    }

    private fun parseStream(raw: String): RuntimeStream? {
        val value = parseJsonValue(raw) ?: return null
        val obj = when (value) {
            is JSONObject -> value
            else -> return null
        }
        if (obj.has("success") && !obj.optBoolean("success", true)) return null
        val nested = listOf("stream", "download", "data", "result")
            .asSequence()
            .mapNotNull { obj.optJSONObject(it) }
            .firstOrNull { firstString(it, "url", "download_url", "downloadUrl", "stream_url", "streamUrl") != null }
        val source = nested ?: obj
        val url = firstString(source, "url", "download_url", "downloadUrl", "stream_url", "streamUrl") ?: return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val permissions = manifest?.permissions ?: return null
        if (runCatching { parseAllowedUrl(url, permissions) }.isFailure) {
            TrackLog.w(TAG, "SFLX returned a stream URL outside its declared network permissions")
            return null
        }
        val headersObj = source.optJSONObject("headers") ?: obj.optJSONObject("headers")
        val headers = buildMap {
            headersObj?.keys()?.forEach { key ->
                headersObj.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
        val sample = firstLong(source, "sample_rate", "sampleRate", "sample_rate_hz", "sampleRateHz")?.let {
            if (it in 1..999) it * 1000 else it
        }?.toInt()
        return RuntimeStream(
            url = url,
            codec = firstString(source, "codec", "format", "mime_type", "mimeType")
                ?.substringAfterLast('/')?.substringBefore(';')?.lowercase(Locale.US),
            sampleRateHz = sample,
            bitDepth = firstLong(source, "bit_depth", "bitDepth", "bits")?.toInt(),
            headers = headers,
            quality = firstString(source, "quality", "audio_quality", "audioQuality"),
            durationSec = firstLong(source, "duration_sec", "durationSec")?.toInt(),
        )
    }

    private fun parseDownloadedStream(
        raw: String?,
        extensionRoot: File,
        requestedQuality: String,
        item: RuntimeTrack,
    ): RuntimeStream? {
        val obj = parseJsonValue(raw ?: return null) as? JSONObject ?: return null
        if (obj.has("success") && !obj.optBoolean("success", true)) return null
        val nested = listOf("data", "result", "download").asSequence().mapNotNull { obj.optJSONObject(it) }.firstOrNull()
        val source = nested ?: obj
        val path = firstString(source, "file_path", "filePath", "path", "output_path", "outputPath")
            ?: firstString(obj, "file_path", "filePath", "path", "output_path", "outputPath")
            ?: return null
        val dataRoot = File(extensionRoot, "data").canonicalFile
        val file = if (File(path).isAbsolute) File(path).canonicalFile else File(dataRoot, path).canonicalFile
        if (!(file.path.startsWith(dataRoot.path + File.separator) || file == dataRoot)) return null
        if (!file.isFile || file.length() <= 0) return null
        val sample = firstLong(source, "actual_sample_rate", "actualSampleRate", "sample_rate", "sampleRate")?.let {
            if (it in 1..999) it * 1000 else it
        }?.toInt()
        return RuntimeStream(
            url = file.toURI().toString(),
            codec = firstString(source, "format", "codec", "mime_type", "mimeType")
                ?.substringAfterLast('/')?.substringBefore(';')?.lowercase(Locale.US) ?: "flac",
            sampleRateHz = sample,
            bitDepth = firstLong(source, "actual_bit_depth", "actualBitDepth", "bit_depth", "bitDepth")?.toInt(),
            headers = emptyMap(),
            quality = firstString(source, "quality", "audio_quality", "audioQuality") ?: requestedQuality,
            durationSec = item.durationMs?.div(1000)?.toInt(),
        )
    }

    private fun looksLossless(stream: RuntimeStream): Boolean {
        val codec = stream.codec?.lowercase(Locale.US)
        if (codec == null) return stream.quality?.uppercase(Locale.US)?.contains("LOSSLESS") == true
        return codec in setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
    }

    private fun preferredLosslessQualities(manifest: SflxManifest): List<String> {
        val ids = manifest.qualityOptions.map { it.id }
        if (ids.isEmpty()) return listOf("HI_RES_LOSSLESS", "LOSSLESS")
        val lossless = ids.filter {
            val upper = it.uppercase(Locale.US)
            upper.contains("LOSSLESS") || upper.contains("FLAC") || upper.contains("HI_RES") || upper.contains("HIRES")
        }
        return lossless.sortedByDescending {
            val upper = it.uppercase(Locale.US)
            if (upper.contains("HI_RES") || upper.contains("HIRES")) 2 else 1
        }.ifEmpty { listOf("HI_RES_LOSSLESS", "LOSSLESS") }
    }

    private fun parseJsonValue(raw: String): Any? = runCatching {
        val trimmed = raw.trim()
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("\"") -> JSONObject("{\"v\":$trimmed}").optString("v")
            else -> null
        }
    }.getOrNull()

    private fun artistText(item: JSONObject): String {
        firstString(item, "artist", "artist_name", "artistName")?.let { return it }
        val value = item.opt("artists")
        return when (value) {
            is String -> value
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    when (val artist = value.opt(index)) {
                        is String -> add(artist)
                        is JSONObject -> firstString(artist, "name", "artist", "title")?.let(::add)
                    }
                }
            }.joinToString(", ")
            else -> ""
        }
    }

    private fun imageText(item: JSONObject): String? {
        firstString(item, "cover_url", "coverUrl", "image", "thumbnail", "thumbnail_url")?.let { return it }
        return when (val images = item.opt("images")) {
            is String -> images
            is JSONArray -> {
                val first = images.opt(0)
                when (first) {
                    is String -> first
                    is JSONObject -> firstString(first, "url", "src")
                    else -> null
                }
            }
            is JSONObject -> firstString(images, "url", "large", "medium", "small")
            else -> null
        }
    }

    private fun firstString(obj: JSONObject, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        if (!obj.has(name) || obj.isNull(name)) null else obj.optString(name).trim().takeIf { it.isNotEmpty() }
    }

    private fun firstLong(obj: JSONObject, vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        if (!obj.has(name) || obj.isNull(name)) null else when (val value = obj.opt(name)) {
            is Number -> value.toLong()
            else -> value?.toString()?.toDoubleOrNull()?.toLong()
        }
    }

    private fun firstBoolean(obj: JSONObject, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
        if (!obj.has(name) || obj.isNull(name)) null else when (val value = obj.opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> when (value?.toString()?.lowercase(Locale.US)) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
        }
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun trimRememberedTracks() {
        while (rememberedTracks.size > 256) {
            val first = rememberedTracks.keys.firstOrNull() ?: break
            rememberedTracks.remove(first)
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val BOOTSTRAP = """
        var __orb_sflx_extension = null;
        function registerExtension(extension) {
          if (!extension || typeof extension !== 'object') throw new Error('invalid extension object');
          __orb_sflx_extension = extension;
        }
        var globalThis = this;
        var window = this;
        var self = this;
        if (typeof setTimeout === 'undefined') {
          function setTimeout(fn, ms) { if (typeof fn === 'function') fn(); return 0; }
          function clearTimeout(id) {}
        }
        if (typeof AbortController === 'undefined') {
          function AbortController() { this.signal = { aborted: false }; }
          AbortController.prototype.abort = function() { this.signal.aborted = true; };
        }
        if (typeof AggregateError === 'undefined') {
          function AggregateError(errors, message) { this.name = 'AggregateError'; this.message = message || ''; this.errors = errors || []; }
        }
        if (typeof Promise.allSettled === 'undefined') {
          Promise.allSettled = function(values) {
            return Promise.all(values.map(function(v) {
              return Promise.resolve(v).then(
                function(value) { return { status: 'fulfilled', value: value }; },
                function(reason) { return { status: 'rejected', reason: reason }; }
              );
            }));
          };
        }
        if (typeof Promise.any === 'undefined') {
          Promise.any = function(values) {
            return new Promise(function(resolve, reject) {
              var pending = values.length, errors = [];
              if (!pending) return reject(new AggregateError([], 'All promises were rejected'));
              values.forEach(function(v, i) {
                Promise.resolve(v).then(resolve, function(error) {
                  errors[i] = error;
                  pending--;
                  if (!pending) reject(new AggregateError(errors, 'All promises were rejected'));
                });
              });
            });
          };
        }

        function __orbHeaders(value) {
          if (!value) return {};
          if (value.headers) return value.headers || {};
          return value;
        }
        function __orbParams(url, options) {
          if (!options || !options.params) return url;
          var pairs = [];
          Object.keys(options.params).forEach(function(k) {
            var v = options.params[k];
            if (v !== undefined && v !== null) pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
          });
          if (!pairs.length) return url;
          return url + (url.indexOf('?') >= 0 ? '&' : '?') + pairs.join('&');
        }
        function __orbHttp(method, url, body, options) {
          var finalUrl = __orbParams(url, options);
          var headers = __orbHeaders(options);
          var bodyValue = body;
          if (bodyValue && typeof bodyValue === 'object') {
            bodyValue = JSON.stringify(bodyValue);
            if (!headers['Content-Type'] && !headers['content-type']) headers['Content-Type'] = 'application/json';
          }
          return JSON.parse(__orbSflxNative.http(method, finalUrl, JSON.stringify(headers || {}), bodyValue == null ? null : String(bodyValue)));
        }
        var http = {
          get: function(url, options) { return __orbHttp('GET', url, null, options); },
          post: function(url, body, options) { return __orbHttp('POST', url, body, options); },
          put: function(url, body, options) { return __orbHttp('PUT', url, body, options); },
          patch: function(url, body, options) { return __orbHttp('PATCH', url, body, options); },
          delete: function(url, options) { return __orbHttp('DELETE', url, null, options); },
          request: function(url, options) {
            if (url && typeof url === 'object') { options = url; url = options.url; }
            options = options || {};
            return __orbHttp(String(options.method || 'GET').toUpperCase(), String(url || options.url || ''), options.body, options);
          },
          clearCookies: function() { __orbSflxNative.clearCookies(); return true; }
        };
        function fetch(url, options) {
          options = options || {};
          var r = __orbHttp(String(options.method || 'GET').toUpperCase(), String(url), options.body, options);
          r.status = r.statusCode;
          r.statusText = r.statusText || '';
          r.url = String(url);
          r.text = function() { return r.body || ''; };
          r.json = function() { return JSON.parse(r.body || 'null'); };
          r.arrayBuffer = function() {
            var s = unescape(encodeURIComponent(r.body || '')), out = [];
            for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i));
            return out;
          };
          return r;
        }

        var __ORB_SFLX_MISSING = { marker: 'missing' };
        var storage = {
          get: function(key, fallback) {
            var raw = __orbSflxNative.storageGet(String(key));
            if (raw === '__ORB_SFLX_NULL__') return fallback === undefined ? null : fallback;
            try { return JSON.parse(raw); } catch (_) { return raw; }
          },
          set: function(key, value) { __orbSflxNative.storageSet(String(key), JSON.stringify(value)); return true; },
          remove: function(key) { __orbSflxNative.storageRemove(String(key)); return true; },
          clear: function() { __orbSflxNative.storageClear(); return true; }
        };
        var credentials = {
          get: function(key, fallback) {
            var raw = __orbSflxNative.credentialGet(String(key));
            if (raw === '__ORB_SFLX_NULL__') return fallback === undefined ? null : fallback;
            try { return JSON.parse(raw); } catch (_) { return raw; }
          },
          set: function(key, value) { __orbSflxNative.credentialSet(String(key), JSON.stringify(value)); return true; },
          store: function(key, value) { __orbSflxNative.credentialSet(String(key), JSON.stringify(value)); return true; },
          has: function(key) { return __orbSflxNative.credentialGet(String(key)) !== '__ORB_SFLX_NULL__'; },
          remove: function(key) { __orbSflxNative.credentialRemove(String(key)); return true; },
          clear: function() { __orbSflxNative.credentialClear(); return true; }
        };
        var file = {
          exists: function(path) { return __orbSflxNative.fileExists(String(path)); },
          read: function(path) { return __orbSflxNative.fileRead(String(path)); },
          readBytes: function(path, options) { return JSON.parse(__orbSflxNative.fileReadBytes(String(path), JSON.stringify(options || {}))); },
          write: function(path, data) { return __orbSflxNative.fileWrite(String(path), String(data)); },
          writeBytes: function(path, data, options) { return JSON.parse(__orbSflxNative.fileWriteBytes(String(path), String(data || ''), JSON.stringify(options || {}))); },
          delete: function(path) { return __orbSflxNative.fileDelete(String(path)); },
          download: function(url, path, options) {
            options = options || {};
            return JSON.parse(__orbSflxNative.fileDownload(String(url), String(path), JSON.stringify(options.headers || {})));
          },
          downloadSegments: function(segments, path, options) {
            options = options || {};
            var result = JSON.parse(__orbSflxNative.fileDownloadSegments(JSON.stringify(segments || []), String(path), JSON.stringify(options)));
            if (typeof options.onProgress === 'function' && result && result.success) {
              try { options.onProgress(Number(result.size || result.bytesWritten || 0), 0, Number(result.segments || 0), Number(result.segments || 0)); } catch (_) {}
            }
            return result;
          },
          getSize: function(path) { return JSON.parse(__orbSflxNative.fileGetSize(String(path))); },
          copy: function(source, target) { return JSON.parse(__orbSflxNative.fileCopy(String(source), String(target))); },
          move: function(source, target) { return JSON.parse(__orbSflxNative.fileMove(String(source), String(target))); },
          transformPatternedBlocks: function(inputPath, outputPath, options, onProgress) {
            var result = JSON.parse(__orbSflxNative.fileTransformPatternedBlocks(String(inputPath), String(outputPath), JSON.stringify(options || {})));
            if (typeof onProgress === 'function' && result && result.success) {
              try { onProgress(Number(result.bytes_processed || result.bytesProcessed || 0), Number(result.bytes_processed || result.bytesProcessed || 0)); } catch (_) {}
            }
            return result;
          }
        };
        var session = undefined;
        if (typeof __orbSflxNative.sessionSignedFetch === 'function') {
          session = {
            signedFetch: function(method, path, body, headers) {
              var bodyValue = body;
              if (bodyValue !== null && bodyValue !== undefined && typeof bodyValue !== 'string') bodyValue = JSON.stringify(bodyValue);
              return JSON.parse(__orbSflxNative.sessionSignedFetch(
                String(method || 'GET'),
                String(path || ''),
                bodyValue === null || bodyValue === undefined ? null : String(bodyValue),
                JSON.stringify(headers || {})
              ));
            },
            completeGrant: function(grant) { return JSON.parse(__orbSflxNative.sessionCompleteGrant(grant == null ? '' : String(grant))); },
            status: function() { return JSON.parse(__orbSflxNative.sessionStatus()); },
            clear: function() { return JSON.parse(__orbSflxNative.sessionClear()); }
          };
        }
        var utils = {
          parseJSON: function(v) { return JSON.parse(v); },
          stringifyJSON: function(v) { return JSON.stringify(v); },
          base64Encode: function(v) { return __orbSflxNative.base64Encode(String(v)); },
          base64Decode: function(v) { return __orbSflxNative.base64Decode(String(v)); },
          md5: function(v) { return __orbSflxNative.hash('md5', String(v)); },
          sha256: function(v) { return __orbSflxNative.hash('sha256', String(v)); },
          hmacSHA256: function(message, key) { return __orbSflxNative.hmac('sha256', String(key), String(message), false); },
          hmacSHA256Base64: function(message, key) { return __orbSflxNative.hmac('sha256', String(key), String(message), true); },
          hmacSHA1: function(keyBytes, messageBytes) { return JSON.parse(__orbSflxNative.hmacBytes('sha1', JSON.stringify(keyBytes || []), JSON.stringify(messageBytes || []))); }
        };
        var gobackend = {
          getLocalTime: function() { return JSON.parse(__orbSflxNative.localTime()); },
          sanitizeFilename: function(value) { return String(value || '').replace(/[\\/:*?\"<>|]/g, '_').replace(/\s+/g, ' ').trim(); },
          getAudioQuality: function(value) { return value && (value.quality || value.audio_quality || value.audioQuality) || null; },
          buildFilename: function(track) { var a = track && (track.artist_name || track.artist || ''); var t = track && (track.track_name || track.title || track.name || 'track'); return gobackend.sanitizeFilename((a ? a + ' - ' : '') + t); }
        };
        var settings = { qualitySettings: {} };
        var auth = {
          isAuthenticated: function() { return false; },
          getToken: function() { return null; },
          logout: function() { return true; }
        };
        var log = {
          debug: function() { console.log.apply(console, arguments); },
          info: function() { console.info.apply(console, arguments); },
          warn: function() { console.warn.apply(console, arguments); },
          error: function() { console.error.apply(console, arguments); }
        };
        function btoa(v) { return __orbSflxNative.base64Encode(String(v)); }
        function atob(v) { return __orbSflxNative.base64Decode(String(v)); }
        if (typeof URLSearchParams === 'undefined') {
          function URLSearchParams(init) { this._pairs = []; if (typeof init === 'string') { var q = init.replace(/^\?/, ''); if (q) { var self = this; q.split('&').forEach(function(p) { var i=p.indexOf('='); self.append(decodeURIComponent(i<0?p:p.slice(0,i)), decodeURIComponent(i<0?'':p.slice(i+1))); }); } } else if (init && typeof init === 'object') { var self=this; Object.keys(init).forEach(function(k){ self.append(k, init[k]); }); } }
          URLSearchParams.prototype.append = function(k,v) { this._pairs.push([String(k), String(v)]); };
          URLSearchParams.prototype.set = function(k,v) { this.delete(k); this.append(k,v); };
          URLSearchParams.prototype.get = function(k) { k=String(k); for(var i=0;i<this._pairs.length;i++) if(this._pairs[i][0]===k) return this._pairs[i][1]; return null; };
          URLSearchParams.prototype.has = function(k) { return this.get(k) !== null; };
          URLSearchParams.prototype.delete = function(k) { k=String(k); this._pairs=this._pairs.filter(function(p){return p[0]!==k;}); };
          URLSearchParams.prototype.toString = function() { return this._pairs.map(function(p){return encodeURIComponent(p[0])+'='+encodeURIComponent(p[1]);}).join('&'); };
        }
        if (typeof URL === 'undefined') {
          function URL(value, base) { var text=String(value); if(base && !/^[a-z]+:\/\//i.test(text)) { var b=String(base); text=b.replace(/\/[^\/]*$/, '/')+text.replace(/^\//,''); } this.href=text; var m=text.match(/^([a-z]+:)?\/\/([^\/]+)([^?#]*)(\?[^#]*)?(#.*)?$/i); this.protocol=m&&m[1]||''; this.host=m&&m[2]||''; this.hostname=this.host.split(':')[0]; this.pathname=m&&m[3]||''; this.search=m&&m[4]||''; this.hash=m&&m[5]||''; this.searchParams=new URLSearchParams(this.search); }
          URL.prototype.toString=function(){return this.href;};
        }
        if (typeof TextEncoder === 'undefined') {
          function TextEncoder() {}
          TextEncoder.prototype.encode = function(value) {
            var s = unescape(encodeURIComponent(String(value))), out = [];
            for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i));
            return out;
          };
        }
        if (typeof TextDecoder === 'undefined') {
          function TextDecoder() {}
          TextDecoder.prototype.decode = function(value) {
            var s = '';
            for (var i = 0; i < value.length; i++) s += String.fromCharCode(value[i]);
            try { return decodeURIComponent(escape(s)); } catch (_) { return s; }
          };
        }
        'ok';
    """.trimIndent()
}
