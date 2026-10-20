package com.music.orb

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** A packaged notification listener can trigger enhanced sideloading restrictions. */
class OrbLockManifestSafetyTest {
    @Test fun orbLockNeverDeclaresCrossAppNotificationAccess() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertFalse(manifest.contains("android.service.notification.NotificationListenerService"))
        assertFalse(manifest.contains("OrbLockNotificationListener"))
    }
}
