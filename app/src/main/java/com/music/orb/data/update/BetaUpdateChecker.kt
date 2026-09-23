package com.music.orb.data.update

import android.util.Log
import com.music.orb.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateChannel(val wireName: String) {
    BETA("beta"),
    STABLE("stable");

    companion object {
        fun fromWire(value: String?): UpdateChannel? = entries.firstOrNull {
            it.wireName.equals(value, ignoreCase = true)
        }
    }
}

data class OrbRelease(
    val channel: UpdateChannel,
    val tagName: String,
    val displayName: String,
    val releaseUrl: String,
    val apkUrl: String,
    val apkName: String,
    /** Raw GitHub release body. The UI strips Markdown presentation syntax. */
    val releaseNotes: String = "",
    /** First image embedded in the release body, used as the update hero when present. */
    val heroImageUrl: String? = null,
)

/**
 * GitHub Releases is the single update source for both channels.
 *
 * BETA   -> draft=false && prerelease=true
 * STABLE -> draft=false && prerelease=false
 *
 * Only releases carrying an APK asset are eligible.
 */
object BetaUpdateChecker {

    private const val TAG = "OrbUpdateChecker"
    private const val DEFAULT_REPOSITORY = "theusalbuquerque/orb"

    suspend fun latest(channel: UpdateChannel): OrbRelease? = withContext(Dispatchers.IO) {
        val repository = BuildConfig.GITHUB_REPOSITORY
            .trim()
            .trim('/')
            .ifBlank { DEFAULT_REPOSITORY }

        if ('/' !in repository) {
            Log.e(TAG, "Invalid GitHub repository configured: $repository")
            return@withContext null
        }

        Log.i(TAG, "Checking GitHub channel=${channel.wireName} repository=$repository")
        val connection = open(
            "https://api.github.com/repos/$repository/releases?per_page=40",
        )

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "GitHub returned HTTP $status for ${channel.wireName}")
                if (status == 403 || status == 429 || status >= 500) {
                    throw IOException("GitHub returned HTTP $status")
                }
                return@withContext null
            }

            val body = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val releases = JSONArray(body)
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue

                val prerelease = release.optBoolean("prerelease", false)
                if (channel == UpdateChannel.BETA && !prerelease) continue
                if (channel == UpdateChannel.STABLE && prerelease) continue

                val tag = release.optString("tag_name").trim()
                val page = release.optString("html_url").trim()
                if (tag.isBlank() || page.isBlank()) continue

                val asset = chooseApk(release.optJSONArray("assets"), channel) ?: continue
                val apkUrl = asset.optString("browser_download_url").trim()
                val apkName = asset.optString("name").trim()
                if (apkUrl.isBlank() || apkName.isBlank()) continue

                val releaseNotes = release.optString("body").trim()
                return@withContext OrbRelease(
                    channel = channel,
                    tagName = tag,
                    displayName = release.optString("name").trim().ifBlank { tag },
                    releaseUrl = page,
                    apkUrl = apkUrl,
                    apkName = apkName,
                    releaseNotes = releaseNotes,
                    heroImageUrl = firstReleaseImage(releaseNotes),
                ).also {
                    Log.i(TAG, "Found ${channel.wireName} release tag=$tag asset=$apkName")
                }
            }

            Log.i(TAG, "No eligible ${channel.wireName} release with an APK asset was found")
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Cheap pre-download filter. The APK's real versionCode is still checked
     * after download, which is the authority Android itself uses.
     */
    fun looksNewerThanInstalled(release: OrbRelease): Boolean =
        looksNewerThanInstalled(release.tagName)

    /**
     * Update prompts are fail-closed: if either version cannot be compared
     * reliably, Orb does not claim that an update exists. This avoids stale or
     * malformed GitHub tags showing a banner to users already on the latest
     * build. APK versionCode validation remains the final authority after an
     * explicit user-initiated download.
     */
    fun looksNewerThanInstalled(remoteVersion: String): Boolean {
        val remote = ParsedVersion.parse(remoteVersion)
        val local = ParsedVersion.parse(BuildConfig.VERSION_NAME)
        if (remote == null || local == null) {
            Log.w(
                TAG,
                "Cannot compare remote=$remoteVersion with installed=${BuildConfig.VERSION_NAME}; suppressing update prompt",
            )
            return false
        }
        return remote > local
    }

    private fun firstReleaseImage(markdown: String): String? {
        if (markdown.isBlank()) return null
        val markdownImage = Regex("""!\[[^]]*]\((https?://[^)\s]+)\)""")
            .find(markdown)
            ?.groupValues
            ?.getOrNull(1)
        if (!markdownImage.isNullOrBlank()) return markdownImage
        return Regex("""<img[^>]+src=[\"'](https?://[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(markdown)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun chooseApk(assets: JSONArray?, channel: UpdateChannel): JSONObject? {
        if (assets == null) return null

        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name").trim()
                val url = asset.optString("browser_download_url").trim()
                if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
                add(asset)
            }
        }
        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { asset ->
            val name = asset.optString("name").lowercase()
            var score = 0

            if ("universal" in name) score += 30
            when (channel) {
                UpdateChannel.BETA -> if ("beta" in name || "dev" in name || "pre" in name) score += 100
                UpdateChannel.STABLE -> {
                    if ("stable" in name || "release" in name || "prod" in name) score += 100
                    if ("beta" in name || "dev" in name || "pre" in name) score -= 150
                }
            }
            score
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Orb/${BuildConfig.VERSION_NAME}")
        }

    private data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val build: Int,
        val prerelease: Boolean,
        val prereleaseRevision: Int,
    ) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int {
            compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
            compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
            compareValues(build, other.build).takeIf { it != 0 }?.let { return it }

            if (prerelease != other.prerelease) {
                return if (prerelease) -1 else 1
            }
            return prereleaseRevision.compareTo(other.prereleaseRevision)
        }

        companion object {
            fun parse(raw: String): ParsedVersion? {
                val normalized = raw.trim().lowercase().removePrefix("v")
                val match = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:\.(\d+))?""")
                    .find(normalized) ?: return null

                val qualifier = normalized.substring(match.range.last + 1)
                val prerelease = qualifier.isNotBlank() && listOf(
                    "beta", "alpha", "rc", "pre", "preview", "dev", "snapshot",
                ).any { it in qualifier }
                val revision = Regex("""(?:beta|alpha|rc|pre|preview|dev|snapshot)[.-]?(\d+)""")
                    .find(qualifier)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: if (prerelease) 1 else 0

                return ParsedVersion(
                    major = match.groupValues[1].toInt(),
                    minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0,
                    patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0,
                    build = match.groupValues.getOrNull(4)?.toIntOrNull() ?: 0,
                    prerelease = prerelease,
                    prereleaseRevision = revision,
                )
            }
        }
    }
}
