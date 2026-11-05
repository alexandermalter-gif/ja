package de.kalorienkompass.ui.diary

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DiaryCalculationsTest {

    private val day = LocalDate.of(2026, 11, 5)

    @Test
    fun burnedKcal_prefersActiveCaloriesFromHealthConnect() {
        val hc = DailyActivityEntity(date = day, steps = 8_000, activeKcal = 310.0, source = DataSource.HEALTH_CONNECT)
        assertEquals(310.0, burnedKcal(hc, 80.0), 1e-9)
        assertTrue(usesActiveCalories(hc))
    }

    @Test
    fun burnedKcal_estimatesFromStepsOtherwise() {
        val manual = DailyActivityEntity(date = day, steps = 10_000, activeKcal = null, source = DataSource.MANUAL)
        assertEquals(400.0, burnedKcal(manual, 80.0), 1e-9) // 10.000 × 80 kg × 0,0005
        // 0 kcal from Health Connect means "no data": fall back to the steps
        val zero = manual.copy(activeKcal = 0.0, source = DataSource.HEALTH_CONNECT)
        assertEquals(400.0, burnedKcal(zero, 80.0), 1e-9)
        assertFalse(usesActiveCalories(zero))
        assertEquals(0.0, burnedKcal(null, 80.0), 1e-9)
    }

    @Test
    fun daySummary_remainingIncludesBurnedOnlyWhenEnabled() {
        val entries = listOf(
            testEntry(day, MealType.BREAKFAST, "Müsli", kcal = 450.0, carbs = 60.0, protein = 15.0, fat = 12.0),
            testEntry(day, MealType.LUNCH, "Nudeln", kcal = 750.0, carbs = 110.0, protein = 25.0, fat = 18.0),
        )
        val with = daySummary(entries, TEST_TARGETS, burned = 300.0, addBurned = true)
        assertEquals(1200.0, with.eatenKcal, 1e-9)
        assertEquals(2300.0, with.budgetKcal, 1e-9)
        assertEquals(1100.0, with.remainingKcal, 1e-9)
        assertEquals(170.0, with.carbs, 1e-9)
        assertEquals(40.0, with.protein, 1e-9)
        assertEquals(30.0, with.fat, 1e-9)
        assertEquals(250, with.carbsGoal)
        assertFalse(with.isOver)
        assertEquals(1200f / 2300f, with.ringProgress, 1e-6f)

        val without = daySummary(entries, TEST_TARGETS, burned = 300.0, addBurned = false)
        assertEquals(800.0, without.remainingKcal, 1e-9)
    }

    @Test
    fun daySummary_overBudget() {
        val entries = listOf(testEntry(day, MealType.DINNER, "Pizza", kcal = 2600.0))
        val s = daySummary(entries, TEST_TARGETS, burned = 0.0, addBurned = true)
        assertTrue(s.isOver)
        assertEquals(-600.0, s.remainingKcal, 1e-9)
        assertEquals(1f, s.ringProgress, 0f)
        // a rounding residue is not "zu viel"
        val almost = daySummary(listOf(testEntry(day, MealType.DINNER, "X", kcal = 2000.4)), TEST_TARGETS, 0.0, true)
        assertFalse(almost.isOver)
    }

    @Test
    fun mealSummaries_alwaysFourMealsInOrder() {
        val entries = listOf(
            testEntry(day, MealType.SNACK, "Apfel", kcal = 80.0),
            testEntry(day, MealType.BREAKFAST, "Brot", kcal = 200.0),
            testEntry(day, MealType.BREAKFAST, "Butter", kcal = 70.0),
            testEntry(day, MealType.BREAKFAST, "Brot", kcal = 200.0),
        )
        val meals = mealSummaries(entries, TEST_TARGETS)
        assertEquals(MealType.entries.toList(), meals.map { it.meal })
        val breakfast = meals.first { it.meal == MealType.BREAKFAST }
        assertEquals(470.0, breakfast.kcal, 1e-9)
        assertEquals(600, breakfast.targetKcal)
        assertEquals(3, breakfast.entryCount)
        assertEquals("Brot, Butter", breakfast.preview)
        val lunch = meals.first { it.meal == MealType.LUNCH }
        assertEquals(0, lunch.entryCount)
        assertEquals("", lunch.preview)
        assertEquals(0f, lunch.progress, 0f)
        val snack = meals.first { it.meal == MealType.SNACK }
        assertFalse(snack.isOver) // 80 of 100 kcal
    }

    @Test
    fun mealShare_isFractionOfDailyCalories() {
        assertEquals(0.3, mealShare(TEST_TARGETS, MealType.BREAKFAST), 1e-9)
        assertEquals(0.05, mealShare(TEST_TARGETS, MealType.SNACK), 1e-9)
        assertEquals(0.0, mealShare(TEST_TARGETS.copy(calories = 0), MealType.LUNCH), 1e-9)
    }

    @Test
    fun latestOnOrBefore_usesDateThenCreatedAt() {
        val list = listOf(
            weight(day.minusDays(3), 81.0, createdAt = 1),
            weight(day, 80.2, createdAt = 5, source = DataSource.HEALTH_CONNECT),
            weight(day, 80.0, createdAt = 9),
            weight(day.plusDays(1), 79.5, createdAt = 10),
        )
        assertEquals(80.0, list.latestOnOrBefore(day)?.value)
        assertEquals(81.0, list.latestOnOrBefore(day.minusDays(1))?.value)
        assertNull(list.latestOnOrBefore(day.minusDays(4)))
    }

    @Test
    fun adjustWeight_roundsToTenthAndClamps() {
        assertEquals(80.1, adjustWeight(80.0, 0.1), 0.0)
        assertEquals(80.3, adjustWeight(adjustWeight(adjustWeight(80.0, 0.1), 0.1), 0.1), 0.0)
        assertEquals(79.9, adjustWeight(80.0, -0.1), 0.0)
        assertEquals(80.5, adjustWeight(80.36, 0.1), 0.0)
        assertEquals(MIN_WEIGHT_KG, adjustWeight(MIN_WEIGHT_KG, -0.1), 0.0)
        assertEquals(MAX_WEIGHT_KG, adjustWeight(MAX_WEIGHT_KG, 0.1), 0.0)
        assertTrue(isPlausibleWeight(72.5))
        assertFalse(isPlausibleWeight(0.0))
        assertFalse(isPlausibleWeight(800.0))
    }

    @Test
    fun formatSignedKg_usesRealMinusSign() {
        assertEquals("+0,4", formatSignedKg(0.4))
        assertEquals("−2,1", formatSignedKg(-2.1))
        assertEquals("±0,0", formatSignedKg(0.01))
    }

    @Test
    fun weightGoal_textAndProgress() {
        // losing: 90 → 80
        assertEquals("Noch 5,4 kg bis zum Ziel", weightGoalText(85.4, 90.0, 80.0))
        assertEquals("Zielgewicht erreicht 🎉", weightGoalText(79.6, 90.0, 80.0))
        assertEquals(0.46f, weightGoalProgress(85.4, 90.0, 80.0)!!, 1e-4f)
        assertEquals(1f, weightGoalProgress(78.0, 90.0, 80.0)!!, 0f)
        assertEquals(0f, weightGoalProgress(91.0, 90.0, 80.0)!!, 0f)
        // gaining: 60 → 65
        assertEquals("Noch 2,0 kg bis zum Ziel", weightGoalText(63.0, 60.0, 65.0))
        assertEquals(0.6f, weightGoalProgress(63.0, 60.0, 65.0)!!, 1e-4f)
        // maintaining: start = target
        assertEquals("0,4 kg über dem Ziel", weightGoalText(75.4, 75.0, 75.0))
        assertEquals("0,6 kg unter dem Ziel", weightGoalText(74.4, 75.0, 75.0))
        assertEquals("Zielgewicht erreicht 🎉", weightGoalText(75.0, 75.0, 75.0))
        assertNull(weightGoalProgress(75.4, 75.0, 75.0))
    }

    @Test
    fun amountLabel_coversUnitsPortionsRecipesAndQuickEntries() {
        assertEquals("150 g", amountLabel(testEntry(day, MealType.LUNCH, "Reis", 200.0, amount = 150.0)))
        assertEquals("330 ml", amountLabel(testEntry(day, MealType.LUNCH, "Cola", 139.0, amount = 330.0, unitLabel = "ml")))
        assertEquals(
            "2 × Scheibe (50 g)",
            amountLabel(testEntry(day, MealType.BREAKFAST, "Brot", 240.0, amount = 2.0, unitLabel = "Scheibe (50 g)", grams = 100.0)),
        )
        assertEquals(
            "1,5 × mittelgroßer Apfel",
            amountLabel(testEntry(day, MealType.SNACK, "Apfel", 117.0, amount = 1.5, unitLabel = "1 mittelgroßer Apfel", grams = 225.0)),
        )
        assertEquals(
            "2 × Portion · 700 g",
            amountLabel(
                testEntry(
                    day, MealType.DINNER, "Lasagne", 1100.0, amount = 2.0, unitLabel = "Portion", grams = 700.0,
                    source = EntrySource.RECIPE,
                ),
            ),
        )
        assertEquals(
            "Schnelleintrag",
            amountLabel(testEntry(day, MealType.SNACK, "Kuchen", 350.0, amount = 1.0, unitLabel = "", grams = 0.0, source = EntrySource.QUICK)),
        )
    }

    @Test
    fun scaledEntry_scalesGramsAndNutrition() {
        val e = testEntry(day, MealType.BREAKFAST, "Haferflocken", kcal = 186.0, amount = 50.0, protein = 6.75, carbs = 29.0, fat = 3.5)
        val s = scaledEntry(e, 75.0)
        assertEquals(75.0, s.amount, 1e-9)
        assertEquals(75.0, s.grams, 1e-9)
        assertEquals(279.0, s.kcal, 1e-9)
        assertEquals(10.125, s.protein, 1e-9)
        assertEquals(43.5, s.carbs, 1e-9)
        assertEquals(5.25, s.fat, 1e-9)
        // amount 0: only the amount changes
        val zero = e.copy(amount = 0.0)
        assertEquals(186.0, scaledEntry(zero, 10.0).kcal, 1e-9)
    }

    @Test
    fun quickAndWeightUnitDetection() {
        assertTrue(testEntry(day, MealType.SNACK, "X", 1.0, unitLabel = "", source = EntrySource.QUICK).isQuickEntry())
        assertFalse(testEntry(day, MealType.SNACK, "X", 1.0).isQuickEntry())
        assertTrue(testEntry(day, MealType.SNACK, "X", 1.0, unitLabel = "ml").isWeightUnit())
        assertFalse(testEntry(day, MealType.SNACK, "X", 1.0, unitLabel = "Portion").isWeightUnit())
    }

    @Test
    fun pickerMillis_roundTrip() {
        assertEquals(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), day.toPickerMillis())
        assertEquals(day, pickerMillisToDate(day.toPickerMillis()))
        assertEquals(day, pickerMillisToDate(day.toPickerMillis() + 23 * 3_600_000L))
        val old = LocalDate.of(1965, 3, 1)
        assertEquals(old, pickerMillisToDate(old.toPickerMillis()))
    }

    @Test
    fun labels() {
        assertEquals("Donnerstag, 5. November", fullDateLabel(day, day))
        assertEquals("Mittwoch, 31. Dezember 2025", fullDateLabel(LocalDate.of(2025, 12, 31), day))
        assertEquals("1 Eintrag", entryCountLabel(1))
        assertEquals("3 Einträge", entryCountLabel(3))
        assertEquals(0.5f, stepProgress(5_000, 10_000), 0f)
        assertEquals(1f, stepProgress(15_000, 10_000), 0f)
        assertEquals(0f, stepProgress(100, 0), 0f)
    }

    @Test
    fun lastSyncLabel_relativeDays() {
        val zone = ZoneOffset.UTC
        val todayAt = day.atTime(14, 5).toInstant(zone).toEpochMilli()
        val yesterdayAt = day.minusDays(1).atTime(9, 30).toInstant(zone).toEpochMilli()
        assertEquals("heute, 14:05", lastSyncLabel(todayAt, day, zone))
        assertEquals("gestern, 09:30", lastSyncLabel(yesterdayAt, day, zone))
    }

    private fun weight(
        date: LocalDate,
        kg: Double,
        createdAt: Long,
        source: DataSource = DataSource.MANUAL,
    ) = MeasurementEntity(type = MeasurementType.WEIGHT, date = date, value = kg, source = source, createdAt = createdAt)
}
