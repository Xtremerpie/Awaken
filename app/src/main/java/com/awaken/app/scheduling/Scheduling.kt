package com.awaken.app.scheduling

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.awaken.app.AppLog
import com.awaken.app.AwakenApp
import com.awaken.app.MainActivity
import com.awaken.app.data.db.ScheduleEntity
import com.awaken.app.data.repo.ScheduleRepository
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.data.settings.DndBehavior
import com.awaken.app.data.settings.SettingsStore
import com.awaken.app.playback.PlaybackService
import com.awaken.core.FireDecision
import com.awaken.core.ScheduleKind
import com.awaken.core.TriggerGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Owns every AlarmManager registration. One PendingIntent per schedule (identified by a data URI), so
 * re-registering always *replaces* — it can never create a second alarm for the same schedule.
 */
class AlarmScheduler(
    private val context: Context,
    private val schedules: ScheduleRepository,
    private val settings: SettingsStore,
) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val lock = Mutex()

    companion object {
        const val ACTION_ALARM = "com.awaken.app.ALARM"
        const val EXTRA_ID = "schedule_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val EXTRA_SNOOZE = "is_snooze"
        private const val SNOOZE_OFFSET = 1_000_000
    }

    /** False when Android has not granted exact alarms: schedules then fire approximately, not on the minute. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun pending(id: Long, triggerAt: Long, snooze: Boolean, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? {
        val i = Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM)
            .setData(Uri.parse("awaken://${if (snooze) "snooze" else "schedule"}/$id"))
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_TRIGGER_AT, triggerAt).putExtra(EXTRA_SNOOZE, snooze)
        val code = if (snooze) id.toInt() + SNOOZE_OFFSET else id.toInt()
        return PendingIntent.getBroadcast(context, code, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    suspend fun rescheduleAll() = lock.withLock {
        val cfg = settings.current()
        val all = schedules.getAll()
        for (s in all) {
            if (!cfg.masterEnabled || !s.enabled) cancel(s.id) else scheduleNext(s, cfg)
        }
        AppLog.i("Rescheduled ${all.count { it.enabled }} schedule(s); exact=${canScheduleExact()}")
    }

    /** Registers the next regular trigger after [after], or removes the alarm if there is none. */
    fun scheduleNext(s: ScheduleEntity, cfg: AppSettings, after: ZonedDateTime = ZonedDateTime.now()) {
        val next = ScheduleRepository.nextTrigger(s, cfg.quietHours, after)
        if (next == null) { cancel(s.id); return }
        arm(s.id, next.toInstant().toEpochMilli(), snooze = false, alarmClock = s.scheduleKind == ScheduleKind.ALARM_CLOCK)
    }

    suspend fun scheduleSnooze(id: Long, minutes: Int) {
        val at = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        arm(id, at, snooze = true, alarmClock = true)
        AppLog.i("Snoozed schedule $id for $minutes min")
    }

    fun cancel(id: Long) {
        pending(id, 0, false, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    private fun arm(id: Long, triggerAt: Long, snooze: Boolean, alarmClock: Boolean) {
        val pi = pending(id, triggerAt, snooze) ?: return
        try {
            if (canScheduleExact()) {
                if (alarmClock) {
                    val show = PendingIntent.getActivity(
                        context, 7, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                // Honest fallback: Android will deliver this close to, but not exactly at, the time.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            AppLog.e("Exact alarm denied; using inexact for schedule $id", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_ALARM) return
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1)
        val triggerAt = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0)
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
        if (id < 0) return
        val container = (context.applicationContext as AwakenApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                handle(context, container, id, triggerAt, isSnooze)
            } catch (e: Exception) {
                AppLog.e("Alarm handling failed for schedule $id", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, c: com.awaken.app.di.AppContainer, id: Long, triggerAt: Long, isSnooze: Boolean) {
        val cfg = c.settings.current()
        val schedule = c.schedules.get(id)
        if (schedule == null) { AppLog.w("Alarm for missing schedule $id"); return }

        // Chain first: whatever happens below, the next regular trigger must exist.
        if (!isSnooze) {
            val base = maxOf(System.currentTimeMillis(), triggerAt)
            c.alarmScheduler.scheduleNext(schedule, cfg, ZonedDateTime.ofInstant(Instant.ofEpochMilli(base), ZoneId.systemDefault()))
        }
        if (!cfg.masterEnabled || !schedule.enabled) return

        val isAlarmClock = schedule.scheduleKind == ScheduleKind.ALARM_CLOCK
        val now = System.currentTimeMillis()
        val maxLate = if (isAlarmClock || isSnooze) 30 * 60_000L else TriggerGate.DEFAULT_MAX_LATE_MS
        val decision = TriggerGate.decide(triggerAt, now, if (isSnooze) null else schedule.lastFiredTriggerAt.takeIf { it > 0 }, maxLate)
        val name = schedule.name

        when (decision) {
            FireDecision.DUPLICATE -> { AppLog.w("Duplicate trigger ignored for '$name' at $triggerAt"); return }
            FireDecision.TOO_LATE -> {
                c.history.log(id, name, "", "SKIPPED", "Missed: the device was off, asleep or delayed past the grace period")
                return
            }
            FireDecision.FIRE -> Unit
        }

        if (schedule.respectQuietHours && !isSnooze && cfg.quietHours.contains(LocalTime.now())) {
            c.history.log(id, name, "", "SKIPPED", "Quiet hours")
            return
        }
        if (!isAlarmClock && cfg.dnd == DndBehavior.SKIP_WHEN_DND && isDoNotDisturb(context)) {
            c.history.log(id, name, "", "SKIPPED", "Do Not Disturb is on")
            return
        }

        c.schedules.markFired(id, triggerAt, schedule.lastPlaylistIndex)
        try {
            PlaybackService.startSchedule(context, id, triggerAt, isSnooze)
        } catch (e: Exception) {
            // e.g. the OS refused to start a foreground service from the background.
            AppLog.e("Could not start playback for '$name'", e)
            c.history.log(id, name, "", "FAILED", "Android refused to start playback: ${e.message}")
            com.awaken.app.notifications.Notifications.postFailure(context, "'$name' could not start. Check battery and notification settings in AWAKEN.")
        }
    }

    private fun isDoNotDisturb(context: Context): Boolean {
        val f = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
}

/** Re-arms every schedule after reboot, app update, clock change, timezone change or exact-alarm permission change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as AwakenApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                AppLog.i("Restoring schedules after ${intent.action}")
                container.firstRun.ensureSeeded()
                container.alarmScheduler.rescheduleAll()
            } catch (e: Exception) {
                AppLog.e("Schedule restore failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
