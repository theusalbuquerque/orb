package com.music.orb

import android.app.Application
import android.app.ActivityManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.music.orb.auth.AuthStore
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.playback.AudioCache
import com.music.orb.playback.DolbyAtmos
import com.music.orb.playback.LastPlayed
import com.music.orb.data.ArtistCreditResolver
import com.music.orb.data.innertube.Innertube
import com.music.orb.data.scrobbling.LastFM
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.HomeSuggestionStore
import com.music.orb.data.settings.ArtistRankingHistoryStore
import com.music.orb.data.settings.ArtistGenreStore
import com.music.orb.data.settings.ArtistPreferenceStore
import com.music.orb.data.settings.LikeStatusStore
import com.music.orb.data.settings.ProfileSnapshotStore
import com.music.orb.data.settings.StatsSnapshotStore
import com.music.orb.data.social.SocialSessionManager
import com.music.orb.data.stats.TrackLanguageResolver
import com.music.orb.data.settings.SearchHistory
import com.music.orb.data.settings.PlaylistListeningStore
import com.music.orb.data.settings.ProfilePrivacyStore
import com.music.orb.data.settings.RecentPlaybackStore
import com.music.orb.data.settings.AlbumExclusionStore
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.update.BetaUpdateScheduler
import com.music.orb.data.update.UpdateAvailableStore
import com.music.orb.data.update.UpdateReadyStore
import com.music.orb.download.Downloads

class BitChordApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Orb now has one Google identity. Credential Manager/Supabase owns the
        // app login; the old YouTube browser cookie survives only as an
        // Innertube compatibility fallback for endpoints that reject OAuth.
        authStore = AuthStore(this)
        OrbGoogleAuth.init(this, authStore)
        Innertube.cookie = authStore.cookie
        // Google identity and YouTube Music authorization are separate states.
        // A stored Google profile must never make private Innertube calls look
        // authenticated by itself. A cached OAuth token can be refreshed by
        // Google Play services when it expires; a verified legacy cookie is the
        // compatibility path for private Music endpoints that reject OAuth.
        Innertube.accountSignedIn =
            authStore.hasLegacyYouTubeSession || !authStore.youtubeAccessToken.isNullOrBlank()
        Innertube.oauthAccessTokenProvider = { OrbGoogleAuth.youtubeAccessToken() }
        Innertube.oauthRejectedHandler = { token -> OrbGoogleAuth.onYoutubeOAuthRejected(token) }
        Innertube.legacySessionRejectedHandler = { OrbGoogleAuth.onLegacyYoutubeSessionRejected() }
        SocialSessionManager.start(this)
        AppSettings.init(this)
        TrackLanguageResolver.init(this)
        com.music.orb.data.lyrics.LyricsDiskCache.init(this)
        ArtistCreditResolver.init(this)
        ArtistRankingHistoryStore.init(this)
        ArtistGenreStore.init(this)
        ArtistPreferenceStore.init(this)
        LikeStatusStore.init(this)
        StatsSnapshotStore.init(this)
        ProfileSnapshotStore.init(this)
        // Update discovery metadata is durable, but the APK itself is never
        // downloaded in the background. Restore both an available notice and
        // any user-initiated ready APK before rebuilding the scheduler.
        UpdateAvailableStore.restore(this)
        UpdateReadyStore.restore(this)
        // The selected update channel is a persisted user preference, while
        // WorkManager jobs are disposable infrastructure. Reconcile them on
        // every process start so Beta/Stable watching cannot silently vanish
        // after an app update, restore or scheduler database cleanup.
        BetaUpdateScheduler.restoreSelectedChannel(this)
        // Track genuine device/system Dolby Atmos independently from Orb's
        // own 360 Audio DSP. The status feeds Settings but never mutates the
        // user's 360 Audio preference.
        DolbyAtmos.init(this)
        // Before LastPlayed: a restored queue can contain source-backed tracks,
        // and turning one of those back into a playable item needs the registry
        // that knows which source it belongs to.
        SourceRegistry.init(this)
        SearchHistory.init(this)
        PlaylistListeningStore.init(this)
        ProfilePrivacyStore.init(this)
        RecentPlaybackStore.init(this)
        AlbumExclusionStore.init(this)
        HomeSuggestionStore.init(this)
        LastPlayed.init(this)
        // What's already saved to Downloads, so the song menu can say so
        // without a media-store query per row.
        Downloads.init(this)
        // One cache directory can only be opened once per process, and
        // PlaybackService shares this one — so it's opened here, not there.
        AudioCache.init(this)
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in the audio cache — survives it
        // untouched. Wipe audio on the first launch of a higher versionCode so a
        // format or key change between builds can't serve stale or mismatched
        // bytes from a cache the new code didn't write.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            AudioCache.clear()
            // Artwork remains valid across app updates; keep its disk cache.
        }
        // Initialize LastFM with saved settings if available
        initLastfm()
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(
                        (((getSystemService(ActivityManager::class.java)?.memoryClass ?: 128).toLong() *
                            1024 * 1024) / 12).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024)
                    )
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(192L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .build()

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.ifBlank { LastFM.FALLBACK_COMPAT_API_KEY }
        val secret = AppSettings.lastfmSecret.value.ifBlank { LastFM.FALLBACK_COMPAT_SECRET }
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    companion object {
        lateinit var authStore: AuthStore
            private set
    }
}
