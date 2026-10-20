package com.music.orb.data.spotify

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.data.settings.AccountSettingsSync
import com.music.orb.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credentials stay encrypted on this device and are scoped to the active Orb account. */
object SpotifySession {
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var owner = ""
    private val initMutex = Mutex()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    suspend fun init(context: Context) = initMutex.withLock {
        // Keystore and Tink setup must not block input dispatch on the UI thread.
        val nextOwner = withContext(Dispatchers.IO) {
            if (prefs == null) prefs = EncryptedSharedPreferences.create(
                context.applicationContext, "orb_spotify_sessions",
                MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            OrbGoogleAuth.storedUniqueId().orEmpty()
        }
        withContext(Dispatchers.Main.immediate) {
            if (owner != nextOwner) { SpotifyLibraryShelfStore.reset(); SpotifyToken.invalidate(); owner = nextOwner }
            SpotifyToken.init(context)
        }
        val connectedNow = withContext(Dispatchers.IO) {
            prefs?.edit()?.remove("oauth:$owner")?.apply()
            cookie().isNotBlank()
        }
        _connected.value = connectedNow
    }
    fun ownerKey(): String = owner.takeIf { stored ->
        OrbGoogleAuth.storedUniqueId().orEmpty() == stored
    }.orEmpty()
    fun cookie(): String = if (ownerKey().isBlank()) "" else prefs?.getString("cookie:$owner", "").orEmpty()
    fun save(cookie: String) {
        check(ownerKey().isNotBlank())
        if (cookie() == cookie) {
            _connected.value = cookie.isNotBlank()
            return
        }
        checkNotNull(prefs).edit().putString("cookie:$owner", cookie).remove("oauth:$owner").apply()
        SpotifyLibraryShelfStore.reset()
        SpotifyToken.invalidate()
        _connected.value = cookie.isNotBlank()
        // Only the active Orb account can back up this cookie. AccountSettingsSync
        // restores it from a per-user encrypted Supabase Vault RPC, never from
        // the public preferences JSON. A disconnect also revokes the cloud copy.
        AccountSettingsSync.notifyIntegrationCredentialChanged("spotify")
    }
    fun disconnect() {
        save("")
        android.webkit.CookieManager.getInstance().apply {
            setCookie("https://open.spotify.com", "sp_dc=; Domain=.spotify.com; Path=/; Max-Age=0; Secure")
            flush()
        }
    }
}
