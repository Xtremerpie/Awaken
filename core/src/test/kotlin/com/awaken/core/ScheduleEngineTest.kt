package com.awaken.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random

class ScheduleEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)

    /** The schedule created on first install: every 60 minutes, all day, every day. */
    private val hourly = ScheduleSpec(1, ScheduleKind.RECURRING, start = LocalTime.MIDNIGHT, intervalMinutes = 60)

    // 2026-11-06 is a Friday.
    @Test fun firstInstallHourlyFiresOnTheNextHour() {
        assertEquals(at(2026, 11, 6, 22, 0), ScheduleEngine.nextTrigger(hourly, at(2026, 11, 6, 21, 10)))
    }

    @Test fun triggerIsStrictlyAfterNow() {
        assertEquals(at(2026, 11, 6, 22, 0), ScheduleEngine.nextTrigger(hourly, at(2026, 11, 6, 21, 0)))
    }

    @Test fun hourlyRollsToMidnight() {
        // 23:00 is the last slot of the day because the window ends at 23:59:59.
        assertEquals(at(2026, 11, 7, 0, 0), ScheduleEngine.nextTrigger(hourly, at(2026, 11, 6, 23, 30)))
    }

    @Test fun customInterval45MinutesAnchorsToStart() {
        val s = hourly.copy(start = LocalTime.of(8, 0), end = LocalTime.of(10, 0), intervalMinutes = 45)
        assertEquals(at(2026, 11, 6, 8, 45), ScheduleEngine.nextTrigger(s, at(2026, 11, 6, 8, 10)))
        assertEquals(at(2026, 11, 6, 9, 30), ScheduleEngine.nextTrigger(s, at(2026, 11, 6, 8, 45)))
        // 10:15 would be past the window end, so the next one is tomorrow's 08:00.
        assertEquals(at(2026, 11, 7, 8, 0), ScheduleEngine.nextTrigger(s, at(2026, 11, 6, 9, 30)))
    }

    @Test fun every15And30Minutes() {
        val q = hourly.copy(intervalMinutes = 15)
        assertEquals(at(2026, 11, 6, 21, 15), ScheduleEngine.nextTrigger(q, at(2026, 11, 6, 21, 1)))
        val h = hourly.copy(intervalMinutes = 30)
        assertEquals(at(2026, 11, 6, 21, 30), ScheduleEngine.nextTrigger(h, at(2026, 11, 6, 21, 1)))
    }

    @Test fun weekdaysOnly() {
        val s = hourly.copy(start = LocalTime.of(9, 0), end = LocalTime.of(17, 0),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        // Friday 18:00 → next is Monday 09:00.
        assertEquals(at(2026, 11, 9, 9, 0), ScheduleEngine.nextTrigger(s, at(2026, 11, 6, 18, 0)))
    }

    @Test fun quietHoursSkipTriggersAndResumeAfterwards() {
        val quiet = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0))
        // 22:00..06:00 fall inside quiet hours, so the next trigger is 07:00 the following morning.
        assertEquals(at(2026, 11, 7, 7, 0), ScheduleEngine.nextTrigger(hourly, at(2026, 11, 6, 21, 30), quiet))
        assertEquals(at(2026, 11, 6, 21, 0), ScheduleEngine.nextTrigger(hourly, at(2026, 11, 6, 20, 30), quiet))
        assertNotNull(ScheduleEngine.nextTrigger(hourly, at(2026, 11, 6, 21, 30), quiet))
        assertFalse(quiet.contains(LocalTime.of(21, 59)))
        assertTrue(quiet.contains(LocalTime.of(22, 0)))
        assertTrue(quiet.contains(LocalTime.of(3, 0)))
        assertFalse(quiet.contains(LocalTime.of(7, 0)))
    }

    @Test fun alarmClockIgnoresQuietHoursByDefault() {
        val alarm = ScheduleSpec(2, ScheduleKind.ALARM_CLOCK, start = LocalTime.of(6, 30))
        val quiet = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0))
        assertEquals(at(2026, 11, 7, 6, 30), ScheduleEngine.nextTrigger(alarm, at(2026, 11, 6, 21, 0), quiet))
    }

    @Test fun alarmClockTodayIfStillAhead() {
        val alarm = ScheduleSpec(2, ScheduleKind.ALARM_CLOCK, start = LocalTime.of(23, 0))
        assertEquals(at(2026, 11, 6, 23, 0), ScheduleEngine.nextTrigger(alarm, at(2026, 11, 6, 21, 0)))
        assertEquals(at(2026, 11, 7, 23, 0), ScheduleEngine.nextTrigger(alarm, at(2026, 11, 6, 23, 0)))
    }

    @Test fun alarmClockSpecificWeekday() {
        val alarm = ScheduleSpec(2, ScheduleKind.ALARM_CLOCK, start = LocalTime.of(7, 0), days = setOf(DayOfWeek.SUNDAY))
        assertEquals(at(2026, 11, 8, 7, 0), ScheduleEngine.nextTrigger(alarm, at(2026, 11, 6, 21, 0)))
    }

    @Test fun overnightWindowContinuesPastMidnightOnTheStartDay() {
        val s = hourly.copy(start = LocalTime.of(22, 0), end = LocalTime.of(2, 0), days = setOf(DayOfWeek.FRIDAY))
        // Friday window runs into Saturday 02:00 even though Saturday is not selected.
        assertEquals(at(2026, 11, 7, 1, 0), ScheduleEngine.nextTrigger(s, at(2026, 11, 7, 0, 30)))
        assertEquals(at(2026, 11, 13, 22, 0), ScheduleEngine.nextTrigger(s, at(2026, 11, 7, 2, 0)))
    }

    @Test fun dateRangeIsHonoured() {
        val s = hourly.copy(startDate = LocalDate.of(2026, 11, 10), endDate = LocalDate.of(2026, 11, 10))
        assertEquals(at(2026, 11, 10, 0, 0), ScheduleEngine.nextTrigger(s, at(2026, 11, 6, 21, 0)))
        assertEquals(at(2026, 11, 10, 23, 0), ScheduleEngine.nextTrigger(s, at(2026, 11, 10, 22, 30)))
        assertNull(ScheduleEngine.nextTrigger(s, at(2026, 11, 10, 23, 30)))
    }

    @Test fun disabledOrInvalidNeverFires() {
        assertNull(ScheduleEngine.nextTrigger(hourly.copy(enabled = false), at(2026, 11, 6, 21, 0)))
        assertNull(ScheduleEngine.nextTrigger(hourly.copy(intervalMinutes = 0), at(2026, 11, 6, 21, 0)))
        assertFalse(ScheduleEngine.validate(hourly.copy(intervalMinutes = 5000)).isEmpty())
        assertFalse(ScheduleEngine.validate(hourly.copy(startDate = LocalDate.of(2026, 2, 2), endDate = LocalDate.of(2026, 1, 1))).isEmpty())
    }

    @Test fun dstSpringForwardDoesNotSkipOrRepeat() {
        val ny = ZoneId.of("America/New_York")
        val s = hourly
        // 2026-03-08 02:00 does not exist in New York.
        var t = ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, ny)
        val seen = mutableListOf<ZonedDateTime>()
        repeat(4) { t = ScheduleEngine.nextTrigger(s, t)!!; seen += t }
        assertTrue(seen.zipWithNext().all { (a, b) -> a.isBefore(b) })
    }

    @Test fun triggerGatePreventsDuplicatesAndStaleFires() {
        val trig = 1_000_000L
        assertEquals(FireDecision.FIRE, TriggerGate.decide(trig, trig + 500, null))
        assertEquals(FireDecision.DUPLICATE, TriggerGate.decide(trig, trig + 500, trig))
        assertEquals(FireDecision.DUPLICATE, TriggerGate.decide(trig, trig + 500, trig + 10))
        assertEquals(FireDecision.TOO_LATE, TriggerGate.decide(trig, trig + TriggerGate.DEFAULT_MAX_LATE_MS + 1, trig - 1))
        assertEquals(FireDecision.FIRE, TriggerGate.decide(trig + 3_600_000, trig + 3_600_500, trig))
    }

    @Test fun playbackOrder() {
        assertEquals(0, PlaybackOrder.nextIndex(OrderMode.SEQUENTIAL, 3, null))
        assertEquals(2, PlaybackOrder.nextIndex(OrderMode.SEQUENTIAL, 3, 1))
        assertEquals(0, PlaybackOrder.nextIndex(OrderMode.SEQUENTIAL, 3, 2))
        assertEquals(0, PlaybackOrder.nextIndex(OrderMode.RANDOM, 1, 0))
        val r = Random(7)
        repeat(500) { assertTrue(PlaybackOrder.nextIndex(OrderMode.RANDOM, 4, 2, r) != 2) }
    }

    @Test fun playbackPlanAndRamp() {
        assertEquals(30_000L, PlaybackPlanner.totalDurationMs(10_000, 3, 0))
        assertEquals(20_000L, PlaybackPlanner.totalDurationMs(10_000, 3, 20))
        assertEquals(10_000L, PlaybackPlanner.totalDurationMs(10_000, 0, 0))
        assertEquals(0f, VolumeRamp.gain(0, 10_000, 2_000, 0, 0), 0.001f)
        assertEquals(0.5f, VolumeRamp.gain(1_000, 10_000, 2_000, 0, 0), 0.001f)
        assertEquals(1f, VolumeRamp.gain(5_000, 10_000, 2_000, 2_000, 0), 0.001f)
        assertEquals(0.5f, VolumeRamp.gain(9_000, 10_000, 2_000, 2_000, 0), 0.001f)
        assertEquals(0.15f, VolumeRamp.gain(0, 100_000, 0, 0, 30_000), 0.001f)
        assertEquals(1f, VolumeRamp.gain(30_000, 100_000, 0, 0, 30_000), 0.001f)
    }
}
