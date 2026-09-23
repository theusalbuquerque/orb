package com.music.orb.data.sources.sflx

import android.content.Context
import com.music.orb.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Installs and updates the SFLX providers Orb knows how to host. */
object SflxPackageManager {
    const val TIDAL_PROVIDER_ID = "tidal-web"
    const val QOBUZ_PROVIDER_ID = "qobuz-web"

    /** Compatibility alias for older TIDAL-only call sites. */
    const val PROVIDER_ID = TIDAL_PROVIDER_ID

    const val REGISTRY_URL =
        "https://raw.githubusercontent.com/zarzet/SpotiFLAC-Extension/main/registry.json"

    private const val QOBUZ_BUNDLED_ASSET = "sflx/qobuz-web.sflx"
    private const val TAG = "SflxPackage"
    private const val MAX_PACKAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 256
    private const val MAX_MANIFEST_BYTES = 128 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class RegistryEntry(
        val id: String,
        val displayName: String,
        val version: String,
        val description: String,
        val downloadUrl: String,
        val sha256: String,
        val tags: List<String>,
    )

    data class InstalledPackage(
        val root: File,
        val manifest: SflxManifest,
        val indexJs: String,
        val registrySha256: String?,
    )

    data class Status(
        val installedVersion: String?,
        val latestVersion: String?,
        val updateAvailable: Boolean,
        val catalogReachable: Boolean,
        val unsupportedRuntimeFeatures: List<String> = emptyList(),
    )

    fun isInstalled(context: Context, providerId: String = TIDAL_PROVIDER_ID): Boolean =
        File(providerDir(context, providerId), "manifest.json").isFile &&
            File(providerDir(context, providerId), "index.js").isFile

    fun installedVersion(context: Context, providerId: String = TIDAL_PROVIDER_ID): String? =
        runCatching { loadInstalled(context, providerId)?.manifest?.version }.getOrNull()

    fun loadInstalled(
        context: Context,
        providerId: String = TIDAL_PROVIDER_ID,
    ): InstalledPackage? {
        val root = providerDir(context, providerId)
        val manifestFile = File(root, "manifest.json")
        val indexFile = File(root, "index.js")
        if (!manifestFile.isFile || !indexFile.isFile) return null
        require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "SFLX manifest is too large" }
        val manifest = SflxManifest.parse(manifestFile.readText())
        require(manifest.name == providerId) {
            "Installed SFLX ${manifest.name} does not match requested provider $providerId"
        }
        require(manifest.isDownloadProvider) { "Installed $providerId SFLX cannot provide downloads" }
        return InstalledPackage(
            root = root,
            manifest = manifest,
            indexJs = indexFile.readText(),
            registrySha256 = File(root, ".orb-registry-sha256").takeIf(File::isFile)?.readText()?.trim(),
        )
    }

    suspend fun status(
        context: Context,
        providerId: String = TIDAL_PROVIDER_ID,
    ): Status = withContext(Dispatchers.IO) {
        val installed = installedVersion(context, providerId)
        val latest = runCatching { fetchRegistryEntry(providerId) }.getOrNull()
        val bundledAvailable = providerId == QOBUZ_PROVIDER_ID && bundledAssetAvailable(context)
        val unsupported = runCatching {
            loadInstalled(context, providerId)?.manifest?.let { SflxRuntime.unsupportedFeatures(it) }.orEmpty()
        }.getOrDefault(emptyList())
        Status(
            installedVersion = installed,
            latestVersion = latest?.version,
            updateAvailable = installed != null && latest != null && compareVersions(latest.version, installed) > 0,
            catalogReachable = latest != null || bundledAvailable,
            unsupportedRuntimeFeatures = unsupported,
        )
    }

    suspend fun fetchRegistryEntry(providerId: String = TIDAL_PROVIDER_ID): RegistryEntry =
        withContext(Dispatchers.IO) {
            val body = getHttpsText(REGISTRY_URL, maxBytes = 512 * 1024)
            val extensions = JSONObject(body).getJSONArray("extensions")
            for (index in 0 until extensions.length()) {
                val item = extensions.getJSONObject(index)
                if (item.optString("id") != providerId) continue
                val url = item.getString("download_url").trim()
                val sha = item.getString("sha256").lowercase().trim()
                require(url.startsWith("https://")) { "SFLX registry returned a non-HTTPS package URL" }
                require(sha.matches(Regex("^[0-9a-f]{64}$"))) { "SFLX registry returned an invalid SHA-256" }
                return@withContext RegistryEntry(
                    id = providerId,
                    displayName = item.optString("display_name", providerId),
                    version = item.getString("version"),
                    description = item.optString("description"),
                    downloadUrl = url,
                    sha256 = sha,
                    tags = buildList {
                        val tags = item.optJSONArray("tags")
                        if (tags != null) for (tagIndex in 0 until tags.length()) add(tags.optString(tagIndex))
                    },
                )
            }
            error("$providerId SFLX is not present in the official registry")
        }

    /**
     * Installs the newest registry package when available. Qobuz also has a
     * bundled package supplied with Orb, used as the offline/bootstrap source.
     */
    suspend fun installLatest(
        context: Context,
        providerId: String = TIDAL_PROVIDER_ID,
    ): InstalledPackage = withContext(Dispatchers.IO) {
        val entry = runCatching { fetchRegistryEntry(providerId) }.getOrNull()
        if (entry == null) {
            if (providerId == QOBUZ_PROVIDER_ID) {
                return@withContext installBundledQobuz(context)
            }
            error("$providerId SFLX is not available from the registry")
        }
        val bytes = getHttpsBytes(entry.downloadUrl, MAX_PACKAGE_BYTES)
        val digest = sha256(bytes)
        require(digest == entry.sha256) { "$providerId SFLX integrity check failed (SHA-256 mismatch)" }
        installBytes(
            context = context,
            providerId = providerId,
            bytes = bytes,
            expectedVersion = entry.version,
            registrySha256 = entry.sha256,
        )
    }

    suspend fun ensureBundledQobuzInstalled(context: Context): InstalledPackage = withContext(Dispatchers.IO) {
        loadInstalled(context, QOBUZ_PROVIDER_ID) ?: installBundledQobuz(context)
    }

    suspend fun installBundledQobuz(context: Context): InstalledPackage = withContext(Dispatchers.IO) {
        val bytes = context.assets.open(QOBUZ_BUNDLED_ASSET).use { input ->
            val data = input.readBytes()
            require(data.size <= MAX_PACKAGE_BYTES) { "Bundled Qobuz SFLX exceeds the safety limit" }
            data
        }
        installBytes(
            context = context,
            providerId = QOBUZ_PROVIDER_ID,
            bytes = bytes,
            expectedVersion = null,
            registrySha256 = null,
        )
    }

    private fun bundledAssetAvailable(context: Context): Boolean = runCatching {
        context.assets.open(QOBUZ_BUNDLED_ASSET).use { true }
    }.getOrDefault(false)

    private fun installBytes(
        context: Context,
        providerId: String,
        bytes: ByteArray,
        expectedVersion: String?,
        registrySha256: String?,
    ): InstalledPackage {
        val base = baseDir(context)
        val staging = File(base, ".$providerId-${System.nanoTime()}.tmp")
        if (staging.exists()) staging.deleteRecursively()
        require(staging.mkdirs()) { "Could not create SFLX staging directory" }

        try {
            extractSafely(bytes, staging)
            val manifestFile = File(staging, "manifest.json")
            val indexFile = File(staging, "index.js")
            require(manifestFile.isFile && indexFile.isFile) {
                "SFLX must contain manifest.json and index.js at the archive root"
            }
            require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "SFLX manifest is too large" }

            val manifest = SflxManifest.parse(manifestFile.readText())
            require(manifest.name == providerId) { "SFLX package is ${manifest.name}, expected $providerId" }
            if (expectedVersion != null) {
                require(manifest.version == expectedVersion) { "SFLX package version does not match the registry" }
            }
            require(manifest.isDownloadProvider) { "$providerId SFLX does not declare download_provider" }
            val unsupported = SflxRuntime.unsupportedFeatures(manifest)
            require(unsupported.isEmpty()) {
                "$providerId SFLX ${manifest.version} requires a newer Orb SFLX runtime (${unsupported.joinToString()})"
            }
            if (registrySha256 != null) File(staging, ".orb-registry-sha256").writeText(registrySha256)
            File(staging, ".orb-package-sha256").writeText(sha256(bytes))

            val destination = providerDir(context, providerId)
            val backup = File(base, ".$providerId.backup")
            backup.deleteRecursively()
            if (destination.exists() && !destination.renameTo(backup)) {
                error("Could not stage the previous $providerId SFLX installation")
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                error("Could not publish the $providerId SFLX installation")
            }
            backup.deleteRecursively()

            TrackLog.d(TAG, "Installed $providerId ${manifest.version}")
            return loadInstalled(context, providerId) ?: error("$providerId SFLX disappeared after installation")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun extractSafely(bytes: ByteArray, root: File) {
        val rootPath = root.canonicalPath + File.separator
        val seen = HashSet<String>()
        var count = 0
        var total = 0L

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRY_COUNT) { "SFLX contains too many files" }
                val name = entry.name.replace('\\', '/')
                require(name.isNotBlank()) { "SFLX contains an empty path" }
                require(!name.startsWith('/')) { "Absolute SFLX archive paths are not allowed" }
                require(!name.startsWith("../") && !name.contains("/../") && name != "..") {
                    "Unsafe SFLX archive path"
                }
                require(seen.add(name)) { "SFLX contains duplicate archive paths" }

                val output = File(root, name).canonicalFile
                require(output.path == root.canonicalPath || output.path.startsWith(rootPath)) {
                    "Unsafe SFLX archive path"
                }
                if (entry.isDirectory) {
                    require(output.mkdirs() || output.isDirectory) { "Could not create SFLX directory" }
                } else {
                    output.parentFile?.let { require(it.mkdirs() || it.isDirectory) }
                    FileOutputStream(output).use { target ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= MAX_EXTRACTED_BYTES) { "SFLX extracted size exceeds the safety limit" }
                            target.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun baseDir(context: Context): File = File(context.filesDir, "sflx").also {
        require(it.mkdirs() || it.isDirectory) { "Could not create SFLX directory" }
    }

    private fun providerDir(context: Context, providerId: String) = File(baseDir(context), providerId)

    private fun getHttpsText(url: String, maxBytes: Int): String =
        getHttpsBytes(url, maxBytes).toString(Charsets.UTF_8)

    private fun getHttpsBytes(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://")) { "Only HTTPS is allowed for SFLX packages" }
        var current = url
        repeat(5) {
            val request = Request.Builder().url(current).header("User-Agent", "Orb/1.0 SFLX").get().build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val next = response.header("Location") ?: error("SFLX redirect has no Location")
                    val resolved = response.request.url.resolve(next) ?: error("Invalid SFLX redirect")
                    require(resolved.isHttps) { "SFLX download redirected outside HTTPS" }
                    current = resolved.toString()
                    return@repeat
                }
                require(response.isSuccessful) { "HTTP ${response.code} while downloading SFLX data" }
                val body = response.body ?: error("Empty SFLX response")
                val length = body.contentLength()
                require(length < 0 || length <= maxBytes) { "SFLX response exceeds the safety limit" }
                val bytes = body.bytes()
                require(bytes.size <= maxBytes) { "SFLX response exceeds the safety limit" }
                return bytes
            }
        }
        error("Too many SFLX redirects")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return left.compareTo(right)
    }
}
