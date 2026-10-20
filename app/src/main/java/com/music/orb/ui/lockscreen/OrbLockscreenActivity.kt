package com.music.orb.ui.lockscreen

import android.app.KeyguardManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.OrbLockDisplayMode
import com.music.orb.R
import com.music.orb.ui.theme.OrbTheme

/** A separate task; finishing it reveals the user's PREVIOUS foreground app. */
class OrbLockscreenActivity : FragmentActivity() {
    companion object {
        // PlaybackService and this Activity run in the same app process.
        // Unlike a one-shot service flag, the marker ends with the Activity.
        @Volatile private var activityActive = false
        // The system can deliver MediaSessionService.onTaskRemoved with a null
        // rootIntent while this separate task is being closed. Keep a short,
        // monotonic-time marker so the player service can distinguish that
        // callback from the user's removal of the actual Orb task.
        @Volatile private var lastLockscreenFinishElapsedMs = 0L

        private const val TASK_REMOVAL_GRACE_MS = 5_000L

        fun isActive(): Boolean = activityActive

        fun hasRecentlyFinished(): Boolean {
            val lastFinish = lastLockscreenFinishElapsedMs
            if (lastFinish == 0L) return false
            val elapsed = SystemClock.elapsedRealtime() - lastFinish
            return elapsed >= 0L && elapsed <= TASK_REMOVAL_GRACE_MS
        }
    }

    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private var unlocked = false
    private var brightnessAnimator: ValueAnimator? = null
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> finishLockscreen()
                Intent.ACTION_SCREEN_OFF -> {
                    // Passive display mode must never wake the device on its own.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                        AppSettings.orbLockscreenMode.value != OrbLockDisplayMode.ALWAYS
                    ) setTurnScreenOn(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppSettings.orbLockscreenEnabled.value ||
            AppSettings.orbLockscreenMode.value == OrbLockDisplayMode.OFF ||
            !(AppSettings.premiumEntitled.value || AppSettings.isOwnerAccount.value)
        ) { finishLockscreen(); return }
        activityActive = true
        Log.d("OrbLockscreen", "Activity created for current lock cycle")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            // The always-on choice may wake the display; ON_WAKE is strictly passive.
            // No cross-app notification listener or alert-triggered wake is needed.
            setTurnScreenOn(AppSettings.orbLockscreenMode.value == OrbLockDisplayMode.ALWAYS)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // Never dismiss the secure keyguard automatically or change global brightness.
        // This Activity has a translucent window only so that the previously
        // unlocked task becomes visible behind the upward exit animation.
        // Orb's Compose surface itself remains opaque while locked.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(unlockReceiver, filter)
        }
        setContent {
            val enabled by AppSettings.orbLockscreenEnabled.collectAsStateWithLifecycle()
            val entitled by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
            val owner by AppSettings.isOwnerAccount.collectAsStateWithLifecycle()
            val displayMode by AppSettings.orbLockscreenMode.collectAsStateWithLifecycle()
            val autoBrightness by AppSettings.orbLockscreenAutoBrightness.collectAsStateWithLifecycle()
            val brightness by AppSettings.orbLockscreenBrightness.collectAsStateWithLifecycle()
            val hideStatusBar by AppSettings.orbLockscreenHideStatusBar.collectAsStateWithLifecycle()
            LaunchedEffect(enabled, entitled, owner, displayMode) {
                if (!enabled || displayMode == OrbLockDisplayMode.OFF || !(entitled || owner)) finishLockscreen()
            }
            DisposableEffect(displayMode, autoBrightness, brightness) {
                applyDisplayPreferences(displayMode, autoBrightness, brightness)
                onDispose { /* Activity's window is cleaned up on finish. */ }
            }
            DisposableEffect(hideStatusBar) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (hideStatusBar) {
                    // This Activity has its own window; never modify MainActivity's bars.
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
                onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
            }
            OrbTheme(darkTheme = true) {
                OrbLockscreenContent(
                    onUnlock = { finishLockscreen() },
                    onPlaybackUnavailable = { finishLockscreen() },
                )
            }
        }
    }

    /** Only this window is adjusted; the user's global brightness never changes. */
    private fun applyDisplayPreferences(
        mode: OrbLockDisplayMode,
        automatic: Boolean,
        brightness: Float,
    ) {
        if (mode == OrbLockDisplayMode.ALWAYS) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        brightnessAnimator?.cancel()
        brightnessAnimator = null
        val oldValue = window.attributes.screenBrightness
        val device = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        }.getOrDefault(128).coerceIn(1, 255) / 255f
        if (automatic) {
            // Ease back towards the user's device brightness first; only then
            // hand brightness authority back to the system's automatic policy.
            if (oldValue in 0f..1f && kotlin.math.abs(oldValue - device) > 0.01f) {
                brightnessAnimator = ValueAnimator.ofFloat(oldValue, device).apply {
                    duration = 650L
                    interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    addUpdateListener { frame ->
                        val attrs = window.attributes
                        attrs.screenBrightness = frame.animatedValue as Float
                        window.attributes = attrs
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        private var cancelled = false
                        override fun onAnimationCancel(animation: Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled && AppSettings.orbLockscreenAutoBrightness.value) {
                                val attrs = window.attributes
                                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                window.attributes = attrs
                            }
                        }
                    })
                    start()
                }
            } else {
                val params = window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
            }
            return
        }
        val target = brightness.coerceIn(0.05f, 1f)
        val start = if (oldValue in 0f..1f) oldValue else device
        if (kotlin.math.abs(start - target) < 0.01f) {
            val attrs = window.attributes
            attrs.screenBrightness = target
            window.attributes = attrs
            return
        }
        brightnessAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 650L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { animator ->
                val attrs = window.attributes
                attrs.screenBrightness = animator.animatedValue as Float
                window.attributes = attrs
            }
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        // If the keyguard has already gone away, never sit above WhatsApp/other apps.
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (unlocked || (!keyguard.isKeyguardLocked && power.isInteractive)) finishLockscreen()
    }

    override fun onStop() {
        super.onStop()
        // Some OEM biometric flows return to the previous app without sending
        // ACTION_USER_PRESENT to our receiver. Retire the task as soon as the
        // unlocked device has moved it out of the foreground.
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!keyguard.isKeyguardLocked && power.isInteractive) finishLockscreen()
    }

    private fun finishLockscreen() {
        if (unlocked) return
        unlocked = true
        activityActive = false
        lastLockscreenFinishElapsedMs = SystemClock.elapsedRealtime()
        Log.d("OrbLockscreen", "Unlocking: slide entire window upward; keep playback running")
        // Do not clear showWhenLocked here: it can trigger the One UI task
        // transition BEFORE our upward translation finishes. Keep the window
        // on screen until the animation ends and only then release keyguard
        // occlusion and the always-on flag.
        if (isFinishing || isDestroyed) return

        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val unlockedBySystem = !keyguard.isKeyguardLocked && power.isInteractive
        val decor = window.decorView
        if (!unlockedBySystem || decor.height <= 0) {
            // Playback unavailable or Activity hidden: do not reveal another
            // app above a STILL-LOCKED keyguard, and never remove its task.
            finishWithoutDefaultTransition()
            return
        }

        // A Samsung task-close transition can override an XML exit animation
        // with a sideways swipe. Translate the actual full-screen surface
        // vertically *before* requesting finish(), then suppress the default
        // close transition. Behind it is the last unlocked task, not Orb.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        decor.animate().cancel()
        decor.animate()
            .translationY(-decor.height.toFloat())
            .setDuration(340L)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .withEndAction { finishWithoutDefaultTransition() }
            .start()
    }

    private fun finishWithoutDefaultTransition() {
        if (isFinishing || isDestroyed) return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        finish()
        // The previous Activity/task must not perform a horizontal animation.
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        brightnessAnimator?.cancel()
        brightnessAnimator = null
        activityActive = false
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }
}
