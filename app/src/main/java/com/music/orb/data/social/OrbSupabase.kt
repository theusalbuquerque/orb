package com.music.orb.data.social

import com.music.orb.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/** Single Supabase client backing Orb's social identity and database. */
object OrbSupabase {
    val baseUrl: String
        get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    val publishableKey: String
        get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    val configured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        check(configured) {
            "Supabase is not configured. Set ORB_SUPABASE_URL and ORB_SUPABASE_PUBLISHABLE_KEY."
        }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth) {
                // PlaybackService shares this process and may stay alive with no
                // foreground Activity. Keep the authenticated RLS session usable
                // so now-playing/history updates do not stop when the screen locks.
                enableLifecycleCallbacks = false
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            install(Postgrest)
            install(Realtime)
        }
    }
}
