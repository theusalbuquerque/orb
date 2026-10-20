package com.music.orb.ui.components

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import com.music.orb.R

// MediaRouter reads colors from the constructor's context before applying its dialog style.
// Wrap that context too: setting DialogFragment's style alone leaves Orb's platform theme visible.
class OrbCastChooserDialogFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?): MediaRouteChooserDialog =
        MediaRouteChooserDialog(ContextThemeWrapper(context,R.style.Theme_Orb_CastDialog),R.style.Theme_Orb_CastDialog)
}

class OrbCastControllerDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?): MediaRouteControllerDialog =
        MediaRouteControllerDialog(ContextThemeWrapper(context,R.style.Theme_Orb_CastDialog),R.style.Theme_Orb_CastDialog)
}
