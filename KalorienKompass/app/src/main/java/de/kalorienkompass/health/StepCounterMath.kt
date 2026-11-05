package de.kalorienkompass.health

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Persisted state of the phone's step counter (TYPE_STEP_COUNTER counts steps since the last reboot).
 *
 * Steps of [baselineDate] = counter − [baselineCounter]. The baseline may become negative after a
 * reboot during the day (so that the steps counted before the reboot are kept).
 */
internal data class StepSensorState(
    val baselineDate: LocalDate,
    val baselineCounter: Long,
    val lastCounter: Long,
    val lastReadAtMillis: Long,
    /** Settings.Global.BOOT_COUNT at the last reading, −1 if unknown. */
    val bootCount: Int,
)

internal data class StepSensorUpdate(
    val state: StepSensorState,
    val todaySteps: Long,
    /** Day of the previous reading that receives its share of the steps walked since then (date change). */
    val previousDate: LocalDate? = null,
    val previousDateSteps: Long? = null,
)

/** Pure step-counter bookkeeping (unit-tested). */
internal object StepCounterMath {

    /** Longer gaps between two readings are not split between days (unreliable); counting restarts. */
    const val MAX_SPLIT_GAP_MILLIS: Long = 48L * 60 * 60 * 1000
    const val MAX_DAILY_STEPS: Long = 200_000L

    /**
     * @param previous persisted state or null on the first reading
     * @param counter current sensor value (steps since boot)
     * @param bootCount current boot count (−1 if unknown)
     * @param bootTimeMillis wall-clock time of the last boot (now − elapsedRealtime)
     * @param existingTodaySteps steps already stored for [today] by the sensor (null if none)
     */
    fun update(
        previous: StepSensorState?,
        counter: Long,
        bootCount: Int,
        today: LocalDate,
        nowMillis: Long,
        bootTimeMillis: Long,
        zone: ZoneId,
        existingTodaySteps: Long?,
    ): StepSensorUpdate {
        val current = counter.coerceAtLeast(0L)
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()

        fun stateFor(todaySteps: Long) = StepSensorState(
            baselineDate = today,
            baselineCounter = current - todaySteps,
            lastCounter = current,
            lastReadAtMillis = nowMillis,
            bootCount = bootCount,
        )

        /** No usable history: continue from the stored value; if the phone booted today, all counted steps are today's. */
        fun restart(): StepSensorUpdate {
            val sinceBootIsToday = bootTimeMillis >= startOfToday && bootTimeMillis <= nowMillis
            val steps = maxOf(existingTodaySteps ?: 0L, if (sinceBootIsToday) current else 0L)
                .coerceIn(0L, MAX_DAILY_STEPS)
            return StepSensorUpdate(state = stateFor(steps), todaySteps = steps)
        }

        if (previous == null) return restart()

        val rebooted = (bootCount >= 0 && previous.bootCount >= 0 && bootCount != previous.bootCount) ||
            current < previous.lastCounter

        return when {
            previous.baselineDate == today -> {
                // Same day. After a reboot the counter restarts at 0: keep the steps counted before.
                val baseline = if (rebooted) {
                    previous.baselineCounter - previous.lastCounter
                } else {
                    previous.baselineCounter
                }
                val steps = (current - baseline).coerceIn(0L, MAX_DAILY_STEPS)
                StepSensorUpdate(state = stateFor(steps), todaySteps = steps)
            }

            previous.baselineDate.isBefore(today) -> {
                // New day: the steps since the last reading are split by time between the previous
                // reading's day and today (we cannot know when exactly they were walked).
                val gapStart = if (rebooted) maxOf(previous.lastReadAtMillis, bootTimeMillis) else previous.lastReadAtMillis
                val gapLength = nowMillis - gapStart
                if (gapLength <= 0L || nowMillis - previous.lastReadAtMillis > MAX_SPLIT_GAP_MILLIS) {
                    return restart()
                }
                val delta = if (rebooted) current else current - previous.lastCounter
                val todayShare = ((nowMillis - maxOf(gapStart, startOfToday)).toDouble() / gapLength).coerceIn(0.0, 1.0)
                val todaySteps = maxOf((delta * todayShare).roundToLong(), existingTodaySteps ?: 0L)
                    .coerceIn(0L, MAX_DAILY_STEPS)

                val prevStart = previous.baselineDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val prevEnd = previous.baselineDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val overlap = (minOf(nowMillis, prevEnd) - maxOf(gapStart, prevStart)).coerceAtLeast(0L)
                val prevShare = (overlap.toDouble() / gapLength).coerceIn(0.0, 1.0)
                val countedBefore = (previous.lastCounter - previous.baselineCounter).coerceAtLeast(0L)
                val prevSteps = (countedBefore + (delta * prevShare).roundToLong()).coerceIn(0L, MAX_DAILY_STEPS)

                StepSensorUpdate(
                    state = stateFor(todaySteps),
                    todaySteps = todaySteps,
                    previousDate = previous.baselineDate,
                    previousDateSteps = prevSteps,
                )
            }

            // The date went backwards (clock or time zone changed): start over.
            else -> restart()
        }
    }
}
