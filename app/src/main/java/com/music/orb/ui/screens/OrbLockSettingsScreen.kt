package com.music.orb.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.orb.R
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.OrbLockDisplayMode
import com.music.orb.ui.components.OrbHaptics

/** Dedicated Orb Lock settings page; never bypasses Android's secure keyguard. */
@Composable
fun OrbLockSettingsScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled by AppSettings.orbLockscreenEnabled.collectAsStateWithLifecycle()
    val entitled by AppSettings.premiumEntitled.collectAsStateWithLifecycle()
    val owner by AppSettings.isOwnerAccount.collectAsStateWithLifecycle()
    val allowed = entitled || owner
    fun feedback() = OrbHaptics.perform(context, OrbHaptics.Kind.TOGGLE)
    fun toggleEnabled(value: Boolean) {
        if (!allowed) return
        AppSettings.setOrbLockscreenEnabled(value)
        feedback()
    }
    fun togglePowerSaving(value: Boolean) {
        if (!allowed) return
        AppSettings.setOrbLockscreenPowerSaving(value)
        feedback()
    }
    fun toggleStatusBar(value: Boolean) {
        if (!allowed) return
        AppSettings.setOrbLockscreenHideStatusBar(value)
        feedback()
    }
    fun toggleAutoBrightness(value: Boolean) {
        if (!allowed) return
        AppSettings.setOrbLockscreenAutoBrightness(value)
        feedback()
    }
    val lockMode by AppSettings.orbLockscreenMode.collectAsStateWithLifecycle()
    val autoBrightness by AppSettings.orbLockscreenAutoBrightness.collectAsStateWithLifecycle()
    var showModePicker by remember { mutableStateOf(false) }
    val brightness by AppSettings.orbLockscreenBrightness.collectAsStateWithLifecycle()
    val powerSaving by AppSettings.orbLockscreenPowerSaving.collectAsStateWithLifecycle()
    val hideStatusBar by AppSettings.orbLockscreenHideStatusBar.collectAsStateWithLifecycle()
    var overlayGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context))
    }
    LifecycleResumeEffect(Unit) {
        overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
    ) {
        if (!allowed) {
            Text(
                text = stringResource(R.string.orb_lock_premium_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
        SettingsGroup(header = stringResource(R.string.orb_lock_settings_header)) {
            SettingsRow(
                icon = Icons.Rounded.DevicesOther,
                title = stringResource(R.string.settings_orb_lockscreen),
                subtitle = stringResource(R.string.settings_orb_lockscreen_subtitle),
                enabled = allowed,
                trailing = {
                    Switch(
                        checked = enabled && allowed,
                        enabled = allowed,
                        onCheckedChange = ::toggleEnabled,
                    )
                },
                onClick = { toggleEnabled(!enabled) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.DevicesOther,
                title = stringResource(R.string.orb_lockscreen_overlay_permission_title),
                subtitle = stringResource(
                    if (overlayGranted) R.string.orb_lockscreen_overlay_permission_granted
                    else R.string.orb_lockscreen_overlay_permission_subtitle,
                ),
                enabled = allowed,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !overlayGranted) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        }
                    }
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Brightness4,
                title = stringResource(R.string.orb_lockscreen_always_on_title),
                subtitle = when (lockMode) {
                    OrbLockDisplayMode.ALWAYS -> stringResource(R.string.orb_lock_mode_always)
                    OrbLockDisplayMode.ON_WAKE -> stringResource(R.string.orb_lock_mode_notifications)
                    OrbLockDisplayMode.OFF -> stringResource(R.string.orb_lock_mode_off)
                },
                enabled = allowed,
                onClick = { showModePicker = true },
            )
            if (lockMode == OrbLockDisplayMode.ON_WAKE) {
                Text(
                    text = stringResource(R.string.orb_lock_mode_on_wake_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.orb_lock_power_saving_title),
                subtitle = stringResource(R.string.orb_lock_power_saving_subtitle),
                enabled = allowed,
                trailing = {
                    Switch(
                        checked = powerSaving,
                        enabled = allowed,
                        onCheckedChange = ::togglePowerSaving,
                    )
                },
                onClick = { togglePowerSaving(!powerSaving) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.orb_lock_hide_status_bar_title),
                subtitle = stringResource(R.string.orb_lock_hide_status_bar_subtitle),
                enabled = allowed,
                trailing = {
                    Switch(
                        checked = hideStatusBar && allowed,
                        enabled = allowed,
                        onCheckedChange = ::toggleStatusBar,
                    )
                },
                onClick = { toggleStatusBar(!hideStatusBar) },
            )
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.orb_lockscreen_brightness_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (autoBrightness) stringResource(R.string.orb_lock_brightness_auto)
                            else "${(brightness * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.orb_lockscreen_brightness_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.orb_lock_brightness_auto))
                    Switch(
                        checked = autoBrightness,
                        onCheckedChange = ::toggleAutoBrightness,
                        enabled = allowed,
                    )
                }
                if (!autoBrightness) {
                    Slider(
                        value = brightness,
                        onValueChange = AppSettings::setOrbLockscreenBrightness,
                        valueRange = 0.05f..1f,
                        enabled = allowed,
                    )
                }
            }
        }
    }
    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text(stringResource(R.string.orb_lockscreen_always_on_title)) },
            text = {
                Column {
                    listOf(
                        OrbLockDisplayMode.ALWAYS to R.string.orb_lock_mode_always,
                        OrbLockDisplayMode.ON_WAKE to R.string.orb_lock_mode_notifications,
                        OrbLockDisplayMode.OFF to R.string.orb_lock_mode_off,
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                AppSettings.setOrbLockscreenMode(mode)
                                feedback()
                                showModePicker = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lockMode == mode, onClick = {
                                AppSettings.setOrbLockscreenMode(mode)
                                feedback()
                                showModePicker = false
                            })
                            Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(
                        stringResource(R.string.orb_lockscreen_always_on_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
