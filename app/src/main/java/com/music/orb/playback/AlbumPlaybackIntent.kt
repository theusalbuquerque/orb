package com.music.orb.playback

import com.music.orb.data.model.Song

/** Only an explicitly selected release, launched in its authored order, is gapless. */
internal fun List<Song>.isOrderedAlbumQueue(): Boolean {
    if (size < 2) return false
    val ids = map { it.albumId?.trim().orEmpty() }
    // Album pages stamp their own browse id onto every row before play. Requiring
    // that explicit shared id avoids mistaking a playlist containing several
    // songs from one album for an authored album session.
    return all { it.preserveAlbumRelease && it.sourcePlaylistId == null } &&
        ids.all { it.isNotEmpty() } && ids.distinct().size == 1
}

