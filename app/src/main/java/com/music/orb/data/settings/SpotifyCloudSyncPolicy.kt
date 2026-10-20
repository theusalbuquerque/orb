package com.music.orb.data.settings

/** Pure migration-safe reconciliation of the per-account Spotify session. */
internal object SpotifyCloudSyncPolicy {
    enum class Action { NONE, UPLOAD_LOCAL, RESTORE_CLOUD }

    /**
     * cloud == null: not migrated; never clear an existing session.
     * cloud == "": explicit disconnect made on another device.
     * The caller must resolve and verify the active Orb account before acting.
     */
    fun decide(local: String, cloud: String?): Action = when {
        cloud == null && local.isNotBlank() -> Action.UPLOAD_LOCAL
        cloud != null && cloud != local -> Action.RESTORE_CLOUD
        else -> Action.NONE
    }
}
