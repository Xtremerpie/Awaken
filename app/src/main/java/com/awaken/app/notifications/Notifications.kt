package com.awaken.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.awaken.app.AppLog
import com.awaken.app.AwakenApp
import com.awaken.app.MainActivity
import com.awaken.app.R
import com.awaken.app.playback.PlaybackService
import kotlinx.coroutines.launch

object Notifications {
    const val CHANNEL_PLAYBACK = "awaken_playback"
    const val CHANNEL_ALARM = "awaken_alarm"
    const val CHANNEL_UPDATE = "awaken_update"

    /** Shared with Media3's media notification so the early foreground notification is replaced in place. */
    const val PLAYBACK_ID = 4101
    const val ALERT_ID = 4102
    const val UPDATE_ID = 4103
    const val FAILURE_ID = 4104

    const val ACTION_STOP = "com.awaken.app.notification.STOP"
    const val ACTION_SNOOZE = "com.awaken.app.notification.SNOOZE"
    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, context.getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_playback_desc); setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_alarm_desc); setSound(null, null); enableVibration(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, context.getString(R.string.channel_update), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_update_desc) }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context, request: Int): PendingIntent = PendingIntent.getActivity(
        context, request,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Shown the instant the service is started so the 5-second foreground deadline can never be missed. */
    fun playbackPlaceholder(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_awaken)
            .setContentTitle("AWAKEN")
            .setContentText(text)
            .setContentIntent(openApp(context, 1))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /** Stop / Snooze controls for a scheduled trigger. Alarm-clock triggers also get a full-screen intent. */
    fun postAlert(context: Context, scheduleId: Long, title: String, text: String, snoozeMinutes: Int, isAlarmClock: Boolean) {
        if (!canPost(context)) return
        fun action(code: Int, act: String, label: String): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                context, code,
                Intent(context, NotificationActionReceiver::class.java).setAction(act)
                    .putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }
        val b = NotificationCompat.Builder(context, if (isAlarmClock) CHANNEL_ALARM else CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_awaken)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context, 2))
            .setCategory(if (isAlarmClock) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (isAlarmClock) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(action(10, ACTION_STOP, "Stop"))
            .addAction(action(11, ACTION_SNOOZE, "Snooze $snoozeMinutes min"))
        if (isAlarmClock) b.setFullScreenIntent(openApp(context, 3), true)
        try {
            NotificationManagerCompat.from(context).notify(ALERT_ID, b.build())
        } catch (e: SecurityException) {
            AppLog.w("Alert notification blocked", e)
        }
    }

    fun cancelAlert(context: Context) = NotificationManagerCompat.from(context).cancel(ALERT_ID)

    fun postFailure(context: Context, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_awaken)
            .setContentTitle("AWAKEN couldn't play")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, 4))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(FAILURE_ID, n) } catch (_: SecurityException) { }
    }

    fun postUpdateAvailable(context: Context, versionName: String, mandatory: Boolean) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_awaken)
            .setContentTitle(if (mandatory) "AWAKEN update required" else "AWAKEN update available")
            .setContentText("Version $versionName is ready in the Update Center.")
            .setContentIntent(openApp(context, 5))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(UPDATE_ID, n) } catch (_: SecurityException) { }
    }
}

/** Handles the Stop and Snooze buttons on the alert notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as AwakenApp).container
        val id = intent.getLongExtra(Notifications.EXTRA_SCHEDULE_ID, -1)
        val minutes = intent.getIntExtra(Notifications.EXTRA_SNOOZE_MINUTES, 10)
        val snooze = intent.action == Notifications.ACTION_SNOOZE
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (snooze && id >= 0) container.alarmScheduler.scheduleSnooze(id, minutes)
                PlaybackService.requestStop(context, snoozed = snooze)
                Notifications.cancelAlert(context)
            } catch (e: Exception) {
                AppLog.e("Notification action failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
