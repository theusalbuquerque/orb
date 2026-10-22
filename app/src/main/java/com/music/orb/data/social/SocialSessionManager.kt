package com.music.orb.data.social

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // The cached Google identity is not proof that a Supabase access token has
    // finished loading. Expose the authenticated UUID to reactive consumers.
    private val _authenticatedUserId = MutableStateFlow<String?>(null)
    val authenticatedUserId: StateFlow<String?> = _authenticatedUserId.asStateFlow()

    fun start(context: Context) {
        if (!OrbSupabase.configured || !started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            OrbSupabase.client.auth.sessionStatus.collectLatest { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val userId = OrbSupabase.client.auth.currentUserOrNull()?.id
                        authenticated = userId != null
                        _authenticatedUserId.value = userId
                        if (userId != null) {
                            var retryMs = 1_000L
                            while (SocialRepository.currentUserId() == userId) {
                                try {
                                    val profile = SocialRepository.myProfile()
                                    if (profile != null) {
                                        runCatching {
                                            SocialRepository.updateRegionalInfo(
                                                UserRegionalInfo.detect(appContext)
                                            ).getOrThrow()
                                        }.onFailure { error ->
                                            if (error is CancellationException) throw error
                                            Log.w(TAG, "Regional metadata sync failed", error)
                                        }
                                        break
                                    }
                                    Log.w(TAG, "Authenticated profile missing; retrying")
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.w(TAG, "Supabase profile fetch failed; retrying", error)
                                }
                                delay(retryMs)
                                retryMs = (retryMs * 2).coerceAtMost(30_000L)
                            }
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        authenticated = false
                        _authenticatedUserId.value = null
                        SocialRepository.clearMyProfileState()
                    }
                    // LoadingFromStorage / NetworkError do not mean sign-out.
                    else -> Unit
                }
            }
        }
    }

    private const val TAG = "OrbSocialSession"
}
