package com.music.orb.data.innertube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrowseAccessPolicyTest {
    @Test fun privateLibraryWithoutCredentialsCannotBeRequested() {
        assertFalse(MusicBrowseAccessPolicy.maySend(true, false, false))
    }

    @Test fun privateLibraryCanUseNativeOAuth() {
        assertTrue(MusicBrowseAccessPolicy.maySend(true, true, false))
    }

    @Test fun existingLegacySessionIsPreserved() {
        assertTrue(MusicBrowseAccessPolicy.maySend(true, false, true))
    }

    @Test fun publicCatalogCanBeFetchedAnonymously() {
        assertTrue(MusicBrowseAccessPolicy.maySend(false, false, false))
        assertTrue(MusicBrowseAccessPolicy.mayFallbackAnonymously(false, true))
    }

    @Test fun privateLibraryNeverFallsBackToPublicResults() {
        assertFalse(MusicBrowseAccessPolicy.mayFallbackAnonymously(true, true))
        assertFalse(MusicBrowseAccessPolicy.mayFallbackAnonymously(true, false))
    }

    @Test fun publicFallbackRespectsCallerPolicy() {
        assertFalse(MusicBrowseAccessPolicy.mayFallbackAnonymously(false, false))
    }
}
