package com.music.orb

import com.music.orb.playback.canRunAutomixOnNetwork
import com.music.orb.playback.shouldUseSavedFileForStreaming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaybackPolicyTest {
    @Test fun offlineAlwaysUsesSavedFile() {
        assertTrue(shouldUseSavedFileForStreaming(online = false, preferDownloadsOnline = false))
        assertTrue(shouldUseSavedFileForStreaming(online = false, preferDownloadsOnline = true))
    }

    @Test fun onlinePlaybackObeysPreference() {
        assertFalse(shouldUseSavedFileForStreaming(online = true, preferDownloadsOnline = false))
        assertTrue(shouldUseSavedFileForStreaming(online = true, preferDownloadsOnline = true))
    }

    @Test fun automixRequiresValidatedInternet() {
        assertFalse(canRunAutomixOnNetwork(online = false))
        assertTrue(canRunAutomixOnNetwork(online = true))
    }
}
