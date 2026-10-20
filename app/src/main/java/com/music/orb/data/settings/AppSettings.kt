package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.music.orb.auth.AuthStore
import com.music.orb.data.billing.ServerControlSnapshot
import com.music.orb.data.lyrics.LyricsSource
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest

/**
 * Streaming quality below the separate per-network Lossless/Hi-Res ceiling.
 *
 * Orb 1.5 treats HIGH as "best available" rather than as a fixed 320 kbps
 * number. Orb additionally exposes AAC explicitly: when chosen, the YouTube
 * resolver prefers an AAC/M4A rendition and other sources receive an AAC
 * request where they support one. AAC is still lossy; Lossless remains the
 * bit-exact tier stored separately through the Maximum flags below.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "About 64 kbps", "29 MB/hr"),
    MEDIUM(128, "Medium", "Up to 128 kbps", "58 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "Best available lossy", "Varies"),
    AAC(320, "AAC", "Best AAC · up to ~320 kbps", "Up to ~144 MB/hr"),
}

/** Orb's overlay presentation, not the hardware/Doze always-on display service. */
// ON_WAKE replaces the former NOTIFICATIONS mode without any access to other apps' alerts.
// Stored NOTIFICATIONS preferences are migrated while loading existing installations.
enum class OrbLockDisplayMode { OFF, ALWAYS, ON_WAKE }

enum class OutputPcmMode(val label: String) {
    PCM_16("16-bit PCM"),
    FLOAT_32("32-bit float"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/** Public Automix 2.0 and the gated Premium-preview Automix 2.5. */
enum class AutomixVersion {
    V2_0,
    V2_5,
}

/** App typography choices. The UI only exposes this experiment in the dev flavor. */

/**
 * UI-only view of a long Automix overlap. The transport may already have moved
 * to [incomingMediaId] so its queue/decoder can keep running, while the listener
 * still perceives [outgoingMediaId] as the foreground record.
 *
 * [progress] is deliberately NOT the DSP fade progress. It is a short visual
 * handoff window (0 -> outgoing fully shown, 1 -> incoming fully shown) that is
 * derived from the real INTRO_BED dominance point. Keeping this separate stops
 * Now Playing from jumping to B just because B became technically current.
 */
data class AutomixVisualTransition(
    val outgoingMediaId: String,
    val incomingMediaId: String,
    val progress: Float = 0f,
    val outgoingPositionMs: Long = 0L,
    val outgoingDurationMs: Long = 0L,
)

/**
 * App settings, backed by SharedPreferences and exposed as flows.
 *
 * PlaybackService runs in the same process as the UI, so it observes these
 * same flows and applies changes to the live ExoPlayer instance immediately —
 * no restart, no rebinding.
 */
object AppSettings {

    private lateinit var prefs: SharedPreferences

    /** Only for the Discord token — everything else on here is plain prefs. */
    private lateinit var authStore: AuthStore

    /**
     * Lossy playback quality, one choice per kind of connection. Both start on
     * AAC/Hi-Quality Audio. Wi-Fi may separately allow a Lossless/Hi-Res upgrade,
     * but that never changes the first-note codec away from AAC.
     */
    val audioQualityWifi = MutableStateFlow(AudioQuality.AAC)
    val audioQualityCellular = MutableStateFlow(AudioQuality.AAC)

    /**
     * Fourth quality tier exposed by Audio & sources.
     *
     * True means "Maximum (Lossless)" for that connection. HIGH remains the
     * lossy fallback if no enabled source can supply a compatible lossless
     * rendition.
     */
    val audioQualityWifiMaximum = MutableStateFlow(false)
    val audioQualityCellularMaximum = MutableStateFlow(false)

    /** Default for new downloads. Per-track selections do not rewrite it. */
    val downloadQuality = MutableStateFlow("HIGH")

    /**
     * Only a quality explicitly saved in Settings skips the per-download picker.
     * The implicit HIGH default must not count as a user choice. This also
     * recognizes preferences saved by earlier versions of Orb.
     */
    val hasConfiguredDownloadQuality: Boolean
        get() = ::prefs.isInitialized &&
            prefs.getString(KEY_DOWNLOAD_QUALITY, null) in setOf("MEDIUM", "HIGH", "MAXIMUM")

    /** Only authorizes High/AAC fallback after an exhaustive Maximum/Lossless search. */
    val downloadAllowLowerQuality = MutableStateFlow(false)

    /** Stream normally while online; explicitly downloaded Library/Downloads files always remain local. */
    val useDownloadsWhileOnline = MutableStateFlow(false)

    /** Whether the active network charges for data. `null` while offline. */
    val meteredConnection = MutableStateFlow<Boolean?>(null)

    /** True only when the active transport is Wi-Fi; null while offline/unknown. */
    val wifiConnection = MutableStateFlow<Boolean?>(null)

    /** Android-validated Internet access, not just a connected Wi-Fi or carrier network. */
    val internetAvailable = MutableStateFlow(false)

    /**
     * Availability switch for Orb's built-in Lossless sources.
     *
     * This is deliberately NOT a playback-quality override anymore. Enabling
     * it merely makes native hifi-api/addon Lossless sources eligible; the Wi-Fi/mobile quality
     * choice decides whether a track actually asks for Lossless/Hi-Res.
     */
    val losslessAudio = MutableStateFlow(false)

    val crossfadeSeconds = MutableStateFlow(0)

    /** Beat-aware DSP transitions. Off by default while the new engine is experimental. */
    val automixEnabled = MutableStateFlow(false)

    /**
     * Automix 2.0 remains the public/default engine. Automix 2.5 is a separate
     * Premium entitlement; while Premium is not generally released, the owner
     * account can use the same gate as a private preview.
     */
    val automixVersion = MutableStateFlow(AutomixVersion.V2_0)
    val automix25Available = MutableStateFlow(false)
    val premiumEntitled = MutableStateFlow(false)

    /** Last server-authoritative account/config snapshot, cached for offline startup. */
    val serverControlReady = MutableStateFlow(false)
    val premiumPlan = MutableStateFlow<String?>(null)
    val premiumSource = MutableStateFlow<String?>(null)
    val premiumExpiresAt = MutableStateFlow<String?>(null)
    val serverAutomix25Enabled = MutableStateFlow(true)
    val serverOrbSwitchEnabled = MutableStateFlow(true)
    val serverPremiumCardEnabled = MutableStateFlow(true)
    val serverRoomsEnabled = MutableStateFlow(true)
    val serverLosslessMobileEnabled = MutableStateFlow(true)
    val serverControlRefreshIntervalSeconds = MutableStateFlow(60L)

    /** True only for the Orb developer/owner account, independent of billing entitlement. */
    val isOwnerAccount = MutableStateFlow(false)

    /** May see the Premium offer before launch, but has no automatic Premium entitlement. */
    val premiumEarlyOfferAccess = MutableStateFlow(false)
    /**
     * Owner-only, LOCAL checkout preview. This is not billing entitlement and
     * never talks to a payment provider. It lets the developer exercise the
     * cancel / zero-total checkout / subscribe UI without a real charge.
     */
    val ownerPremiumTestSubscribed = MutableStateFlow(true)
    internal val automix25AccountHash = MutableStateFlow("")
    private var requestedAutomixVersion = AutomixVersion.V2_0

    /** Orb v1.5 smart-fade runtime alias; Orb keeps the existing Automix toggle in UI. */
    val smartFadeEnabled get() = automixEnabled
    val smartAnalysis = MutableStateFlow(SmartAnalysis())
    val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    val smartMixInProgress = MutableStateFlow(false)
    val automixTransitionInProgress = MutableStateFlow(false)

    /**
     * Visual ownership of an INTRO_BED. Null for ordinary playback/transitions.
     * This is runtime state only; it is never persisted as a user setting.
     */
    val automixVisualTransition = MutableStateFlow<AutomixVisualTransition?>(null)

    val skipSilence = MutableStateFlow(false)

    /**
     * Orb's own 360 Audio DSP. It widens compatible stereo PCM through
     * [com.music.orb.playback.SpatialAudioProcessor] inside ExoPlayer's audio
     * pipeline, after decoding and independently of any Dolby implementation.
     *
     * This is deliberately separate from Dolby Atmos: 360 Audio may process a
     * normal stereo AAC/Opus/FLAC stream on devices that do not ship Atmos at
     * all. Dolby Atmos capability/status is tracked by
     * [com.music.orb.playback.DolbyAtmos] only for genuine device/system Atmos.
     */
    val spatialAudio = MutableStateFlow(false)
    val playbackSpeed = MutableStateFlow(1.0f)

    /** PCM representation requested at Android's AudioTrack boundary. */
    val outputPcmMode = MutableStateFlow(OutputPcmMode.PCM_16)

    /** Route playback to a connected USB DAC when one is available. */
    val preferUsbDac = MutableStateFlow(false)
    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)

    /** Home page Aura background gradient. Enabled by default; users may disable it to reduce battery/GPU use. */
    val homeAuraEnabled = MutableStateFlow(true)

    /** Prefer canonical album cuts over single/remix editions when Orb can verify the same recording. */
    val prioritizeAlbumVersions = MutableStateFlow(false)

    /**
     * Keep playing similar music once the queue runs out.
     *
     * AutoPlay is opt-in in both release channels: selecting a song means that
     * exact song unless the listener explicitly turns on the infinity button in
     * Now Playing.
     */
    val autoplay = MutableStateFlow(false)

    /**
     * Which library the Library tab and "Add to library" actions write to.
     *
     * True keeps the historical YouTube Music-backed behaviour. False leaves
     * the Google session connected for catalogue/account features but stores
     * library membership only in Orb's own on-device library.
     */
    val useYouTubeMusicLibrary = MutableStateFlow(true)

    /** Put the playing track's codec, bitrate and sample rate on the player. */
    val showNerdStats = MutableStateFlow(false)

    /** Optional tactile confirmations for key expressive actions. */
    val updateReminderUntil = MutableStateFlow(0L)
    val hapticFeedback = MutableStateFlow(true)

    /** Premium same-account playback handoff triggered by touching nearby Orb devices. */
    val touchTransferEnabled = MutableStateFlow(true)

    /** Freezes the main player's mesh gradient instead of letting it drift/crossfade. */
    val reduceAnimation = MutableStateFlow(false)

    /** Hide the device status bar only while the full Now Playing window is open. */
    val hideStatusBarNowPlaying = MutableStateFlow(false)

    /** Opt-in lockscreen view: never changes the device's PIN/biometric security. */
    val orbLockscreenEnabled = MutableStateFlow(false)

    /** Opt-in to keeping Orb's lockscreen lit; NOT Android's hardware low-power AOD. */
    val orbLockscreenAlwaysOn = MutableStateFlow(false)

    /** How Orb Lock behaves after the device is locked. */
    val orbLockscreenMode = MutableStateFlow(OrbLockDisplayMode.OFF)

    /** Follow the device's current display-brightness policy by default. */
    val orbLockscreenAutoBrightness = MutableStateFlow(true)

    /** Activity-local brightness, 5–100%; never changes the device-wide setting. */
    val orbLockscreenBrightness = MutableStateFlow(0.25f)

    /** True black background; the album art itself remains visible in the foreground. */
    val orbLockscreenPowerSaving = MutableStateFlow(false)

    /** Hides the status bar inside Orb Lock only; unrelated to Now Playing. */
    val orbLockscreenHideStatusBar = MutableStateFlow(false)

    /** Keep the display awake only while the full Now Playing window is open. */
    val keepScreenOnNowPlaying = MutableStateFlow(false)

    /** Allow only the phone Now Playing window to follow device rotation. */
    val allowScreenRotation = MutableStateFlow(false)

    /**
     * Premium opt-in: keep an active MediaSession playing when Orb's MAIN task
     * is deliberately removed from Recents. Simply switching apps or turning
     * the screen off never stops playback, for any user.
     *
     * This is account-scoped and defaults to false (swiping Orb away stops it).
     * The service checks entitlement again when it receives onTaskRemoved().
     */
    val continuePlaybackAfterRecentRemoval = MutableStateFlow(false)

    /** Swiping a song row plays it next instead of adding it to the end of the queue. */
    val swipeToPlayNext = MutableStateFlow(false)

    /**
     * Opt-in for the Orb Beta update channel.
     *
     * When enabled, WorkManager checks the configured GitHub repository for
     * pre-releases and notifies about newer Beta builds. APK downloads are
     * always started explicitly by the listener from the update dialog.
     */
    val betaUpdatesEnabled = MutableStateFlow(false)

    /**
     * True while Orb follows the stable channel in the background. This permits
     * automatic checks and notifications only; it never permits an APK download.
     */
    val stableAutoUpdatesEnabled = MutableStateFlow(true)

    /** Drops haze blur (status bar, mini player, bottom fade, lyrics focus) for a solid-fill look. */
    val reduceDynamicBlur = MutableStateFlow(false)

    /**
     * Plays a looping video behind the cover art on the player when one is
     * published for the track — Spotify's Canvas, Apple's motion artwork.
     *
     * Costs a video stream on top of the audio one and reaches three
     * services that have nothing to do with playback, so it stays a switch —
     * but it is the better default, and most tracks resolve to no canvas at
     * all. See [CanvasRepository][com.music.orb.data.canvas.CanvasRepository].
     */
    val animatedCanvas = MutableStateFlow(true)

    /**
     * Capa em tela cheia padrão e fixa.
     */
    val fullBleedArtwork = MutableStateFlow(true)

    /**
     * Time-synced lyrics on the player, lit up as they are sung.
     *
     * On by default — it is most of the point of the player screen — but it
     * reaches third-party lyric databases for every track played, so it stays
     * a switch, and [lyricsSources] narrows which of them get asked.
     */
    val syncedLyrics = MutableStateFlow(true)

    /** The databases [syncedLyrics] may ask. Empty is the same as off. */
    private fun availableLyricsSources(): Set<LyricsSource> =
        LyricsSource.entries.toSet()

    val lyricsSources = MutableStateFlow(availableLyricsSources())

    /** Keep looking for syllable/word timing after a line-synced result is found. */
    val prioritizeSyllableSync = MutableStateFlow(false)

    /** Disk budget for cached audio. [AudioCache][com.music.orb.playback.AudioCache] evicts past it. */
    val audioCacheLimitBytes = MutableStateFlow(DEFAULT_CACHE_LIMIT_BYTES)

    // ── Scrobbling ──────────────────────────────────────────────────────

    /**
     * Whether the scrobbling integrations are offered at all.
     *
     * Off for now: Last.fm and ListenBrainz are shelved until a later version,
     * and this is the one switch that shelves them — the settings rows dim
     * and the submit paths in
     * [PlaybackService][com.music.orb.playback.PlaybackService] go quiet.
     * Without the second half of that, a device that had Last.fm connected
     * before would keep scrobbling behind a screen saying the feature is gone.
     *
     * Nothing here clears the stored keys or toggles, so an account that was
     * connected comes back exactly as it was.
     *
     * A plain `val` rather than a `const val` on purpose: a const would be
     * folded away and every gate below would compile to a "condition is always
     * false" warning.
     */
    val scrobblingAvailable = false

    val lastfmEnabled = MutableStateFlow(false)
    val lastfmUsername = MutableStateFlow("")
    val lastfmSessionKey = MutableStateFlow("")
    val lastfmApiKey = MutableStateFlow("")
    val lastfmSecret = MutableStateFlow("")
    val lastfmEndpoint = MutableStateFlow("")
    val lastfmScrobbleEnabled = MutableStateFlow(false)
    val lastfmNowPlaying = MutableStateFlow(false)
    val scrobbleMinDuration = MutableStateFlow(30)
    val scrobbleDelayPercent = MutableStateFlow(0.5f)
    val scrobbleDelaySeconds = MutableStateFlow(180)
    val listenBrainzEnabled = MutableStateFlow(false)
    val listenBrainzToken = MutableStateFlow("")

    // ── Discord Rich Presence ───────────────────────────────────────────

    /**
     * The connected Discord account's token, mirrored out of [AuthStore] so
     * [PlaybackService][com.music.orb.playback.PlaybackService] can pick
     * up a login without polling for one. Empty means not connected.
     *
     * Only the mirror is here — the persisted copy is encrypted, because unlike
     * a scrobbler key this one is the account itself.
     */
    val discordToken = MutableStateFlow("")

    /**
     * Who the token belongs to, cached at login. Kept so the settings screen
     * can show the account without a round trip every time it opens, and can
     * still show it offline.
     */
    val discordUsername = MutableStateFlow("")
    val discordName = MutableStateFlow("")
    val discordAvatar = MutableStateFlow("")

    val discordRpcEnabled = MutableStateFlow(true)

    /** Put the track title on the bold profile line, in place of the artist. */
    val discordUseDetails = MutableStateFlow(false)

    /** Reveals the presence-shape controls: status, activity type/name, buttons. */
    val discordAdvancedMode = MutableStateFlow(false)

    val discordStatus = MutableStateFlow("online")
    val discordActivityType = MutableStateFlow("listening")

    /** Overrides the "Listening to ___" line; empty means the app's own name. */
    val discordActivityName = MutableStateFlow("")

    val discordButton1Text = MutableStateFlow("")
    val discordButton1Visible = MutableStateFlow(true)
    val discordButton2Text = MutableStateFlow("")
    val discordButton2Visible = MutableStateFlow(true)

    /** The notice about what connecting an account actually does has been read. */
    val discordInfoDismissed = MutableStateFlow(false)

    /** Published by PlaybackService so the UI can open the system equalizer. */
    val audioSessionId = MutableStateFlow(0)


    /** The lossy ceiling that applies to a stream started right now. */
    val effectiveAudioQuality: AudioQuality
        get() = if (meteredConnection.value == true) {
            audioQualityCellular.value
        } else {
            audioQualityWifi.value
        }

    /**
     * Whether the active connection is allowed to upgrade above AAC into
     * Lossless / Hi-Res Lossless.
     *
     * Free accounts keep the existing Wi-Fi-only rule. Premium accounts may
     * also use Lossless / Hi-Res Lossless on mobile data. The first-note path
     * remains latency-first (AAC/Opus first); this flag only authorizes the
     * subsequent quality upgrade.
     */
    val effectiveMaximumAudioQuality: Boolean
        get() = losslessAudio.value && (
            (wifiConnection.value == true && audioQualityWifiMaximum.value) ||
                (wifiConnection.value == false && audioQualityCellularMaximum.value &&
                    premiumEntitled.value && serverLosslessMobileEnabled.value)
            )

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        serverAutomix25Enabled.value = prefs.getBoolean(KEY_SERVER_AUTOMIX25_ENABLED, true)
        serverOrbSwitchEnabled.value = prefs.getBoolean(KEY_SERVER_ORB_SWITCH_ENABLED, true)
        serverPremiumCardEnabled.value = prefs.getBoolean(KEY_SERVER_PREMIUM_CARD_ENABLED, true)
        serverRoomsEnabled.value = prefs.getBoolean(KEY_SERVER_ROOMS_ENABLED, true)
        serverLosslessMobileEnabled.value = prefs.getBoolean(KEY_SERVER_LOSSLESS_MOBILE_ENABLED, true)
        serverControlRefreshIntervalSeconds.value = prefs.getLong(KEY_SERVER_REFRESH_INTERVAL_SECONDS, 60L).coerceIn(30L, 900L)
        premiumPlan.value = prefs.getString(KEY_SERVER_PREMIUM_PLAN, null)
        premiumSource.value = prefs.getString(KEY_SERVER_PREMIUM_SOURCE, null)
        premiumExpiresAt.value = prefs.getString(KEY_SERVER_PREMIUM_EXPIRES_AT, null)
        migrateSingleQuality()
        migrateLegacyHighDefaultToAac()
        // Keep AAC as AAC. Earlier builds translated the stored AAC default
        // back into HIGH at startup, which made playback take the wrong source
        // policy and could accidentally enter the Lossless path.
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        audioQualityWifiMaximum.value = prefs.getBoolean(KEY_QUALITY_WIFI_MAXIMUM, false)
        // Account entitlement is enforced at playback time. The stored opt-in
        // cannot grant mobile Lossless to free accounts by itself.
        audioQualityCellularMaximum.value = prefs.getBoolean(KEY_QUALITY_CELLULAR_MAXIMUM, false)
        downloadQuality.value = prefs.getString(KEY_DOWNLOAD_QUALITY, "HIGH")
            ?.takeIf { it in setOf("MEDIUM", "HIGH", "MAXIMUM") } ?: "HIGH"
        // A new key avoids inheriting obsolete off-by-default behavior from the
        // pre-automatic-fallback picker. Users can turn High fallback off explicitly.
        downloadAllowLowerQuality.value = prefs.getBoolean(KEY_DOWNLOAD_FALLBACK_HIGH_V3, false)
        useDownloadsWhileOnline.value = prefs.getBoolean(KEY_USE_DOWNLOADS_WHILE_ONLINE, false)
        losslessAudio.value = prefs.getBoolean(KEY_LOSSLESS, false)
        crossfadeSeconds.value = prefs.getInt(KEY_CROSSFADE, 0)
        automixEnabled.value = prefs.getBoolean(KEY_AUTOMIX, false)
        requestedAutomixVersion = runCatching {
            AutomixVersion.valueOf(
                prefs.getString(KEY_AUTOMIX_VERSION, AutomixVersion.V2_0.name)
                    ?: AutomixVersion.V2_0.name,
            )
        }.getOrDefault(AutomixVersion.V2_0)
        // Fail closed to 2.0 until the signed-in account entitlement is known.
        automixVersion.value = AutomixVersion.V2_0
        // Automix owns the transition envelope. A manual crossfade must never
        // compete with it, including after restoring settings from an older build.
        if (automixEnabled.value && crossfadeSeconds.value != 0) {
            crossfadeSeconds.value = 0
            prefs.edit().putInt(KEY_CROSSFADE, 0).apply()
        }
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        spatialAudio.value = prefs.getBoolean(KEY_SPATIAL_AUDIO, false)
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM")
        }.getOrDefault(ThemeMode.SYSTEM)
        homeAuraEnabled.value = prefs.getBoolean(KEY_HOME_AURA_ENABLED, true)
        // The font picker was removed. Normalize older installs back to Orb's default
        // so nobody gets stranded on the former experimental font after upgrading.
        prefs.edit().remove("app_font").apply()
        prioritizeAlbumVersions.value = prefs.getBoolean(KEY_PRIORITIZE_ALBUM_VERSIONS, false)
        // AutoPlay is opt-in for both Beta and Stable. Older installs may have
        // inherited the legacy implicit-true default, so only a value written
        // through the explicit infinity toggle is restored.
        autoplay.value = if (prefs.getBoolean(KEY_AUTOPLAY_EXPLICIT, false)) {
            prefs.getBoolean(KEY_AUTOPLAY, false)
        } else {
            false
        }
        useYouTubeMusicLibrary.value = prefs.getBoolean(KEY_USE_YOUTUBE_MUSIC_LIBRARY, true)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        updateReminderUntil.value = prefs.getLong(KEY_UPDATE_REMINDER_UNTIL, 0L)
        hapticFeedback.value = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        touchTransferEnabled.value = prefs.getBoolean(KEY_TOUCH_TRANSFER_ENABLED, true)
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
        hideStatusBarNowPlaying.value = prefs.getBoolean(KEY_HIDE_STATUS_BAR_NOW_PLAYING, false)
        orbLockscreenEnabled.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_ENABLED, false)
        orbLockscreenMode.value = runCatching {
            OrbLockDisplayMode.valueOf(
                when (val saved = prefs.getString(KEY_ORB_LOCKSCREEN_MODE, null)) {
                    "NOTIFICATIONS" -> OrbLockDisplayMode.ON_WAKE.name
                    else -> saved ?: ""
                }
            )
        }.getOrElse {
            if (prefs.getBoolean(KEY_ORB_LOCKSCREEN_ALWAYS_ON, false)) OrbLockDisplayMode.ALWAYS
            else OrbLockDisplayMode.OFF
        }
        orbLockscreenAlwaysOn.value = orbLockscreenMode.value == OrbLockDisplayMode.ALWAYS
        orbLockscreenAutoBrightness.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_AUTO_BRIGHTNESS, true)
        orbLockscreenBrightness.value = prefs.getFloat(KEY_ORB_LOCKSCREEN_BRIGHTNESS, 0.25f).coerceIn(0.05f, 1f)
        orbLockscreenPowerSaving.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_POWER_SAVING, false)
        orbLockscreenHideStatusBar.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_HIDE_STATUS_BAR, false)
        keepScreenOnNowPlaying.value = prefs.getBoolean(KEY_KEEP_SCREEN_ON_NOW_PLAYING, false)
        allowScreenRotation.value = prefs.getBoolean(KEY_ALLOW_SCREEN_ROTATION, false)
        loadContinuePlaybackAfterRecentRemoval()
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        betaUpdatesEnabled.value = prefs.getBoolean(KEY_BETA_UPDATES_ENABLED, false)
        stableAutoUpdatesEnabled.value = if (betaUpdatesEnabled.value) {
            false
        } else {
            // Stable update notifications are now the default app behaviour.
            // Migrate older installs that persisted the former opt-in false value.
            prefs.edit().putBoolean(KEY_STABLE_AUTO_UPDATES_ENABLED, true).apply()
            true
        }
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        animatedCanvas.value = prefs.getBoolean(KEY_ANIMATED_CANVAS, true)
        syncedLyrics.value = prefs.getBoolean(KEY_SYNCED_LYRICS, true)
        lyricsSources.value = readLyricsSources()
        prioritizeSyllableSync.value = prefs.getBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, false)
        // No longer keep credentials for discontinued PaxSenix providers.
        prefs.edit().remove(KEY_PAXSENIX_API_KEY).apply()
        outputPcmMode.value = prefs.getString(KEY_OUTPUT_PCM_MODE, null)
            ?.let { saved -> OutputPcmMode.entries.firstOrNull { it.name == saved } }
            ?: OutputPcmMode.PCM_16
        preferUsbDac.value = prefs.getBoolean(KEY_PREFER_USB_DAC, false)
        audioCacheLimitBytes.value = prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES)
            .coerceIn(DEFAULT_CACHE_LIMIT_BYTES, MAX_CACHE_LIMIT_BYTES)
        lastfmEnabled.value = prefs.getBoolean(KEY_LASTFM_ENABLED, false)
        lastfmUsername.value = prefs.getString(KEY_LASTFM_USERNAME, "").orEmpty()
        lastfmSessionKey.value = prefs.getString(KEY_LASTFM_SESSION_KEY, "").orEmpty()
        lastfmApiKey.value = prefs.getString(KEY_LASTFM_API_KEY, "").orEmpty()
        lastfmSecret.value = prefs.getString(KEY_LASTFM_SECRET, "").orEmpty()
        lastfmEndpoint.value = prefs.getString(KEY_LASTFM_ENDPOINT, "").orEmpty()
        lastfmScrobbleEnabled.value = prefs.getBoolean(KEY_LASTFM_SCROBBLE_ENABLED, false)
        lastfmNowPlaying.value = prefs.getBoolean(KEY_LASTFM_NOW_PLAYING, false)
        scrobbleMinDuration.value = prefs.getInt(KEY_SCROBBLE_MIN_DURATION, 30)
        scrobbleDelayPercent.value = prefs.getFloat(KEY_SCROBBLE_DELAY_PERCENT, 0.5f)
        scrobbleDelaySeconds.value = prefs.getInt(KEY_SCROBBLE_DELAY_SECONDS, 180)
        listenBrainzEnabled.value = prefs.getBoolean(KEY_LISTENBRAINZ_ENABLED, false)
        listenBrainzToken.value = prefs.getString(KEY_LISTENBRAINZ_TOKEN, "").orEmpty()
        authStore = AuthStore(context)
        setCurrentAccountEmail(authStore.googleEmail)
        discordToken.value = authStore.discordToken.orEmpty()
        discordUsername.value = prefs.getString(KEY_DISCORD_USERNAME, "").orEmpty()
        discordName.value = prefs.getString(KEY_DISCORD_NAME, "").orEmpty()
        discordAvatar.value = prefs.getString(KEY_DISCORD_AVATAR, "").orEmpty()
        discordRpcEnabled.value = prefs.getBoolean(KEY_DISCORD_RPC_ENABLED, true)
        discordUseDetails.value = prefs.getBoolean(KEY_DISCORD_USE_DETAILS, false)
        discordAdvancedMode.value = prefs.getBoolean(KEY_DISCORD_ADVANCED_MODE, false)
        discordStatus.value = prefs.getString(KEY_DISCORD_STATUS, "online").orEmpty()
        discordActivityType.value = prefs.getString(KEY_DISCORD_ACTIVITY_TYPE, "listening").orEmpty()
        discordActivityName.value = prefs.getString(KEY_DISCORD_ACTIVITY_NAME, "").orEmpty()
        discordButton1Text.value = prefs.getString(KEY_DISCORD_BUTTON_1_TEXT, "").orEmpty()
        discordButton1Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, true)
        discordButton2Text.value = prefs.getString(KEY_DISCORD_BUTTON_2_TEXT, "").orEmpty()
        discordButton2Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, true)
        discordInfoDismissed.value = prefs.getBoolean(KEY_DISCORD_INFO_DISMISSED, false)
        watchConnection(context)
    }

    /** Re-publishes account-synced preferences after a remote settings snapshot is applied. */
    internal fun reloadFromPreferences() {
        if (!::prefs.isInitialized) return
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        audioQualityWifiMaximum.value = prefs.getBoolean(KEY_QUALITY_WIFI_MAXIMUM, false)
        losslessAudio.value = prefs.getBoolean(KEY_LOSSLESS, false)
        crossfadeSeconds.value = prefs.getInt(KEY_CROSSFADE, 0)
        automixEnabled.value = prefs.getBoolean(KEY_AUTOMIX, false)
        requestedAutomixVersion = runCatching {
            AutomixVersion.valueOf(prefs.getString(KEY_AUTOMIX_VERSION, AutomixVersion.V2_0.name) ?: AutomixVersion.V2_0.name)
        }.getOrDefault(AutomixVersion.V2_0)
        refreshAutomix25Entitlement()
        if (automixEnabled.value && crossfadeSeconds.value != 0) {
            crossfadeSeconds.value = 0
            prefs.edit().putInt(KEY_CROSSFADE, 0).apply()
        }
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        spatialAudio.value = prefs.getBoolean(KEY_SPATIAL_AUDIO, false)
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
        themeMode.value = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
        homeAuraEnabled.value = prefs.getBoolean(KEY_HOME_AURA_ENABLED, true)
        prioritizeAlbumVersions.value = prefs.getBoolean(KEY_PRIORITIZE_ALBUM_VERSIONS, false)
        autoplay.value = if (prefs.getBoolean(KEY_AUTOPLAY_EXPLICIT, false)) prefs.getBoolean(KEY_AUTOPLAY, false) else false
        useYouTubeMusicLibrary.value = prefs.getBoolean(KEY_USE_YOUTUBE_MUSIC_LIBRARY, true)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        updateReminderUntil.value = prefs.getLong(KEY_UPDATE_REMINDER_UNTIL, 0L)
        hapticFeedback.value = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        touchTransferEnabled.value = prefs.getBoolean(KEY_TOUCH_TRANSFER_ENABLED, true)
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
        hideStatusBarNowPlaying.value = prefs.getBoolean(KEY_HIDE_STATUS_BAR_NOW_PLAYING, false)
        keepScreenOnNowPlaying.value = prefs.getBoolean(KEY_KEEP_SCREEN_ON_NOW_PLAYING, false)
        allowScreenRotation.value = prefs.getBoolean(KEY_ALLOW_SCREEN_ROTATION, false)
        loadContinuePlaybackAfterRecentRemoval()
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        betaUpdatesEnabled.value = prefs.getBoolean(KEY_BETA_UPDATES_ENABLED, false)
        stableAutoUpdatesEnabled.value = prefs.getBoolean(KEY_STABLE_AUTO_UPDATES_ENABLED, true)
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        animatedCanvas.value = prefs.getBoolean(KEY_ANIMATED_CANVAS, true)
        syncedLyrics.value = prefs.getBoolean(KEY_SYNCED_LYRICS, true)
        lyricsSources.value = readLyricsSources()
        prioritizeSyllableSync.value = prefs.getBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, false)
        outputPcmMode.value = prefs.getString(KEY_OUTPUT_PCM_MODE, null)
            ?.let { saved -> OutputPcmMode.entries.firstOrNull { it.name == saved } }
            ?: OutputPcmMode.PCM_16
        preferUsbDac.value = prefs.getBoolean(KEY_PREFER_USB_DAC, false)
        audioQualityCellularMaximum.value = prefs.getBoolean(KEY_QUALITY_CELLULAR_MAXIMUM, false)
        downloadQuality.value = prefs.getString(KEY_DOWNLOAD_QUALITY, "HIGH")
            ?.takeIf { it in setOf("MEDIUM", "HIGH", "MAXIMUM") } ?: "HIGH"
        downloadAllowLowerQuality.value = prefs.getBoolean(KEY_DOWNLOAD_FALLBACK_HIGH_V3, false)
        useDownloadsWhileOnline.value = prefs.getBoolean(KEY_USE_DOWNLOADS_WHILE_ONLINE, false)
        orbLockscreenEnabled.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_ENABLED, false)
        orbLockscreenMode.value = runCatching {
            OrbLockDisplayMode.valueOf(
                when (val saved = prefs.getString(KEY_ORB_LOCKSCREEN_MODE, null)) {
                    "NOTIFICATIONS" -> OrbLockDisplayMode.ON_WAKE.name
                    else -> saved ?: ""
                }
            )
        }.getOrElse {
            if (prefs.getBoolean(KEY_ORB_LOCKSCREEN_ALWAYS_ON, false)) OrbLockDisplayMode.ALWAYS
            else OrbLockDisplayMode.OFF
        }
        orbLockscreenAlwaysOn.value = orbLockscreenMode.value == OrbLockDisplayMode.ALWAYS
        orbLockscreenAutoBrightness.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_AUTO_BRIGHTNESS, true)
        orbLockscreenBrightness.value = prefs.getFloat(KEY_ORB_LOCKSCREEN_BRIGHTNESS, 0.25f).coerceIn(0.05f, 1f)
        orbLockscreenPowerSaving.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_POWER_SAVING, false)
        orbLockscreenHideStatusBar.value = prefs.getBoolean(KEY_ORB_LOCKSCREEN_HIDE_STATUS_BAR, false)
        audioCacheLimitBytes.value = prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES)
            .coerceIn(DEFAULT_CACHE_LIMIT_BYTES, MAX_CACHE_LIMIT_BYTES)
        lastfmEnabled.value = prefs.getBoolean(KEY_LASTFM_ENABLED, false)
        lastfmUsername.value = prefs.getString(KEY_LASTFM_USERNAME, "").orEmpty()
        lastfmEndpoint.value = prefs.getString(KEY_LASTFM_ENDPOINT, "").orEmpty()
        lastfmScrobbleEnabled.value = prefs.getBoolean(KEY_LASTFM_SCROBBLE_ENABLED, false)
        lastfmNowPlaying.value = prefs.getBoolean(KEY_LASTFM_NOW_PLAYING, false)
        scrobbleMinDuration.value = prefs.getInt(KEY_SCROBBLE_MIN_DURATION, 30)
        scrobbleDelayPercent.value = prefs.getFloat(KEY_SCROBBLE_DELAY_PERCENT, 0.5f)
        scrobbleDelaySeconds.value = prefs.getInt(KEY_SCROBBLE_DELAY_SECONDS, 180)
        listenBrainzEnabled.value = prefs.getBoolean(KEY_LISTENBRAINZ_ENABLED, false)
        discordUsername.value = prefs.getString(KEY_DISCORD_USERNAME, "").orEmpty()
        discordName.value = prefs.getString(KEY_DISCORD_NAME, "").orEmpty()
        discordAvatar.value = prefs.getString(KEY_DISCORD_AVATAR, "").orEmpty()
        discordRpcEnabled.value = prefs.getBoolean(KEY_DISCORD_RPC_ENABLED, true)
        discordUseDetails.value = prefs.getBoolean(KEY_DISCORD_USE_DETAILS, false)
        discordAdvancedMode.value = prefs.getBoolean(KEY_DISCORD_ADVANCED_MODE, false)
        discordStatus.value = prefs.getString(KEY_DISCORD_STATUS, "online").orEmpty()
        discordActivityType.value = prefs.getString(KEY_DISCORD_ACTIVITY_TYPE, "listening").orEmpty()
        discordActivityName.value = prefs.getString(KEY_DISCORD_ACTIVITY_NAME, "").orEmpty()
        discordButton1Text.value = prefs.getString(KEY_DISCORD_BUTTON_1_TEXT, "").orEmpty()
        discordButton1Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, true)
        discordButton2Text.value = prefs.getString(KEY_DISCORD_BUTTON_2_TEXT, "").orEmpty()
        discordButton2Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, true)
    }

    /**
     * True the first time this is called after [currentVersionCode] rises above
     * whatever was last recorded — i.e. once per update, on the first launch
     * after it installs. A fresh install has nothing to compare against, so
     * the very first call seeds the stored value from [currentVersionCode]
     * rather than reporting an update.
     *
     * Orb ships sideloaded (see [com.music.orb.data.AppUpdateChecker]),
     * so installing a new APK over the old one is the only "update" there is —
     * app data, this pref included, survives it exactly like a Play Store
     * update. Call once per process start, before anything reads a cache that
     * an update should invalidate.
     */
    fun consumeVersionUpdate(currentVersionCode: Int): Boolean {
        val last = prefs.getInt(KEY_LAST_VERSION_CODE, currentVersionCode)
        if (last != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        }
        return currentVersionCode > last
    }

    /**
     * A ceiling saved when there was only one applies to both connections.
     * Someone who picked Low to protect a data plan would not thank us for
     * quietly putting Wi-Fi *and* mobile back on High.
     */
    private fun migrateSingleQuality() {
        val legacy = prefs.getString(KEY_QUALITY_LEGACY, null) ?: return
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, legacy)
            .putString(KEY_QUALITY_CELLULAR, legacy)
            .remove(KEY_QUALITY_LEGACY)
            .apply()
    }

    /**
     * One-time migration from the builds where HIGH was the implicit/default
     * quality. HIGH normally resolves to WebM/Opus on YouTube, so leaving that
     * stored value behind would make an upgraded installation keep sounding as
     * though AAC had never become Orb's default. Explicit LOW/MEDIUM choices are
     * preserved; only the old HIGH default is promoted to AAC once.
     */
    private fun migrateLegacyHighDefaultToAac() {
        if (prefs.getBoolean(KEY_AAC_DEFAULT_MIGRATED, false)) return
        val wifi = prefs.getString(KEY_QUALITY_WIFI, null)
        val cellular = prefs.getString(KEY_QUALITY_CELLULAR, null)
        val editor = prefs.edit().putBoolean(KEY_AAC_DEFAULT_MIGRATED, true)
        if (wifi == null || wifi == AudioQuality.HIGH.name) {
            editor.putString(KEY_QUALITY_WIFI, AudioQuality.AAC.name)
        }
        if (cellular == null || cellular == AudioQuality.HIGH.name) {
            editor.putString(KEY_QUALITY_CELLULAR, AudioQuality.AAC.name)
        }
        editor.apply()
    }

    private fun readQuality(key: String): AudioQuality {
        val stored = prefs.getString(key, null) ?: return AudioQuality.AAC
        val parsed = runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.AAC)
        // HIGH was the historical name of Orb's top lossy tier. The current
        // product contract for that visible tier is Hi-Quality AAC, so old
        // installs are normalized on read instead of silently falling back to
        // generic Opus-first "best lossy" behaviour.
        return if (parsed == AudioQuality.HIGH) AudioQuality.AAC else parsed
    }

    /**
     * Track the active network so [effectiveAudioQuality] can answer without
     * touching ConnectivityManager. Stream resolution happens off the main
     * thread mid-playback; a callback keeps that lookup off the hot path and
     * lets the settings page show which ceiling is currently in force.
     */
    private fun watchConnection(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val refresh = {
            val active = manager.activeNetwork
            meteredConnection.value = runCatching {
                if (active == null) null else manager.isActiveNetworkMetered
            }.getOrNull()
            val capabilities = runCatching {
                active?.let { manager.getNetworkCapabilities(it) }
            }.getOrNull()
            wifiConnection.value = if (active == null) null else
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            internetAvailable.value = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            ) == true
        }
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = refresh()
                },
            )
        }
    }

    fun setAutoplay(value: Boolean) {
        autoplay.value = value
        prefs.edit()
            .putBoolean(KEY_AUTOPLAY, value)
            // Marks this as a real listener choice. Both release channels ignore
            // the legacy implicit-true default until this toggle is touched.
            .putBoolean(KEY_AUTOPLAY_EXPLICIT, true)
            .apply()
    }

    fun setUseYouTubeMusicLibrary(value: Boolean) {
        useYouTubeMusicLibrary.value = value
        prefs.edit().putBoolean(KEY_USE_YOUTUBE_MUSIC_LIBRARY, value).apply()
    }

    fun setAudioQualityWifi(value: AudioQuality) {
        val resolved = if (value == AudioQuality.HIGH) AudioQuality.AAC else value
        audioQualityWifi.value = resolved
        audioQualityWifiMaximum.value = false
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, resolved.name)
            .putBoolean(KEY_QUALITY_WIFI_MAXIMUM, false)
            .apply()
    }

    fun setAudioQualityCellular(value: AudioQuality) {
        val resolved = if (value == AudioQuality.HIGH) AudioQuality.AAC else value
        audioQualityCellular.value = resolved
        audioQualityCellularMaximum.value = false
        prefs.edit()
            .putString(KEY_QUALITY_CELLULAR, resolved.name)
            .putBoolean(KEY_QUALITY_CELLULAR_MAXIMUM, false)
            .apply()
    }

    fun setAudioQualityWifiMaximum(value: Boolean) {
        audioQualityWifiMaximum.value = value
        val editor = prefs.edit().putBoolean(KEY_QUALITY_WIFI_MAXIMUM, value)
        if (value) {
            // Maximum is an upgrade ceiling, not the first-note codec. Keep AAC
            // underneath it so FLAC discovery can never force an Opus start.
            audioQualityWifi.value = AudioQuality.AAC
            editor.putString(KEY_QUALITY_WIFI, AudioQuality.AAC.name)
        }
        editor.apply()
    }

    fun setAudioQualityCellularMaximum(value: Boolean) {
        if (value && (!premiumEntitled.value || !serverLosslessMobileEnabled.value)) return
        audioQualityCellularMaximum.value = value
        if (value) audioQualityCellular.value = AudioQuality.AAC
        prefs.edit()
            .putBoolean(KEY_QUALITY_CELLULAR_MAXIMUM, value)
            .putString(KEY_QUALITY_CELLULAR, audioQualityCellular.value.name)
            .apply()
    }

    fun setDownloadQuality(value: String) {
        if (value !in setOf("MEDIUM", "HIGH", "MAXIMUM")) return
        if (value == "MAXIMUM" && !premiumEntitled.value) return
        downloadQuality.value = value
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, value).apply()
    }

    fun setUseDownloadsWhileOnline(value: Boolean) {
        useDownloadsWhileOnline.value = value
        prefs.edit().putBoolean(KEY_USE_DOWNLOADS_WHILE_ONLINE, value).apply()
    }

    fun setDownloadAllowLowerQuality(value: Boolean) {
        downloadAllowLowerQuality.value = value
        prefs.edit().putBoolean(KEY_DOWNLOAD_FALLBACK_HIGH_V3, value).apply()
    }

    fun setLosslessAudio(value: Boolean) {
        losslessAudio.value = value
        prefs.edit().putBoolean(KEY_LOSSLESS, value).apply()
    }

    fun setCrossfadeSeconds(value: Int) {
        val resolved = if (automixEnabled.value) 0 else value.coerceIn(0, 12)
        crossfadeSeconds.value = resolved
        prefs.edit().putInt(KEY_CROSSFADE, resolved).apply()
    }

    fun setAutomixEnabled(value: Boolean) {
        automixEnabled.value = value
        val editor = prefs.edit().putBoolean(KEY_AUTOMIX, value)
        if (value) {
            // Automix and manual Crossfade are mutually exclusive by design.
            crossfadeSeconds.value = 0
            editor.putInt(KEY_CROSSFADE, 0)
        }
        editor.apply()
    }

    fun setAutomixVersion(value: AutomixVersion) {
        requestedAutomixVersion = value
        prefs.edit().putString(KEY_AUTOMIX_VERSION, value.name).apply()
        automixVersion.value =
            if (value == AutomixVersion.V2_5 && !automix25Available.value) {
                AutomixVersion.V2_0
            } else {
                value
            }
    }

    /**
     * Refreshes the private 2.5 entitlement from the Orb/Google identity. The
     * raw e-mail is never persisted by this setting or sent to the Automix API.
     */
    fun setCurrentAccountEmail(email: String?) {
        val newAccountHash = email
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::sha256Hex)
            .orEmpty()
        if (automix25AccountHash.value != newAccountHash) {
            // Do not treat the previous account's verified bootstrap as the
            // new account's snapshot. Its Premium flag is reloaded below.
            serverControlReady.value = false
            premiumPlan.value = null
            premiumSource.value = null
            premiumExpiresAt.value = null
        }
        automix25AccountHash.value = newAccountHash

        // Early access is the initial state only. Verified billing changes are
        // cached per account so canceling is preserved across login/restart.
        val ownerAccount = automix25AccountHash.value == AUTOMIX_25_BETA_OWNER_HASH
        isOwnerAccount.value = ownerAccount
        // Visibility only. This account must still complete a real purchase and
        // receive an authenticated billing entitlement to use Premium features.
        premiumEarlyOfferAccess.value = PremiumLaunch.isEarlyOfferAccount(email)
        ownerPremiumTestSubscribed.value = ownerAccount &&
            prefs.getBoolean("owner_premium_test_subscribed_${automix25AccountHash.value}", true)
        premiumEntitled.value = automix25AccountHash.value.isNotEmpty() &&
            prefs.getBoolean("premium_entitled_${automix25AccountHash.value}", ownerAccount)
        // A previously signed-in Premium account must not leak its choice to
        // the next user on the same phone, including a signed-out user.
        loadContinuePlaybackAfterRecentRemoval()
        refreshAutomix25Entitlement()
    }

    /** Only affects the owner's local billing preview, never Premium entitlement. */
    fun setOwnerPremiumTestSubscribed(subscribed: Boolean) {
        if (!isOwnerAccount.value || automix25AccountHash.value.isBlank() || !::prefs.isInitialized) return
        ownerPremiumTestSubscribed.value = subscribed
        prefs.edit().putBoolean(
            "owner_premium_test_subscribed_${automix25AccountHash.value}", subscribed,
        ).apply()
    }

    /** Applies a snapshot signed by the authenticated Orb backend. */
    internal fun applyServerControl(snapshot: ServerControlSnapshot) {
        serverControlReady.value = true
        premiumPlan.value = snapshot.plan
        premiumSource.value = snapshot.source
        premiumExpiresAt.value = snapshot.expiresAt
        serverAutomix25Enabled.value = snapshot.automix25Enabled
        serverOrbSwitchEnabled.value = snapshot.orbSwitchEnabled
        serverPremiumCardEnabled.value = snapshot.premiumCardEnabled
        serverRoomsEnabled.value = snapshot.roomsEnabled
        serverLosslessMobileEnabled.value = snapshot.losslessMobileEnabled
        serverControlRefreshIntervalSeconds.value = snapshot.refreshIntervalSeconds.coerceIn(30L, 900L)

        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SERVER_PREMIUM_PLAN, snapshot.plan)
                .putString(KEY_SERVER_PREMIUM_SOURCE, snapshot.source)
                .putString(KEY_SERVER_PREMIUM_EXPIRES_AT, snapshot.expiresAt)
                .putBoolean(KEY_SERVER_AUTOMIX25_ENABLED, snapshot.automix25Enabled)
                .putBoolean(KEY_SERVER_ORB_SWITCH_ENABLED, snapshot.orbSwitchEnabled)
                .putBoolean(KEY_SERVER_PREMIUM_CARD_ENABLED, snapshot.premiumCardEnabled)
                .putBoolean(KEY_SERVER_ROOMS_ENABLED, snapshot.roomsEnabled)
                .putBoolean(KEY_SERVER_LOSSLESS_MOBILE_ENABLED, snapshot.losslessMobileEnabled)
                .putLong(KEY_SERVER_REFRESH_INTERVAL_SECONDS, snapshot.refreshIntervalSeconds.coerceIn(30L, 900L))
                .apply()
        }
        setPremiumEntitled(snapshot.premium)
    }

    /** Called only with an authenticated server/billing response. */
    fun setPremiumEntitled(value: Boolean) {
        val effective = value || isOwnerAccount.value
        premiumEntitled.value = effective
        if (automix25AccountHash.value.isNotEmpty()) {
            prefs.edit().putBoolean("premium_entitled_${automix25AccountHash.value}", effective).apply()
        }
        refreshAutomix25Entitlement()
    }

    private fun refreshAutomix25Entitlement() {
        val allowed = premiumEntitled.value && serverAutomix25Enabled.value
        automix25Available.value = allowed
        automixVersion.value =
            if (allowed) requestedAutomixVersion else AutomixVersion.V2_0
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun setSkipSilence(value: Boolean) {
        skipSilence.value = value
        prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
    }

    fun setSpatialAudio(value: Boolean) {
        spatialAudio.value = value
        prefs.edit().putBoolean(KEY_SPATIAL_AUDIO, value).apply()
    }

    fun setPlaybackSpeed(value: Float) {
        playbackSpeed.value = value
        prefs.edit().putFloat(KEY_SPEED, value).apply()
    }

    fun setShowNerdStats(value: Boolean) {
        showNerdStats.value = value
        prefs.edit().putBoolean(KEY_NERD_STATS, value).apply()
    }

    fun remindUpdateLater() {
        val deadline = System.currentTimeMillis() + 3L * 60L * 60L * 1_000L
        prefs.edit().putLong(KEY_UPDATE_REMINDER_UNTIL, deadline).apply()
        updateReminderUntil.value = deadline
    }

    fun setHapticFeedback(value: Boolean) {
        hapticFeedback.value = value
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()
    }

    fun setTouchTransferEnabled(value: Boolean) {
        if (value && (!premiumEntitled.value || !serverOrbSwitchEnabled.value)) return
        touchTransferEnabled.value = value
        prefs.edit().putBoolean(KEY_TOUCH_TRANSFER_ENABLED, value).apply()
    }

    fun setPrioritizeAlbumVersions(value: Boolean) {
        prioritizeAlbumVersions.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_ALBUM_VERSIONS, value).apply()
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setHomeAuraEnabled(value: Boolean) {
        homeAuraEnabled.value = value
        prefs.edit().putBoolean(KEY_HOME_AURA_ENABLED, value).apply()
    }


    fun setReduceAnimation(value: Boolean) {
        reduceAnimation.value = value
        prefs.edit().putBoolean(KEY_REDUCE_ANIMATION, value).apply()
    }

    fun setHideStatusBarNowPlaying(value: Boolean) {
        hideStatusBarNowPlaying.value = value
        prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR_NOW_PLAYING, value).apply()
    }

    fun setOrbLockscreenEnabled(value: Boolean) {
        // Visibility is a Premium benefit, not just a disabled switch in Settings.
        val allowed = value && (premiumEntitled.value || isOwnerAccount.value)
        orbLockscreenEnabled.value = allowed
        // A fresh opt-in must immediately have a usable mode, not OFF.
        if (allowed && orbLockscreenMode.value == OrbLockDisplayMode.OFF) {
            setOrbLockscreenMode(OrbLockDisplayMode.ON_WAKE)
        }
        prefs.edit().putBoolean(KEY_ORB_LOCKSCREEN_ENABLED, allowed).apply()
    }

    fun setOrbLockscreenAlwaysOn(value: Boolean) = setOrbLockscreenMode(
        if (value) OrbLockDisplayMode.ALWAYS else OrbLockDisplayMode.OFF
    )

    fun setOrbLockscreenMode(mode: OrbLockDisplayMode) {
        if (!premiumEntitled.value && !isOwnerAccount.value) return
        orbLockscreenMode.value = mode
        orbLockscreenAlwaysOn.value = mode == OrbLockDisplayMode.ALWAYS
        // OFF is an actual off-state, while choosing either display mode enables Orb Lock.
        val enabled = mode != OrbLockDisplayMode.OFF
        orbLockscreenEnabled.value = enabled
        prefs.edit()
            .putString(KEY_ORB_LOCKSCREEN_MODE, mode.name)
            .putBoolean(KEY_ORB_LOCKSCREEN_ALWAYS_ON, mode == OrbLockDisplayMode.ALWAYS)
            .putBoolean(KEY_ORB_LOCKSCREEN_ENABLED, enabled)
            .apply()
    }

    fun setOrbLockscreenAutoBrightness(value: Boolean) {
        if (!premiumEntitled.value && !isOwnerAccount.value) return
        orbLockscreenAutoBrightness.value = value
        prefs.edit().putBoolean(KEY_ORB_LOCKSCREEN_AUTO_BRIGHTNESS, value).apply()
    }

    fun setOrbLockscreenBrightness(value: Float) {
        if (!premiumEntitled.value && !isOwnerAccount.value) return
        val safe = value.coerceIn(0.05f, 1f)
        orbLockscreenBrightness.value = safe
        prefs.edit().putFloat(KEY_ORB_LOCKSCREEN_BRIGHTNESS, safe).apply()
    }

    fun setOrbLockscreenPowerSaving(value: Boolean) {
        if (!premiumEntitled.value && !isOwnerAccount.value) return
        orbLockscreenPowerSaving.value = value
        prefs.edit().putBoolean(KEY_ORB_LOCKSCREEN_POWER_SAVING, value).apply()
    }

    fun setOrbLockscreenHideStatusBar(value: Boolean) {
        if (!premiumEntitled.value && !isOwnerAccount.value) return
        orbLockscreenHideStatusBar.value = value
        prefs.edit().putBoolean(KEY_ORB_LOCKSCREEN_HIDE_STATUS_BAR, value).apply()
    }

    fun setKeepScreenOnNowPlaying(value: Boolean) {
        keepScreenOnNowPlaying.value = value
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON_NOW_PLAYING, value).apply()
    }

    fun setAllowScreenRotation(value: Boolean) {
        allowScreenRotation.value = value
        prefs.edit().putBoolean(KEY_ALLOW_SCREEN_ROTATION, value).apply()
    }

    private fun loadContinuePlaybackAfterRecentRemoval() {
        val accountHash = automix25AccountHash.value
        continuePlaybackAfterRecentRemoval.value = accountHash.isNotEmpty() &&
            prefs.getBoolean("${KEY_CONTINUE_PLAYBACK_AFTER_RECENTS}_$accountHash",
                prefs.getBoolean(KEY_CONTINUE_PLAYBACK_AFTER_RECENTS, false))
    }

    fun setContinuePlaybackAfterRecentRemoval(enabled: Boolean) {
        val accountHash = automix25AccountHash.value
        // Premium is enforced here as well as in the switch and playback service.
        if (!premiumEntitled.value || accountHash.isEmpty()) return
        continuePlaybackAfterRecentRemoval.value = enabled
        prefs.edit()
            .putBoolean("${KEY_CONTINUE_PLAYBACK_AFTER_RECENTS}_$accountHash", enabled)
            .putBoolean(KEY_CONTINUE_PLAYBACK_AFTER_RECENTS, enabled)
            .apply()
    }

    /** This is used by the service, not just for displaying the Premium switch. */
    fun canContinuePlaybackAfterRecentRemoval(): Boolean =
        premiumEntitled.value && automix25AccountHash.value.isNotEmpty() &&
            continuePlaybackAfterRecentRemoval.value

    fun setSwipeToPlayNext(value: Boolean) {
        swipeToPlayNext.value = value
        prefs.edit().putBoolean(KEY_SWIPE_TO_PLAY_NEXT, value).apply()
    }

    fun setBetaUpdatesEnabled(value: Boolean) {
        betaUpdatesEnabled.value = value
        prefs.edit().putBoolean(KEY_BETA_UPDATES_ENABLED, value).apply()
    }

    fun setStableAutoUpdatesEnabled(value: Boolean) {
        stableAutoUpdatesEnabled.value = value
        prefs.edit().putBoolean(KEY_STABLE_AUTO_UPDATES_ENABLED, value).apply()
    }

    fun setReduceDynamicBlur(value: Boolean) {
        reduceDynamicBlur.value = value
        prefs.edit().putBoolean(KEY_REDUCE_BLUR, value).apply()
    }

    fun setSyncedLyrics(value: Boolean) {
        syncedLyrics.value = value
        prefs.edit().putBoolean(KEY_SYNCED_LYRICS, value).apply()
    }

    fun setLyricsSources(value: Set<LyricsSource>) {
        val sanitized = value.toSet()
        lyricsSources.value = sanitized
        prefs.edit().putString(KEY_LYRICS_SOURCES, sanitized.joinToString(",") { it.name }).apply()
    }

    fun setPrioritizeSyllableSync(value: Boolean) {
        prioritizeSyllableSync.value = value
        prefs.edit().putBoolean(KEY_PRIORITIZE_SYLLABLE_SYNC, value).apply()
    }

    fun setOutputPcmMode(value: OutputPcmMode) {
        outputPcmMode.value = value
        prefs.edit().putString(KEY_OUTPUT_PCM_MODE, value.name).apply()
    }

    fun setPreferUsbDac(value: Boolean) {
        preferUsbDac.value = value
        prefs.edit().putBoolean(KEY_PREFER_USB_DAC, value).apply()
    }

    /**
     * Stored as a joined list of names rather than a string set: a name that
     * no longer exists — a source dropped in a later build — has to fall out
     * quietly, and the default when nothing has been saved is "all of them",
     * which a missing key and an empty set would otherwise be unable to tell
     * apart.
     */
    private fun readLyricsSources(): Set<LyricsSource> {
        val stored = prefs.getString(KEY_LYRICS_SOURCES, null)
        if (stored == null) {
            prefs.edit().putBoolean(KEY_LYRICS_PROVIDER_EXPANSION_MIGRATED, true).apply()
            return availableLyricsSources()
        }
        val parsed = stored.split(",")
            .mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }
            
            .toSet()
        if (parsed.isEmpty() && stored.isNotBlank()) {
            val restored = availableLyricsSources()
            prefs.edit().putString(KEY_LYRICS_SOURCES, restored.joinToString(",") { it.name }).apply()
            return restored
        }
        if (prefs.getBoolean(KEY_LYRICS_PROVIDER_EXPANSION_MIGRATED, false)) {
            val normalized = parsed.joinToString(",") { it.name }
            if (stored != normalized) prefs.edit().putString(KEY_LYRICS_SOURCES, normalized).apply()
            return parsed
        }

        // Preserve the user's choices for the four providers Orb already had,
        // but enable every newly introduced provider once on upgrade.
        val legacy = setOf(
            LyricsSource.BETTER_LYRICS,
            LyricsSource.LYRICS_PLUS,
            LyricsSource.SIMP_MUSIC,
            LyricsSource.LRCLIB,
        )
        val migrated = parsed + LyricsSource.entries.filterNot {
            it in legacy
        }
        prefs.edit()
            .putString(KEY_LYRICS_SOURCES, migrated.joinToString(",") { it.name })
            .putBoolean(KEY_LYRICS_PROVIDER_EXPANSION_MIGRATED, true)
            .apply()
        return migrated
    }

    fun setAnimatedCanvas(value: Boolean) {
        animatedCanvas.value = value
        prefs.edit().putBoolean(KEY_ANIMATED_CANVAS, value).apply()
    }

    /** Clamped to [DEFAULT_CACHE_LIMIT_BYTES]..[MAX_CACHE_LIMIT_BYTES] — the floor is the default, not zero. */
    fun setAudioCacheLimitBytes(value: Long) {
        val clamped = value.coerceIn(DEFAULT_CACHE_LIMIT_BYTES, MAX_CACHE_LIMIT_BYTES)
        audioCacheLimitBytes.value = clamped
        prefs.edit().putLong(KEY_CACHE_LIMIT, clamped).apply()
    }

    fun setLastfmEnabled(value: Boolean) {
        lastfmEnabled.value = value
        prefs.edit().putBoolean(KEY_LASTFM_ENABLED, value).apply()
    }

    fun setLastfmUsername(value: String) {
        lastfmUsername.value = value
        prefs.edit().putString(KEY_LASTFM_USERNAME, value).apply()
    }

    fun setLastfmSessionKey(value: String) {
        lastfmSessionKey.value = value
        prefs.edit().putString(KEY_LASTFM_SESSION_KEY, value).apply()
    }

    fun setLastfmApiKey(value: String) {
        lastfmApiKey.value = value
        prefs.edit().putString(KEY_LASTFM_API_KEY, value).apply()
    }

    fun setLastfmSecret(value: String) {
        lastfmSecret.value = value
        prefs.edit().putString(KEY_LASTFM_SECRET, value).apply()
    }

    fun setLastfmEndpoint(value: String) {
        lastfmEndpoint.value = value
        prefs.edit().putString(KEY_LASTFM_ENDPOINT, value).apply()
    }

    fun setLastfmScrobbleEnabled(value: Boolean) {
        lastfmScrobbleEnabled.value = value
        prefs.edit().putBoolean(KEY_LASTFM_SCROBBLE_ENABLED, value).apply()
    }

    fun setLastfmNowPlaying(value: Boolean) {
        lastfmNowPlaying.value = value
        prefs.edit().putBoolean(KEY_LASTFM_NOW_PLAYING, value).apply()
    }

    fun setScrobbleMinDuration(value: Int) {
        scrobbleMinDuration.value = value
        prefs.edit().putInt(KEY_SCROBBLE_MIN_DURATION, value).apply()
    }

    fun setScrobbleDelayPercent(value: Float) {
        scrobbleDelayPercent.value = value
        prefs.edit().putFloat(KEY_SCROBBLE_DELAY_PERCENT, value).apply()
    }

    fun setScrobbleDelaySeconds(value: Int) {
        scrobbleDelaySeconds.value = value
        prefs.edit().putInt(KEY_SCROBBLE_DELAY_SECONDS, value).apply()
    }

    fun setListenBrainzEnabled(value: Boolean) {
        listenBrainzEnabled.value = value
        prefs.edit().putBoolean(KEY_LISTENBRAINZ_ENABLED, value).apply()
    }

    fun setListenBrainzToken(value: String) {
        listenBrainzToken.value = value
        prefs.edit().putString(KEY_LISTENBRAINZ_TOKEN, value).apply()
    }

    /** Writes through to the encrypted store; pass "" to disconnect. */
    fun setDiscordToken(value: String) {
        discordToken.value = value
        authStore.discordToken = value.ifEmpty { null }
        AccountSettingsSync.notifyIntegrationCredentialChanged("discord")
    }

    fun setDiscordAccount(username: String, name: String, avatar: String?) {
        discordUsername.value = username
        discordName.value = name
        discordAvatar.value = avatar.orEmpty()
        prefs.edit()
            .putString(KEY_DISCORD_USERNAME, username)
            .putString(KEY_DISCORD_NAME, name)
            .putString(KEY_DISCORD_AVATAR, avatar.orEmpty())
            .apply()
    }

    fun setDiscordRpcEnabled(value: Boolean) {
        discordRpcEnabled.value = value
        prefs.edit().putBoolean(KEY_DISCORD_RPC_ENABLED, value).apply()
    }

    fun setDiscordUseDetails(value: Boolean) {
        discordUseDetails.value = value
        prefs.edit().putBoolean(KEY_DISCORD_USE_DETAILS, value).apply()
    }

    fun setDiscordAdvancedMode(value: Boolean) {
        discordAdvancedMode.value = value
        prefs.edit().putBoolean(KEY_DISCORD_ADVANCED_MODE, value).apply()
    }

    fun setDiscordStatus(value: String) {
        discordStatus.value = value
        prefs.edit().putString(KEY_DISCORD_STATUS, value).apply()
    }

    fun setDiscordActivityType(value: String) {
        discordActivityType.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_TYPE, value).apply()
    }

    fun setDiscordActivityName(value: String) {
        discordActivityName.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_NAME, value).apply()
    }

    fun setDiscordButton1Text(value: String) {
        discordButton1Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_1_TEXT, value).apply()
    }

    fun setDiscordButton1Visible(value: Boolean) {
        discordButton1Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, value).apply()
    }

    fun setDiscordButton2Text(value: String) {
        discordButton2Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_2_TEXT, value).apply()
    }

    fun setDiscordButton2Visible(value: Boolean) {
        discordButton2Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, value).apply()
    }

    fun setDiscordInfoDismissed(value: Boolean) {
        discordInfoDismissed.value = value
        prefs.edit().putBoolean(KEY_DISCORD_INFO_DISMISSED, value).apply()
    }

    fun statsNotificationsSeenAtMs(userId: String): Long =
        prefs.getLong("${KEY_STATS_NOTIFICATIONS_SEEN_AT}_$userId", 0L)

    fun markStatsNotificationsSeen(userId: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("${KEY_STATS_NOTIFICATIONS_SEEN_AT}_$userId", nowMs).apply()
    }

    /**
     * One-shot discovery hint introduced with the Stats ranking card update.
     * The key is deliberately versioned: existing installations see the hint
     * once after installing this update, while later launches stay quiet.
     */
    fun shouldShowStatsArtistRankingHint(): Boolean =
        !prefs.getBoolean(KEY_STATS_ARTIST_RANKING_HINT_V161_SEEN, false)

    fun markStatsArtistRankingHintSeen() {
        prefs.edit().putBoolean(KEY_STATS_ARTIST_RANKING_HINT_V161_SEEN, true).apply()
    }

    /** Forgets the account: token and cached profile. */
    fun clearDiscordAccount() {
        setDiscordToken("")
        setDiscordAccount("", "", null)
    }

    const val DEFAULT_CACHE_LIMIT_BYTES = 1L * 1024 * 1024 * 1024
    const val MAX_CACHE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

    private const val KEY_QUALITY_LEGACY = "audio_quality"
    private const val KEY_QUALITY_WIFI = "audio_quality_wifi"
    private const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
    private const val KEY_QUALITY_WIFI_MAXIMUM = "audio_quality_wifi_maximum"
    private const val KEY_QUALITY_CELLULAR_MAXIMUM = "audio_quality_cellular_maximum"
    private const val KEY_AAC_DEFAULT_MIGRATED = "audio_quality_aac_default_migrated_v2"
    private const val KEY_LOSSLESS = "lossless_audio"
    private const val KEY_DOWNLOAD_QUALITY = "offline_download_quality"
    private const val KEY_DOWNLOAD_FALLBACK_HIGH_V3 = "offline_download_fallback_high_v3"
    private const val KEY_USE_DOWNLOADS_WHILE_ONLINE = "use_downloads_while_online"
    private const val KEY_CROSSFADE = "crossfade_seconds"
    private const val KEY_AUTOMIX = "automix_enabled_v2"
    private const val KEY_AUTOMIX_VERSION = "automix_version"
    private const val KEY_SERVER_PREMIUM_PLAN = "server_premium_plan"
    private const val KEY_SERVER_PREMIUM_SOURCE = "server_premium_source"
    private const val KEY_SERVER_PREMIUM_EXPIRES_AT = "server_premium_expires_at"
    private const val KEY_SERVER_AUTOMIX25_ENABLED = "server_automix25_enabled"
    private const val KEY_SERVER_ORB_SWITCH_ENABLED = "server_orb_switch_enabled"
    private const val KEY_SERVER_PREMIUM_CARD_ENABLED = "server_premium_card_enabled"
    private const val KEY_SERVER_ROOMS_ENABLED = "server_rooms_enabled"
    private const val KEY_SERVER_LOSSLESS_MOBILE_ENABLED = "server_lossless_mobile_enabled"
    private const val KEY_SERVER_REFRESH_INTERVAL_SECONDS = "server_control_refresh_interval_seconds"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_SPATIAL_AUDIO = "spatial_audio"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_HOME_AURA_ENABLED = "home_aura_enabled"
    private const val KEY_PRIORITIZE_ALBUM_VERSIONS = "prioritize_album_versions"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_AUTOPLAY_EXPLICIT = "autoplay_explicit"
    private const val KEY_USE_YOUTUBE_MUSIC_LIBRARY = "use_youtube_music_library"
    private const val KEY_NERD_STATS = "show_nerd_stats"
    private const val KEY_UPDATE_REMINDER_UNTIL = "orb_update_reminder_until"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    private const val KEY_TOUCH_TRANSFER_ENABLED = "touch_transfer_enabled"
    private const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
    private const val KEY_REDUCE_ANIMATION = "reduce_animation"
    private const val KEY_HIDE_STATUS_BAR_NOW_PLAYING = "hide_status_bar_now_playing"
    private const val KEY_ORB_LOCKSCREEN_ENABLED = "orb_lockscreen_enabled"
    private const val KEY_ORB_LOCKSCREEN_ALWAYS_ON = "orb_lockscreen_always_on"
    private const val KEY_ORB_LOCKSCREEN_MODE = "orb_lockscreen_display_mode"
    private const val KEY_ORB_LOCKSCREEN_AUTO_BRIGHTNESS = "orb_lockscreen_auto_brightness"
    private const val KEY_ORB_LOCKSCREEN_BRIGHTNESS = "orb_lockscreen_brightness"
    private const val KEY_ORB_LOCKSCREEN_POWER_SAVING = "orb_lockscreen_power_saving"
    private const val KEY_ORB_LOCKSCREEN_HIDE_STATUS_BAR = "orb_lockscreen_hide_status_bar"
    private const val KEY_KEEP_SCREEN_ON_NOW_PLAYING = "keep_screen_on_now_playing"
    private const val KEY_ALLOW_SCREEN_ROTATION = "allow_screen_rotation"
    private const val KEY_CONTINUE_PLAYBACK_AFTER_RECENTS = "continue_playback_after_recents"
    private const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
    private const val KEY_BETA_UPDATES_ENABLED = "beta_updates_enabled"
    private const val KEY_STABLE_AUTO_UPDATES_ENABLED = "stable_auto_updates_enabled"
    private const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
    private const val KEY_ANIMATED_CANVAS = "animated_canvas"
    private const val KEY_SYNCED_LYRICS = "synced_lyrics"
    private const val KEY_LYRICS_SOURCES = "lyrics_sources"
    private const val KEY_LYRICS_PROVIDER_EXPANSION_MIGRATED = "lyrics_provider_expansion_v16_migrated"
    private const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
    private const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"
    private const val KEY_OUTPUT_PCM_MODE = "output_pcm_mode"
    private const val KEY_PREFER_USB_DAC = "prefer_usb_dac"
    private const val KEY_STATS_NOTIFICATIONS_SEEN_AT = "stats_notifications_seen_at"
    private const val KEY_STATS_ARTIST_RANKING_HINT_V161_SEEN = "stats_artist_ranking_hint_v161_seen"

    private const val KEY_LASTFM_ENABLED = "lastfm_enabled"
    private const val KEY_LASTFM_USERNAME = "lastfm_username"
    private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
    private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
    private const val KEY_LASTFM_SECRET = "lastfm_secret"
    private const val KEY_LASTFM_ENDPOINT = "lastfm_endpoint"
    private const val KEY_LASTFM_SCROBBLE_ENABLED = "lastfm_scrobble_enabled"
    private const val KEY_LASTFM_NOW_PLAYING = "lastfm_now_playing"
    private const val KEY_SCROBBLE_MIN_DURATION = "scrobble_min_duration"
    private const val KEY_SCROBBLE_DELAY_PERCENT = "scrobble_delay_percent"
    private const val KEY_SCROBBLE_DELAY_SECONDS = "scrobble_delay_seconds"
    private const val KEY_LISTENBRAINZ_ENABLED = "listenbrainz_enabled"
    private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"

    private const val KEY_DISCORD_USERNAME = "discord_username"
    private const val KEY_DISCORD_NAME = "discord_name"
    private const val KEY_DISCORD_AVATAR = "discord_avatar"
    private const val KEY_DISCORD_RPC_ENABLED = "discord_rpc_enabled"
    private const val KEY_DISCORD_USE_DETAILS = "discord_use_details"
    private const val KEY_DISCORD_ADVANCED_MODE = "discord_advanced_mode"
    private const val KEY_DISCORD_STATUS = "discord_status"
    private const val KEY_DISCORD_ACTIVITY_TYPE = "discord_activity_type"
    private const val KEY_DISCORD_ACTIVITY_NAME = "discord_activity_name"
    private const val KEY_DISCORD_BUTTON_1_TEXT = "discord_button_1_text"
    private const val KEY_DISCORD_BUTTON_1_VISIBLE = "discord_button_1_visible"
    private const val KEY_DISCORD_BUTTON_2_TEXT = "discord_button_2_text"
    private const val KEY_DISCORD_BUTTON_2_VISIBLE = "discord_button_2_visible"
    private const val KEY_DISCORD_INFO_DISMISSED = "discord_info_dismissed"

    // SHA-256 of the temporary private 2.5 preview account. Future Premium
    // subscribers are authorized through [premiumEntitled], not added here.
    private const val AUTOMIX_25_BETA_OWNER_HASH =
        "2c8c3e1d1bcef1415230705c38bafb7403905850a7f37c5206bd5cfc055c6aeb"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}


