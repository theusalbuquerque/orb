package com.music.orb.playback

/** Explicit files opened from Downloads are not resolved here; they carry a local URI. */
internal fun shouldUseSavedFileForStreaming(online: Boolean, preferDownloadsOnline: Boolean): Boolean =
    !online || preferDownloadsOnline

/** Local tracks may play offline; musical Automix planning needs a usable network. */
internal fun canRunAutomixOnNetwork(online: Boolean): Boolean = online
