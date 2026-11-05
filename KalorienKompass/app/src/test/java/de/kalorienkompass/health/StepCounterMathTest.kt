package de.kalorienkompass.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StepCounterMathTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 11, 5)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun state(
        date: LocalDate,
        baseline: Long,
        last: Long,
        readAt: Long,
        bootCount: Int = 7,
    ) = StepSensorState(date, baseline, last, readAt, bootCount)

    @Test
    fun firstReading_bootedBeforeToday_startsAtZero() {
        val u = StepCounterMath.update(
            previous = null,
            counter = 52_000,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 9),
            bootTimeMillis = millis(today.minusDays(3), 8),
            zone = zone,
            existingTodaySteps = null,
        )
        assertEquals(0L, u.todaySteps)
        assertEquals(52_000L, u.state.baselineCounter)
        assertEquals(today, u.state.baselineDate)
        assertNull(u.previousDate)
    }

    @Test
    fun firstReading_bootedToday_countsEverythingSinceBoot() {
        val u = StepCounterMath.update(
            previous = null,
            counter = 1_234,
            bootCount = 8,
            today = today,
            nowMillis = millis(today, 10),
            bootTimeMillis = millis(today, 6),
            zone = zone,
            existingTodaySteps = null,
        )
        assertEquals(1_234L, u.todaySteps)
        assertEquals(0L, u.state.baselineCounter)
    }

    @Test
    fun firstReading_keepsAlreadyStoredSteps() {
        val u = StepCounterMath.update(
            previous = null,
            counter = 10_000,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 12),
            bootTimeMillis = millis(yesterday, 6),
            zone = zone,
            existingTodaySteps = 3_000,
        )
        assertEquals(3_000L, u.todaySteps)
        assertEquals(7_000L, u.state.baselineCounter)
    }

    @Test
    fun sameDay_countsSinceBaseline() {
        val u = StepCounterMath.update(
            previous = state(today, baseline = 1_000, last = 1_500, readAt = millis(today, 9)),
            counter = 4_200,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 12),
            bootTimeMillis = millis(yesterday, 6),
            zone = zone,
            existingTodaySteps = 500,
        )
        assertEquals(3_200L, u.todaySteps)
        assertEquals(1_000L, u.state.baselineCounter)
        assertEquals(4_200L, u.state.lastCounter)
        assertNull(u.previousDate)
    }

    @Test
    fun sameDay_rebootKeepsStepsCountedBefore() {
        // 500 steps before the reboot, 300 after it
        val u = StepCounterMath.update(
            previous = state(today, baseline = 1_000, last = 1_500, readAt = millis(today, 9), bootCount = 7),
            counter = 300,
            bootCount = 8,
            today = today,
            nowMillis = millis(today, 12),
            bootTimeMillis = millis(today, 11),
            zone = zone,
            existingTodaySteps = 500,
        )
        assertEquals(800L, u.todaySteps)
        // the next reading on the same boot continues correctly
        val next = StepCounterMath.update(
            previous = u.state,
            counter = 1_300,
            bootCount = 8,
            today = today,
            nowMillis = millis(today, 15),
            bootTimeMillis = millis(today, 11),
            zone = zone,
            existingTodaySteps = 800,
        )
        assertEquals(1_800L, next.todaySteps)
    }

    @Test
    fun sameDay_counterDecreaseWithoutBootCountIsTreatedAsReboot() {
        val u = StepCounterMath.update(
            previous = state(today, baseline = 0, last = 2_000, readAt = millis(today, 9), bootCount = -1),
            counter = 100,
            bootCount = -1,
            today = today,
            nowMillis = millis(today, 12),
            bootTimeMillis = millis(today, 11),
            zone = zone,
            existingTodaySteps = 2_000,
        )
        assertEquals(2_100L, u.todaySteps)
    }

    @Test
    fun newDay_splitsStepsSinceLastReadingByTime() {
        // last reading yesterday 22:00, now 08:00 → 2 of 10 hours belong to yesterday
        val u = StepCounterMath.update(
            previous = state(yesterday, baseline = 10_000, last = 18_000, readAt = millis(yesterday, 22)),
            counter = 19_000,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 8),
            bootTimeMillis = millis(yesterday.minusDays(2), 8),
            zone = zone,
            existingTodaySteps = null,
        )
        assertEquals(800L, u.todaySteps)
        assertEquals(yesterday, u.previousDate)
        assertEquals(8_000L + 200L, u.previousDateSteps)
        assertEquals(today, u.state.baselineDate)
        assertEquals(19_000L - 800L, u.state.baselineCounter)

        // later the same day only today's steps grow
        val later = StepCounterMath.update(
            previous = u.state,
            counter = 21_000,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 12),
            bootTimeMillis = millis(yesterday.minusDays(2), 8),
            zone = zone,
            existingTodaySteps = 800,
        )
        assertEquals(2_800L, later.todaySteps)
        assertNull(later.previousDate)
    }

    @Test
    fun newDay_afterReboot_usesStepsSinceBoot() {
        // reboot at 06:00 today, 600 steps since then
        val u = StepCounterMath.update(
            previous = state(yesterday, baseline = 10_000, last = 18_000, readAt = millis(yesterday, 22), bootCount = 7),
            counter = 600,
            bootCount = 8,
            today = today,
            nowMillis = millis(today, 9),
            bootTimeMillis = millis(today, 6),
            zone = zone,
            existingTodaySteps = null,
        )
        assertEquals(600L, u.todaySteps)
        assertEquals(yesterday, u.previousDate)
        assertEquals(8_000L, u.previousDateSteps)
        assertEquals(0L, u.state.baselineCounter)
    }

    @Test
    fun longGap_restartsInsteadOfGuessing() {
        val u = StepCounterMath.update(
            previous = state(today.minusDays(5), baseline = 0, last = 5_000, readAt = millis(today.minusDays(5), 20)),
            counter = 60_000,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 9),
            bootTimeMillis = millis(today.minusDays(10), 8),
            zone = zone,
            existingTodaySteps = null,
        )
        assertEquals(0L, u.todaySteps)
        assertEquals(60_000L, u.state.baselineCounter)
        assertNull(u.previousDate)
    }

    @Test
    fun dateMovedBackwards_restarts() {
        val u = StepCounterMath.update(
            previous = state(today.plusDays(1), baseline = 0, last = 5_000, readAt = millis(today.plusDays(1), 8)),
            counter = 5_500,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 9),
            bootTimeMillis = millis(yesterday, 8),
            zone = zone,
            existingTodaySteps = 1_000,
        )
        assertEquals(1_000L, u.todaySteps)
        assertEquals(4_500L, u.state.baselineCounter)
    }

    @Test
    fun stepsAreCappedAndNeverNegative() {
        val u = StepCounterMath.update(
            previous = state(today, baseline = 10_000, last = 10_000, readAt = millis(today, 9)),
            counter = 10_000 + 5_000_000,
            bootCount = 7,
            today = today,
            nowMillis = millis(today, 12),
            bootTimeMillis = millis(yesterday, 8),
            zone = zone,
            existingTodaySteps = null,
        )
        assertEquals(StepCounterMath.MAX_DAILY_STEPS, u.todaySteps)
    }
}
