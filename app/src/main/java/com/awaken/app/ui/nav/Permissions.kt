package com.awaken.app.ui.nav

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle

/** What Android currently lets AWAKEN do. Each flag maps to one real system switch the user can flip. */
data class PermissionState(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
    val fullScreenIntent: Boolean,
) {
    /** Everything needed for on-time playback with the screen off. */
    val reliable get() = notifications && exactAlarms && batteryUnrestricted
}

object Permissions {
    fun snapshot(ctx: Context): PermissionState {
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exact = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        val fsi = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        return PermissionState(notif, exact, battery, fsi)
    }

    private fun pkg(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun exactAlarmIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(ctx))
        else appDetails(ctx)

    /** Asks Android to exempt AWAKEN from battery optimisation (the user must accept in a system dialog). */
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(ctx))

    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun notificationSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun fullScreenIntentSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx)) else appDetails(ctx)

    fun appDetails(ctx: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(ctx))

    /** Starts a settings screen; falls back to app details if the device has no handler for it. */
    fun launch(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { ctx.startActivity(appDetails(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }
}

/** Live permission state that refreshes whenever the user returns from a system settings screen. */
@Composable
fun rememberPermissionState(): PermissionState {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(Permissions.snapshot(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = Permissions.snapshot(ctx) }
    return state
}
