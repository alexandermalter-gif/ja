package de.kalorienkompass.ui.progress

import de.kalorienkompass.ui.components.charts.BarSpacing
import de.kalorienkompass.ui.components.charts.ChartHitTester
import de.kalorienkompass.ui.components.charts.ValueAxis
import de.kalorienkompass.ui.components.charts.axisDateLabel
import de.kalorienkompass.ui.components.charts.barAxis
import de.kalorienkompass.ui.components.charts.decimalsForStep
import de.kalorienkompass.ui.components.charts.defaultBarLabel
import de.kalorienkompass.ui.components.charts.defaultBarTooltipTitle
import de.kalorienkompass.ui.components.charts.inferSpacing
import de.kalorienkompass.ui.components.charts.lineAxis
import de.kalorienkompass.ui.components.charts.niceAxis
import de.kalorienkompass.ui.components.charts.sparseLabelIndices
import de.kalorienkompass.ui.components.charts.ticksWithin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pure helpers of the Canvas charts (ui/components/charts). */
class ChartAxisTest {

    @Test
    fun decimalsForStep_matchesStepPrecision() {
        assertEquals(0, decimalsForStep(5.0))
        assertEquals(0, decimalsForStep(1000.0))
        assertEquals(1, decimalsForStep(0.5))
        assertEquals(1, decimalsForStep(2.5))
        assertEquals(2, decimalsForStep(0.25))
    }

    @Test
    fun niceAxis_startsAtZeroWithNiceSteps() {
        val axis = niceAxis(0.0, 2650.0)
        assertEquals(0.0, axis.min, 0.0)
        assertEquals(3000.0, axis.max, 1e-9)
        assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0), axis.ticks)
        assertEquals("2.000", axis.label(2000.0))
    }

    @Test
    fun niceAxis_degenerateInput() {
        val flat = niceAxis(0.0, 0.0)
        assertTrue(flat.max > flat.min)
        val nan = niceAxis(Double.NaN, Double.NaN)
        assertTrue(nan.max > nan.min)
    }

    @Test
    fun barAxis_includesTheGoalAndHasHeadroom() {
        val axis = barAxis(listOf(1200.0, 1500.0), goal = 2400.0)
        assertEquals(0.0, axis.min, 0.0)
        assertTrue(axis.max >= 2400.0)
        assertTrue(axis.ticks.size in 3..5)

        val steps = barAxis(listOf(4_000.0, 12_345.0), goal = 10_000.0)
        assertTrue(steps.max >= 12_345.0)
        assertTrue(steps.ticks.size in 3..5)

        val empty = barAxis(emptyList(), null)
        assertTrue(empty.max > 0.0)
        assertTrue(empty.ticks.size >= 2)
    }

    @Test
    fun ticksWithin_niceValuesInsideTheDomain() {
        val axis = ticksWithin(81.6, 86.3)
        assertEquals(81.6, axis.min, 0.0)
        assertEquals(86.3, axis.max, 0.0)
        assertEquals(listOf(82.0, 84.0, 86.0), axis.ticks)
        assertEquals("84", axis.label(84.0))

        val small = ticksWithin(0.9, 1.6)
        assertTrue(small.ticks.size in 2..4)
        assertTrue(small.ticks.all { it in 0.9..1.6 })
    }

    @Test
    fun lineAxis_flatDataGetsAReadableWindow() {
        val axis = lineAxis(listOf(83.4, 83.4), goal = null).axis
        assertTrue(axis.min < 83.4 && axis.max > 83.4)
        assertTrue(axis.max - axis.min >= 1.0)
        assertTrue(axis.ticks.size >= 2)
    }

    @Test
    fun lineAxis_goalNearTheDataIsIncluded() {
        val result = lineAxis(listOf(90.0, 85.0, 82.0), goal = 78.0)
        assertTrue(result.goalInside)
        assertTrue(result.axis.min <= 78.0)
        assertTrue(result.axis.max >= 90.0)
    }

    @Test
    fun lineAxis_farGoalDoesNotSqueezeTheLine() {
        val result = lineAxis(listOf(90.0, 89.6), goal = 70.0)
        assertFalse(result.goalInside)
        assertTrue(result.axis.min > 85.0)
    }

    @Test
    fun lineAxis_withoutValuesCentersOnTheGoal() {
        val result = lineAxis(emptyList(), goal = 75.0)
        assertTrue(result.axis.min < 75.0 && result.axis.max > 75.0)
        assertTrue(result.goalInside)
    }

    @Test
    fun lineAxis_neverGoesBelowZeroForPositiveData() {
        val axis = lineAxis(listOf(0.2, 3.0), goal = null).axis
        assertTrue(axis.min >= 0.0)
    }

    @Test
    fun valueAxis_fraction() {
        val axis = ValueAxis(0.0, 100.0, listOf(0.0, 50.0, 100.0), 0)
        assertEquals(0.25f, axis.fraction(25.0), 1e-6f)
        assertEquals(0.5f, ValueAxis(5.0, 5.0, listOf(5.0), 0).fraction(5.0), 1e-6f)
    }

    @Test
    fun sparseLabelIndices_includeTheLastBarAndRespectTheMaximum() {
        assertEquals((0 until 7).toList(), sparseLabelIndices(7))
        val month = sparseLabelIndices(30)
        assertEquals(29, month.last())
        assertTrue(month.size <= 7)
        assertEquals(month.sorted(), month)
        val year = sparseLabelIndices(53)
        assertEquals(52, year.last())
        assertTrue(year.size <= 7)
        assertTrue(sparseLabelIndices(0).isEmpty())
    }

    @Test
    fun inferSpacing_fromDates() {
        val d = LocalDate.of(2026, 11, 2)
        assertEquals(BarSpacing.DAILY, inferSpacing(listOf(d, d.plusDays(1), d.plusDays(2))))
        assertEquals(BarSpacing.WEEKLY, inferSpacing(listOf(d, d.plusWeeks(1), d.plusWeeks(2))))
        assertEquals(BarSpacing.MONTHLY, inferSpacing(listOf(d, d.plusMonths(1), d.plusMonths(2))))
        assertEquals(BarSpacing.DAILY, inferSpacing(listOf(d)))
    }

    @Test
    fun defaultBarLabels() {
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals("Mo.", defaultBarLabel(monday, BarSpacing.DAILY, 7))
        assertEquals("02.11.", defaultBarLabel(monday, BarSpacing.DAILY, 30))
        assertEquals("Nov.", defaultBarLabel(monday, BarSpacing.MONTHLY, 12))
        assertEquals("Woche ab 02.11.", defaultBarTooltipTitle(monday, BarSpacing.WEEKLY))
        assertEquals("November 2026", defaultBarTooltipTitle(monday, BarSpacing.MONTHLY))
    }

    @Test
    fun axisDateLabel_shortOrMonthYear() {
        assertEquals("03.11.", axisDateLabel(LocalDate.of(2026, 11, 3), 30))
        assertEquals("Nov. ’26", axisDateLabel(LocalDate.of(2026, 11, 3), 400))
        assertEquals("Jan. ’05", axisDateLabel(LocalDate.of(2005, 1, 3), 400))
    }

    @Test
    fun hitTester_findsTheNearestPoint() {
        val tester = ChartHitTester()
        assertNull(tester.nearest(10f))
        tester.xs = floatArrayOf(0f, 50f, 100f)
        assertEquals(1, tester.nearest(60f))
        assertEquals(2, tester.nearest(1000f))
        assertEquals(0, tester.nearest(-5f))
    }
}
