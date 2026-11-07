package com.awaken.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Random

/** ALARM_CLOCK fires once a day at [ScheduleSpec.start]. RECURRING fires every N minutes inside a daily window. */
enum class ScheduleKind { ALARM_CLOCK, RECURRING }

/** How a playlist advances when a schedule fires. */
enum class OrderMode { SEQUENTIAL, RANDOM }

/** Global quiet hours. The window may wrap past midnight (e.g. 22:00 → 07:00). */
data class QuietHours(
    val enabled: Boolean,
    val start: LocalTime = LocalTime.of(22, 0),
    val end: LocalTime = LocalTime.of(7, 0),
) {
    fun contains(t: LocalTime): Boolean {
        if (!enabled || start == end) return false
        return if (start < end) t >= start && t < end else t >= start || t < end
    }

    companion object {
        val OFF = QuietHours(enabled = false)
    }
}

/**
 * The scheduling-relevant part of a schedule. Pure data so it can be unit-tested without Android.
 * An empty [days] set means "every day". [end] == null (or == [start]) means "until 23:59:59".
 */
data class ScheduleSpec(
    val id: Long,
    val kind: ScheduleKind,
    val enabled: Boolean = true,
    val start: LocalTime,
    val end: LocalTime? = null,
    val intervalMinutes: Int = 60,
    val days: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val respectQuietHours: Boolean = kind == ScheduleKind.RECURRING,
)

object ScheduleEngine {
    const val MAX_INTERVAL_MINUTES = 24 * 60

    /** Human-readable validation errors; empty list means the spec is usable. */
    fun validate(spec: ScheduleSpec): List<String> {
        val errors = mutableListOf<String>()
        if (spec.kind == ScheduleKind.RECURRING && spec.intervalMinutes !in 1..MAX_INTERVAL_MINUTES) {
            errors += "Interval must be between 1 minute and 24 hours."
        }
        val s = spec.startDate
        val e = spec.endDate
        if (s != null && e != null && e.isBefore(s)) errors += "End date is before start date."
        return errors
    }

    /**
     * The first trigger strictly after [after], or null if there is none within [horizonDays]
     * (disabled, invalid, expired date range, or no allowed weekday).
     *
     * Weekdays are evaluated on the day a window *starts*, so a 22:00→02:00 window that begins on
     * Friday continues into Saturday morning. Date range is evaluated on the trigger's own local date.
     */
    fun nextTrigger(
        spec: ScheduleSpec,
        after: ZonedDateTime,
        quiet: QuietHours = QuietHours.OFF,
        horizonDays: Int = 400,
    ): ZonedDateTime? {
        if (!spec.enabled || validate(spec).isNotEmpty()) return null
        val zone = after.zone
        val firstDay = after.toLocalDate().minusDays(1) // a wrapping window from yesterday may still be running

        for (offset in 0..horizonDays + 1) {
            val day = firstDay.plusDays(offset.toLong())
            if (spec.days.isNotEmpty() && day.dayOfWeek !in spec.days) continue

            if (spec.kind == ScheduleKind.ALARM_CLOCK) {
                val t = day.atTime(spec.start).atZone(zone)
                if (t.isAfter(after) && accepts(spec, t, quiet)) return t
                continue
            }

            val windowStart = day.atTime(spec.start).atZone(zone)
            val windowEnd = windowEnd(spec, day, zone)
            val stepMs = Duration.ofMinutes(spec.intervalMinutes.toLong()).toMillis()
            var k = if (after.isBefore(windowStart)) {
                0L
            } else {
                Math.floorDiv(after.toInstant().toEpochMilli() - windowStart.toInstant().toEpochMilli(), stepMs) + 1
            }
            while (true) {
                val t = windowStart.plusMinutes(k * spec.intervalMinutes)
                if (t.isAfter(windowEnd)) break
                if (t.isAfter(after) && accepts(spec, t, quiet)) return t
                k++
            }
        }
        return null
    }

    private fun windowEnd(spec: ScheduleSpec, day: LocalDate, zone: java.time.ZoneId): ZonedDateTime {
        val end = spec.end
        return if (end == null || end == spec.start) {
            day.atTime(23, 59, 59).atZone(zone)
        } else if (end.isAfter(spec.start)) {
            day.atTime(end).atZone(zone)
        } else {
            day.plusDays(1).atTime(end).atZone(zone)
        }
    }

    private fun accepts(spec: ScheduleSpec, t: ZonedDateTime, quiet: QuietHours): Boolean {
        val d = t.toLocalDate()
        if (spec.startDate != null && d.isBefore(spec.startDate)) return false
        if (spec.endDate != null && d.isAfter(spec.endDate)) return false
        if (spec.respectQuietHours && quiet.contains(t.toLocalTime())) return false
        return true
    }
}

enum class FireDecision { FIRE, DUPLICATE, TOO_LATE }

/**
 * Duplicate-playback guard. AlarmManager can deliver the same trigger twice (reboot restore racing a
 * pending alarm, app update, manual reschedule). A trigger is identified by its scheduled time.
 */
object TriggerGate {
    const val DEFAULT_MAX_LATE_MS = 10 * 60 * 1000L

    fun decide(
        triggerAtMs: Long,
        nowMs: Long,
        lastFiredTriggerMs: Long?,
        maxLateMs: Long = DEFAULT_MAX_LATE_MS,
    ): FireDecision {
        if (lastFiredTriggerMs != null && lastFiredTriggerMs >= triggerAtMs) return FireDecision.DUPLICATE
        if (nowMs - triggerAtMs > maxLateMs) return FireDecision.TOO_LATE
        return FireDecision.FIRE
    }
}

object PlaybackOrder {
    /** Next playlist index. Random never repeats the current item when there is more than one. */
    fun nextIndex(mode: OrderMode, size: Int, current: Int?, random: Random = Random()): Int {
        require(size > 0) { "empty playlist" }
        if (size == 1) return 0
        return when (mode) {
            OrderMode.SEQUENTIAL -> if (current == null || current < 0) 0 else (current + 1) % size
            OrderMode.RANDOM -> {
                var i = random.nextInt(size)
                if (current != null && i == current) i = (i + 1 + random.nextInt(size - 1)) % size
                i
            }
        }
    }
}

object PlaybackPlanner {
    /** Total time a trigger should play: loops × media length, capped by [durationSec] (0 = no cap). */
    fun totalDurationMs(mediaMs: Long, loops: Int, durationSec: Int): Long {
        val natural = mediaMs.coerceAtLeast(0) * loops.coerceAtLeast(1)
        return if (durationSec > 0) minOf(natural, durationSec * 1000L) else natural
    }
}

object VolumeRamp {
    private const val GRADUAL_START = 0.15f

    /** Multiplier (0..1) applied on top of the user's volume. */
    fun gain(elapsedMs: Long, totalMs: Long, fadeInMs: Long, fadeOutMs: Long, gradualMs: Long): Float {
        var g = 1f
        if (fadeInMs > 0 && elapsedMs < fadeInMs) g = minOf(g, elapsedMs.coerceAtLeast(0) / fadeInMs.toFloat())
        if (fadeOutMs > 0 && totalMs > 0) {
            val remaining = totalMs - elapsedMs
            if (remaining < fadeOutMs) g = minOf(g, remaining.coerceAtLeast(0) / fadeOutMs.toFloat())
        }
        if (gradualMs > 0 && elapsedMs < gradualMs) {
            g = minOf(g, GRADUAL_START + (1 - GRADUAL_START) * (elapsedMs.coerceAtLeast(0) / gradualMs.toFloat()))
        }
        return g.coerceIn(0f, 1f)
    }
}
