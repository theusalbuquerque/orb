package com.music.orb.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.HapticFeedbackConstants
import com.music.orb.data.settings.AppSettings

/** Small, permission-free tactile confirmations for high-value UI actions. */
object OrbHaptics {
    enum class Kind { ACTION, TOGGLE, LIKE }

    fun perform(context: Context, kind: Kind = Kind.ACTION) {
        if (!AppSettings.hapticFeedback.value) return
        val activity = context.findActivity() ?: return
        val feedback = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && kind == Kind.LIKE -> HapticFeedbackConstants.CONFIRM
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && kind == Kind.TOGGLE -> HapticFeedbackConstants.CLOCK_TICK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
            kind == Kind.TOGGLE -> HapticFeedbackConstants.CLOCK_TICK
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        activity.window.decorView.performHapticFeedback(feedback)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
