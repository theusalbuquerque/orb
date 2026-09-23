package com.music.orb.data.social

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Restores Supabase auth and synchronizes coarse regional profile metadata. */
object SocialSessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    @Volatile
    var authenticated: Boolean = false
        private set

    fun start(context: Context) {
        if (!OrbSupabase.configured || !started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            OrbSupabase.client.auth.sessionStatus.collectLatest { status ->
                authenticated = status is SessionStatus.Authenticated
                if (authenticated) {
                    // Populate the local profile snapshot first. updateRegionalInfo
                    // then writes only if country/language/locale actually changed.
                    runCatching { SocialRepository.myProfile() }
                    runCatching {
                        SocialRepository.updateRegionalInfo(UserRegionalInfo.detect(appContext)).getOrThrow()
                    }
                } else {
                    SocialRepository.clearMyProfileState()
                }
            }
        }
    }
}
