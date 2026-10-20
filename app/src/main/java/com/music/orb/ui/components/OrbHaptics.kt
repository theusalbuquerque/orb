package com.music.orb.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.music.orb.R
import com.music.orb.data.settings.AppSettings

/** Small, permission-free tactile confirmations for high-value UI actions. */
object OrbHaptics {
    enum class Kind { ACTION, TOGGLE, LIKE, NAVIGATION, CAROUSEL, TRANSFER_ARMED, TRANSFER_ACTIVE, TRANSFER_COMPLETE }

    @Volatile private var carouselSoundPool: SoundPool? = null
    @Volatile private var carouselSoundId: Int = 0
    @Volatile private var carouselSoundLoaded: Boolean = false
    @Volatile private var lastCarouselSoundMs: Long = 0L

    @Volatile private var uiSoundPool: SoundPool? = null
    @Volatile private var ratingTickSoundId: Int = 0
    @Volatile private var reactionPopSoundId: Int = 0
    @Volatile private var reactionWhooshSoundId: Int = 0
    @Volatile private var ratingSavedSparkleSoundId: Int = 0
    @Volatile private var refreshBubblePopSoundId: Int = 0
    @Volatile private var ratingTickLoaded: Boolean = false
    @Volatile private var reactionPopLoaded: Boolean = false
    @Volatile private var reactionWhooshLoaded: Boolean = false
    @Volatile private var ratingSavedSparkleLoaded: Boolean = false
    @Volatile private var refreshBubblePopLoaded: Boolean = false
    @Volatile private var lastOverlayEnterMs: Long = 0L
    @Volatile private var lastOverlayExitMs: Long = 0L
    @Volatile private var lastOverlayTapMs: Long = 0L
    @Volatile private var lastRatingTickMs: Long = 0L
    @Volatile private var lastGenericPerformMs: Long = 0L

    /** A brief pulse for individual room artwork arrivals. */
    fun roomArtworkTick(context: Context) {
        if (!AppSettings.hapticFeedback.value) return
        val vibrator = transferVibrator(context.applicationContext)
        val applied = vibrator?.let {
            runCatching {
                if (!it.hasVibrator()) false else {
                    it.vibrate(VibrationEffect.createOneShot(12, boostedAmplitude(80)))
                    true
                }
            }.getOrDefault(false)
        } ?: false
        if (!applied) perform(context, Kind.NAVIGATION)
    }

    fun startTransferGlow(context: Context) {
        if (!AppSettings.hapticFeedback.value) return
        val vibrator = transferVibrator(context.applicationContext) ?: return
        runCatching {
            vibrator.cancel()
            // TouchTransferOverlay uses 300 ms brighten + 380 ms dim = 680 ms.
            // Pulse at the brightness peak, then repeat exactly once per glow cycle.
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(300, 24, 356),
                    intArrayOf(0, 92, 0).map(::boostedAmplitude).toIntArray(),
                    0,
                ),
            )
        }
    }

    fun startTransferMotion(context: Context, durationMs: Long = 1700L) {
        if (!AppSettings.hapticFeedback.value) return
        val vibrator = transferVibrator(context.applicationContext) ?: return
        runCatching {
            vibrator.cancel()
            // ~1.06 s, matching the 1.08 s cover travel. Gaps continuously shrink while
            // amplitude rises, so the haptic rhythm visibly "accelerates" with the artwork.
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(
                        0, 18,
                        220, 18,
                        170, 19,
                        130, 20,
                        95, 21,
                        68, 22,
                        48, 23,
                        34, 24,
                        24, 25,
                        16, 26,
                        10, 27,
                    ).let { timings ->
                        val factor = durationMs.toDouble() / timings.sum().coerceAtLeast(1L)
                        LongArray(timings.size) { index ->
                            if(timings[index]==0L)0L else (timings[index]*factor).toLong().coerceAtLeast(1L)
                        }
                    },
                    intArrayOf(
                        0, 65,
                        0, 78,
                        0, 92,
                        0, 108,
                        0, 126,
                        0, 145,
                        0, 164,
                        0, 184,
                        0, 205,
                        0, 225,
                        0, 245,
                    ).map(::boostedAmplitude).toIntArray(),
                    -1,
                ),
            )
        }
    }

    fun completeTransfer(context: Context) {
        if (!AppSettings.hapticFeedback.value) return
        val vibrator = transferVibrator(context.applicationContext) ?: return
        runCatching {
            vibrator.cancel()
            // Strong, short confirmation, then silence.
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 40, 24, 58),
                    intArrayOf(0, 220, 0, 255).map(::boostedAmplitude).toIntArray(),
                    -1,
                ),
            )
        }
    }

    fun stopTransfer(context: Context) {
        val vibrator = transferVibrator(context.applicationContext) ?: return
        runCatching { vibrator.cancel() }
    }

    private fun transferVibrator(context: Context): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return null
        return vibrator.takeIf { it.hasVibrator() }
    }

    private fun boostedAmplitude(value: Int): Int = (value*1.10f).roundToInt().coerceIn(0,255)

    @Composable
    fun transportClick(action: () -> Unit): () -> Unit {
        val context = LocalContext.current
        return { action(); perform(context, Kind.ACTION) }
    }

    fun perform(context: Context, kind: Kind = Kind.ACTION) {
        val now = SystemClock.uptimeMillis()
        if (now - lastGenericPerformMs < 18L) return
        lastGenericPerformMs = now
        // The user's Respostas táteis toggle gates the entire feedback family.
        if (!AppSettings.hapticFeedback.value) return

        if (kind == Kind.TRANSFER_ARMED || kind == Kind.TRANSFER_ACTIVE || kind == Kind.TRANSFER_COMPLETE) {
            when (kind) {
                Kind.TRANSFER_ARMED -> startTransferGlow(context)
                Kind.TRANSFER_ACTIVE -> startTransferMotion(context)
                Kind.TRANSFER_COMPLETE -> completeTransfer(context)
                else -> Unit
            }
            return
        }

        // Never make a navigation/click callback wait on the vibrator Binder.
        // Posting the tactile confirmation lets Compose commit the requested UI
        // state in the current input turn; the haptic lands a few milliseconds
        // later without creating the old "I tapped, then it reacted" feel.
        val decor = context.findActivity()?.window?.decorView
        if (decor != null) {
            decor.post { performStandardFeedback(context, kind) }
        } else {
            performStandardFeedback(context, kind)
        }
    }

    /** Complete the final tactile tick synchronously before the user turns
     *  haptics off. perform() posts to the decor and would otherwise observe
     *  the new disabled preference by the time it runs. */
    fun performToggleBeforeDisabling(context: Context) {
        if (AppSettings.hapticFeedback.value) {
            performStandardFeedback(context, Kind.TOGGLE)
        }
    }

    private fun performStandardFeedback(context: Context, kind: Kind) {
        if (!AppSettings.hapticFeedback.value) return
        val activity = context.findActivity()
        val applied = runCatching {
            val vibrator = transferVibrator(context) ?: return@runCatching false
            if (!vibrator.hasAmplitudeControl()) return@runCatching false
            val subtle = kind == Kind.TOGGLE || kind == Kind.NAVIGATION || kind == Kind.CAROUSEL
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    if (subtle) 12L else 20L,
                    boostedAmplitude(if (subtle) 80 else 140),
                ),
            )
            true
        }.getOrDefault(false)
        val feedback = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && kind == Kind.LIKE -> HapticFeedbackConstants.CONFIRM
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (kind == Kind.TOGGLE || kind == Kind.NAVIGATION || kind == Kind.CAROUSEL) ->
                HapticFeedbackConstants.CLOCK_TICK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
            kind == Kind.TOGGLE || kind == Kind.NAVIGATION || kind == Kind.CAROUSEL ->
                HapticFeedbackConstants.CLOCK_TICK
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        if (!applied) activity?.window?.decorView?.performHapticFeedback(feedback)
        if (kind == Kind.CAROUSEL) playCarouselRollTick(context.applicationContext)
    }


    /** Subtle open pulse for sheets/popups; duplicate nested glass layers are debounced. */
    fun overlayEnter(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayEnterMs < 140L) return
        lastOverlayEnterMs = now
        ensureUiSounds(context.applicationContext)
        perform(context, Kind.NAVIGATION)
    }

    /** Slightly softer close pulse so dismissal feels distinct from activation. */
    fun overlayExit(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayExitMs < 140L) return
        lastOverlayExitMs = now
        if (!AppSettings.hapticFeedback.value) return
        val vibrator = transferVibrator(context.applicationContext)
        val applied = vibrator?.let {
            runCatching {
                it.vibrate(VibrationEffect.createOneShot(10L, boostedAmplitude(62)))
                true
            }.getOrDefault(false)
        } ?: false
        if (!applied) context.findActivity()?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Passive tap observer used by glass overlays. It never consumes the actual click. */
    fun overlayTap(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayTapMs < 55L) return
        lastOverlayTapMs = now
        perform(context, Kind.TOGGLE)
    }

    /** One tactile/audio notch each time the rating scrub crosses a half-star boundary. */
    fun ratingStarTick(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastRatingTickMs < 38L) return
        lastRatingTickMs = now
        perform(context, Kind.NAVIGATION)
        ensureUiSounds(context.applicationContext)
        playUiSound(ratingTickSoundId, ratingTickLoaded, volume = 0.20f, rate = 1f)
    }


    /** Bright, compact confirmation used when a saved rating collapses into its star action. */
    fun ratingSavedSparkle(context: Context) {
        ensureUiSounds(context.applicationContext)
        if (AppSettings.hapticFeedback.value) {
            val vibrator = transferVibrator(context.applicationContext)
            val applied = vibrator?.let {
                runCatching {
                    it.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0L, 14L, 22L, 20L),
                            intArrayOf(0, boostedAmplitude(95), 0, boostedAmplitude(165)),
                            -1,
                        ),
                    )
                    true
                }.getOrDefault(false)
            } ?: false
            if (!applied) {
                context.findActivity()?.window?.decorView
                    ?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
        playUiSound(ratingSavedSparkleSoundId, ratingSavedSparkleLoaded, volume = 0.30f, rate = 1f)
    }

    /** Bubble-like landing feedback for a reaction reaching the friend's card. */
    fun reactionPop(context: Context) {
        if (!AppSettings.hapticFeedback.value) return
        ensureUiSounds(context.applicationContext)
        val vibrator = transferVibrator(context.applicationContext)
        val applied = vibrator?.let {
            runCatching {
                it.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 16L, 18L, 24L),
                        intArrayOf(0, boostedAmplitude(105), 0, boostedAmplitude(175)),
                        -1,
                    ),
                )
                true
            }.getOrDefault(false)
        } ?: false
        if (!applied) context.findActivity()?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        playUiSound(reactionPopSoundId, reactionPopLoaded, volume = 0.30f, rate = 1f)
    }

    /** Airy removal feedback paired with the smoke/evaporation reaction animation. */
    fun reactionWhoosh(context: Context) {
        if (!AppSettings.hapticFeedback.value) return
        ensureUiSounds(context.applicationContext)
        val vibrator = transferVibrator(context.applicationContext)
        val applied = vibrator?.let {
            runCatching {
                it.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 12L, 18L, 10L),
                        intArrayOf(0, boostedAmplitude(90), 0, boostedAmplitude(55)),
                        -1,
                    ),
                )
                true
            }.getOrDefault(false)
        } ?: false
        if (!applied) context.findActivity()?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        playUiSound(reactionWhooshSoundId, reactionWhooshLoaded, volume = 0.24f, rate = 1f)
    }

    /** Prepare the short liquid pop before the first pull gesture. */
    fun prepareRefreshBubblePop(context: Context) {
        ensureUiSounds(context.applicationContext)
    }

    /** Played exactly once when the camera-anchored membrane pinches off. */
    fun refreshBubblePop(context: Context) {
        ensureUiSounds(context.applicationContext)
        // UI sonification is independent of the haptic-feedback setting.
        playUiSound(refreshBubblePopSoundId, refreshBubblePopLoaded, volume = 0.38f, rate = 1f)
    }

    private fun ensureUiSounds(context: Context) {
        if (uiSoundPool != null) return
        synchronized(this) {
            if (uiSoundPool != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(attrs)
                .build()
            uiSoundPool = pool
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) return@setOnLoadCompleteListener
                when (sampleId) {
                    ratingTickSoundId -> ratingTickLoaded = true
                    reactionPopSoundId -> reactionPopLoaded = true
                    reactionWhooshSoundId -> reactionWhooshLoaded = true
                    ratingSavedSparkleSoundId -> ratingSavedSparkleLoaded = true
                    refreshBubblePopSoundId -> refreshBubblePopLoaded = true
                }
            }
            ratingTickSoundId = pool.load(context, R.raw.orb_rating_star_tick, 1)
            reactionPopSoundId = pool.load(context, R.raw.orb_reaction_pop, 1)
            reactionWhooshSoundId = pool.load(context, R.raw.orb_reaction_whoosh, 1)
            ratingSavedSparkleSoundId = pool.load(context, R.raw.orb_rating_saved_sparkle, 1)
            refreshBubblePopSoundId = pool.load(context, R.raw.orb_refresh_bubble_pop, 1)
        }
    }

    private fun playUiSound(soundId: Int, loaded: Boolean, volume: Float, rate: Float) {
        val pool = uiSoundPool ?: return
        if (!loaded || soundId == 0) return
        pool.play(soundId, volume, volume, 0, 0, rate.coerceIn(0.5f, 2f))
    }

    private fun playCarouselRollTick(context: Context) {
        var pool = carouselSoundPool
        if (pool == null) {
            synchronized(this) {
                pool = carouselSoundPool
                if (pool == null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    pool = SoundPool.Builder()
                        .setMaxStreams(4)
                        .setAudioAttributes(attrs)
                        .build()
                        .also { soundPool ->
                            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                                if (sampleId == carouselSoundId && status == 0) carouselSoundLoaded = true
                            }
                            carouselSoundId = soundPool.load(context, R.raw.orb_carousel_roll_tick, 1)
                            carouselSoundPool = soundPool
                        }
                }
            }
        }
        val readyPool = carouselSoundPool ?: return
        if (!carouselSoundLoaded || carouselSoundId == 0) return

        // Keep the carousel sound soft and low. Fast swipes should feel like a
        // continuous muted roller, not a sequence of bright UI clicks, so the
        // playback rate stays at/below the sample's original pitch and volume is
        // deliberately well under the rest of Android's sonification family.
        val now = SystemClock.uptimeMillis()
        val delta = (now - lastCarouselSoundMs).coerceAtLeast(0L)
        lastCarouselSoundMs = now
        val rate = when {
            delta in 1..70 -> 0.96f
            delta in 71..120 -> 0.92f
            else -> 0.88f
        }
        readyPool.play(carouselSoundId, 0.15f, 0.15f, 0, 0, rate)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
