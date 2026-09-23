package com.music.orb.data.update

import android.content.Context
import com.music.orb.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object BetaUpdateDownloader {

    private const val DIRECTORY = "updates"
    private const val MIN_APK_BYTES = 100 * 1024L

    suspend fun download(context: Context, release: OrbRelease): File = withContext(Dispatchers.IO) {
        val directory = updateDirectory(context)
        directory.mkdirs()

        val name = safeName(release)
        val destination = File(directory, name)
        val partial = File(directory, "$name.part")

        if (destination.isValidArchive()) return@withContext destination

        destination.delete()
        partial.delete()

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "Orb/${BuildConfig.VERSION_NAME}")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("APK download returned HTTP $status")

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            UpdateDownloadStore.begin(release, totalBytes)
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (UpdateDownloadStore.isCancelRequested(release.tagName)) {
                            throw UpdateDownloadCancelledException()
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        UpdateDownloadStore.progress(release, downloaded, totalBytes)
                    }
                    output.fd.sync()
                }
            }

            if (!partial.isValidArchive()) throw IOException("Downloaded file is not an APK archive")

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }

            directory.listFiles()
                ?.filter { it.isFile && it != destination }
                ?.forEach(File::delete)

            destination
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun existing(context: Context, release: OrbRelease): File? =
        File(updateDirectory(context), safeName(release)).takeIf { it.isValidArchive() }

    fun byName(context: Context, name: String): File? {
        if (name.isBlank()) return null
        val directory = updateDirectory(context).canonicalFile
        val candidate = File(directory, name).canonicalFile
        if (!candidate.path.startsWith(directory.path + File.separator)) return null
        return candidate.takeIf { it.isValidArchive() }
    }

    fun clear(context: Context) {
        updateDirectory(context).listFiles()?.forEach(File::delete)
    }

    private fun updateDirectory(context: Context) = File(context.filesDir, DIRECTORY)

    private fun safeName(release: OrbRelease): String {
        val fallback = "Orb-${release.channel.wireName}-${release.tagName}.apk"
        val source = release.apkName.takeIf { it.endsWith(".apk", true) } ?: fallback
        return source.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }

    private fun File.isValidArchive(): Boolean {
        if (!isFile || length() < MIN_APK_BYTES) return false
        return runCatching {
            inputStream().use { input -> input.read() == 0x50 && input.read() == 0x4B }
        }.getOrDefault(false)
    }
}
