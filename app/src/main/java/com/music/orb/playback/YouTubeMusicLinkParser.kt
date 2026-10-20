package com.music.orb.playback

import android.net.Uri
import java.net.URI

/** Recognizes shared track links without requiring a YouTube Music login. */
object YouTubeMusicLinkParser {
    private val validVideoId = Regex("^[A-Za-z0-9_-]{11}$")

    fun videoId(uri: Uri?): String? = videoId(uri?.toString())

    /** Pure parser also used by unit tests without Android framework mocks. */
    fun videoId(link: String?): String? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if ((uri.scheme != "https" && uri.scheme != "http") ||
            !uri.host.equals("music.youtube.com", ignoreCase = true) ||
            uri.path != "/watch"
        ) return null
        val value = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == "v" }
            ?.substringAfter('=', missingDelimiterValue = "")
        return value?.takeIf(validVideoId::matches)
    }
}
