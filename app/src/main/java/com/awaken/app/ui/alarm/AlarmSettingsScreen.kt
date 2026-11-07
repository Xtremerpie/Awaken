package com.awaken.app.ui.alarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.data.settings.DndBehavior
import com.awaken.app.playback.PlaybackService
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.components.StatusPill
import com.awaken.app.ui.components.SwitchRow
import com.awaken.app.ui.nav.LocalContainer
import com.awaken.app.ui.nav.Permissions
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.nav.rememberPermissionState
import com.awaken.app.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@Composable
fun AlarmSettingsScreen(onBack: () -> Unit, onNewAlarm: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms = rememberPermissionState()
    val p = LocalPalette.current
    val cfg by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    ScreenColumn("Alarm settings", onBack) {
        GoldButton("Create an alarm clock", onNewAlarm, Modifier.fillMaxWidth(), Icons.Rounded.Alarm)

        SectionLabel("Background reliability")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (perms.reliable) "All reliability switches are on. AWAKEN has the best chance Android allows." else "Some switches are off. Playback may be late, approximate or blocked while the screen is off.",
                    style = MaterialTheme.typography.bodyMedium, color = if (perms.reliable) p.primary else MaterialTheme.colorScheme.error,
                )
                PermRow("Notifications", "Playback controls, stop/snooze buttons, failure alerts", perms.notifications) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else Permissions.launch(ctx, Permissions.notificationSettings(ctx))
                }
                PermRow("Exact alarms", "Needed to fire on the minute. Without it Android delivers approximately.", perms.exactAlarms) {
                    Permissions.launch(ctx, Permissions.exactAlarmIntent(ctx))
                }
                PermRow("Unrestricted battery", "Stops battery optimisation from pausing AWAKEN", perms.batteryUnrestricted) {
                    Permissions.launch(ctx, Permissions.batteryIntent(ctx))
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    PermRow("Full-screen alarm", "Lets an alarm clock open over the lock screen", perms.fullScreenIntent) {
                        Permissions.launch(ctx, Permissions.fullScreenIntentSettings(ctx))
                    }
                }
                GhostButton("Open battery settings", { Permissions.launch(ctx, Permissions.batterySettingsIntent()) }, Modifier.fillMaxWidth())
            }
        }

        SectionLabel("Phone-maker restrictions")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Some manufacturers (Xiaomi, Oppo, Vivo, Realme, Huawei, OnePlus, Samsung and others) add their own background limits on top of Android's. If playback is missing while the screen is off:", style = MaterialTheme.typography.bodyMedium)
                Text("•  Turn on Autostart / Allow background activity for AWAKEN.", style = MaterialTheme.typography.bodySmall)
                Text("•  Lock AWAKEN in the recent-apps list, and remove it from any 'sleeping apps' or 'battery saver' list.", style = MaterialTheme.typography.bodySmall)
                Text("•  Don't force-stop AWAKEN: Android then blocks every alarm until you open the app again.", style = MaterialTheme.typography.bodySmall)
                GhostButton("Step-by-step guide for your phone", {
                    Permissions.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/")))
                }, Modifier.fillMaxWidth())
            }
        }

        SectionLabel("Alarm behaviour")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Default snooze (minutes)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ scope.launch { container.settings.setDefaultSnoozeMinutes(cfg.defaultSnoozeMinutes - 1) } }) { Icon(Icons.Rounded.Remove, "Less") }
                    Text("${cfg.defaultSnoozeMinutes}", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
                    IconButton({ scope.launch { container.settings.setDefaultSnoozeMinutes(cfg.defaultSnoozeMinutes + 1) } }) { Icon(Icons.Rounded.Add, "More") }
                }
                SwitchRow("Vibrate when a schedule fires", null, cfg.vibration, { scope.launch { container.settings.setVibration(it) } })
                SwitchRow("Gradual volume", "Rise from quiet over ${cfg.gradualSeconds} seconds", cfg.gradualVolume, { scope.launch { container.settings.setGradualVolume(it) } })
                Text("Do Not Disturb", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(cfg.dnd == DndBehavior.SKIP_WHEN_DND, { scope.launch { container.settings.setDnd(DndBehavior.SKIP_WHEN_DND) } }, { Text("Respect it") })
                    FilterChip(cfg.dnd == DndBehavior.PLAY_ANYWAY, { scope.launch { container.settings.setDnd(DndBehavior.PLAY_ANYWAY) } }, { Text("Play anyway") })
                }
                Text("Alarm-clock schedules use the alarm audio stream, which Android lets through Do Not Disturb when you allow alarms there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GhostButton("Test playback now", {
                    scope.launch { container.media.defaultMedia()?.let { PlaybackService.playMedia(ctx, listOf(it.id)) } }
                }, Modifier.fillMaxWidth(), Icons.Rounded.PlayArrow)
            }
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String, granted: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) StatusPill("ON", LocalPalette.current.primary) else GhostButton("Fix", onFix)
    }
}
