package com.music.orb.data.innertube

/** Keep anonymous catalogue fallbacks separate from account-owned reads. */
internal object MusicBrowseAccessPolicy {
    fun maySend(authenticatedOnly: Boolean, hasOAuth: Boolean, hasCookie: Boolean): Boolean =
        !authenticatedOnly || hasOAuth || hasCookie

    fun mayFallbackAnonymously(authenticatedOnly: Boolean, allowAnonymousFallback: Boolean): Boolean =
        !authenticatedOnly && allowAnonymousFallback
}
