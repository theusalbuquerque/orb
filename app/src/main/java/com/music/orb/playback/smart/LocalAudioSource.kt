package com.music.orb.playback.smart

import android.net.Uri

internal object LocalAudioSource {
    fun isLocal(uri: Uri): Boolean = uri.scheme == "content" || uri.scheme == "file"
}
