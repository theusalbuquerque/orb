package com.music.orb

import com.music.orb.data.settings.SpotifyCloudSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyCloudSyncPolicyTest {
    @Test fun legacyLocalSessionSeedsNewVaultColumn() {
        assertEquals(
            SpotifyCloudSyncPolicy.Action.UPLOAD_LOCAL,
            SpotifyCloudSyncPolicy.decide("sp_dc_session", null),
        )
    }

    @Test fun freshDeviceRestoresExistingCloudSession() {
        assertEquals(
            SpotifyCloudSyncPolicy.Action.RESTORE_CLOUD,
            SpotifyCloudSyncPolicy.decide("", "sp_dc_session"),
        )
    }

    @Test fun noCredentialsAreFabricatedForNewAccounts() {
        assertEquals(
            SpotifyCloudSyncPolicy.Action.NONE,
            SpotifyCloudSyncPolicy.decide("", null),
        )
    }

    @Test fun unchangedCookieDoesNotCauseUploadLoop() {
        assertEquals(
            SpotifyCloudSyncPolicy.Action.NONE,
            SpotifyCloudSyncPolicy.decide("sp_dc_session", "sp_dc_session"),
        )
    }

    @Test fun RemoteDisconnectClearsDeviceCredential() {
        assertEquals(
            SpotifyCloudSyncPolicy.Action.RESTORE_CLOUD,
            SpotifyCloudSyncPolicy.decide("sp_dc_session", ""),
        )
    }
}
