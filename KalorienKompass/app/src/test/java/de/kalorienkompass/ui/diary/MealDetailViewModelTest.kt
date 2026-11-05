package de.kalorienkompass.ui.diary

import android.app.Application
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.repo.DiaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MealDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var diary: DiaryRepository

    private val today = LocalDate.of(2026, 11, 5)
    private val yesterday = today.minusDays(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = inMemoryDatabase(dispatcher)
        diary = DiaryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private class Fixture(val vm: MealDetailViewModel, val events: MutableList<MealDetailEvent>)

    private fun TestScope.createViewModel(meal: MealType = MealType.BREAKFAST, date: LocalDate = today): Fixture {
        val vm = MealDetailViewModel(date, meal, diary, flowOf(TEST_TARGETS))
        val events = mutableListOf<MealDetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return Fixture(vm, events)
    }

    @Test
    fun state_containsOnlyThisMeal_withMealTargets() = runTest(dispatcher) {
        diary.addEntry(testEntry(today, MealType.BREAKFAST, "Müsli", kcal = 350.0, carbs = 50.0, protein = 10.0, fat = 9.0))
        diary.addEntry(testEntry(today, MealType.LUNCH, "Nudeln", kcal = 700.0))
        diary.addEntry(testEntry(yesterday, MealType.BREAKFAST, "Brot", kcal = 250.0))

        val f = createViewModel()
        advanceUntilIdle()
        val state = f.vm.uiState.value
        assertEquals(listOf("Müsli"), state.entries.map { it.name })
        assertEquals(350.0, state.total.kcal, 1e-9)
        assertEquals(600, state.targetKcal)
        assertEquals(250.0 * 0.3, state.carbsGoal, 1e-9)
        assertEquals(1, state.previousDayCount)
        assertEquals(250.0, state.previousDayKcal, 1e-9)
    }

    @Test
    fun delete_thenUndo_restoresTheSameEntry() = runTest(dispatcher) {
        val id = diary.addEntry(testEntry(today, MealType.BREAKFAST, "Haferflocken", kcal = 300.0, createdAt = 5_000L))
        diary.addEntry(testEntry(today, MealType.BREAKFAST, "Milch", kcal = 130.0, createdAt = 6_000L))
        val f = createViewModel()
        advanceUntilIdle()

        val entry = f.vm.uiState.value.entries.first { it.id == id }
        f.vm.delete(entry)
        advanceUntilIdle()
        assertEquals(listOf("Milch"), f.vm.uiState.value.entries.map { it.name })
        val deleted = f.events.filterIsInstance<MealDetailEvent.Deleted>().single()
        assertEquals(id, deleted.entry.id)

        f.vm.undoDelete(deleted.entry)
        advanceUntilIdle()
        // same id and original position
        assertEquals(listOf(id), f.vm.uiState.value.entries.take(1).map { it.id })
        assertEquals(listOf("Haferflocken", "Milch"), f.vm.uiState.value.entries.map { it.name })

        // a second undo must not duplicate the entry
        f.vm.undoDelete(deleted.entry)
        advanceUntilIdle()
        assertEquals(2, f.vm.uiState.value.entries.size)
    }

    @Test
    fun copyFromPreviousDay_copiesTheMeal_andUndoRemovesOnlyTheCopies() = runTest(dispatcher) {
        diary.addEntry(testEntry(yesterday, MealType.BREAKFAST, "Müsli", kcal = 350.0, createdAt = 1_000L))
        diary.addEntry(testEntry(yesterday, MealType.BREAKFAST, "Kaffee", kcal = 30.0, createdAt = 2_000L))
        diary.addEntry(testEntry(yesterday, MealType.LUNCH, "Suppe", kcal = 200.0, createdAt = 3_000L))
        diary.addEntry(testEntry(today, MealType.BREAKFAST, "Banane", kcal = 100.0, createdAt = 4_000L))
        val f = createViewModel()
        advanceUntilIdle()
        assertEquals(2, f.vm.uiState.value.previousDayCount)
        assertEquals(380.0, f.vm.uiState.value.previousDayKcal, 1e-9)

        f.vm.copyFromPreviousDay()
        advanceUntilIdle()
        assertEquals(listOf("Banane", "Müsli", "Kaffee"), f.vm.uiState.value.entries.map { it.name })
        assertEquals(480.0, f.vm.uiState.value.total.kcal, 1e-9)
        val copied = f.events.filterIsInstance<MealDetailEvent.Copied>().single()
        assertEquals(2, copied.count)
        assertEquals(2, copied.entryIds.size)

        f.vm.undoCopy(copied.entryIds)
        advanceUntilIdle()
        assertEquals(listOf("Banane"), f.vm.uiState.value.entries.map { it.name })
        // the previous day is untouched
        assertEquals(3, diary.observeDay(yesterday).first().size)
    }

    @Test
    fun copyFromPreviousDay_withoutEntries_showsMessage() = runTest(dispatcher) {
        val f = createViewModel(meal = MealType.DINNER)
        advanceUntilIdle()
        f.vm.copyFromPreviousDay()
        advanceUntilIdle()
        assertTrue(f.events.single() is MealDetailEvent.Message)
        assertTrue(f.vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun updateEntry_scalesNutrition_andMovesToAnotherMeal() = runTest(dispatcher) {
        val id = diary.addEntry(
            testEntry(
                today, MealType.BREAKFAST, "Haferflocken", kcal = 186.0, amount = 50.0,
                protein = 6.7, carbs = 29.0, fat = 3.5,
            ),
        )
        val f = createViewModel()
        advanceUntilIdle()

        f.vm.updateEntry(f.vm.uiState.value.entries.single(), newAmount = 100.0, newMeal = MealType.BREAKFAST)
        advanceUntilIdle()
        val doubled = f.vm.uiState.value.entries.single()
        assertEquals(100.0, doubled.amount, 1e-9)
        assertEquals(100.0, doubled.grams, 1e-9)
        assertEquals(372.0, doubled.kcal, 1e-9)
        assertEquals(58.0, doubled.carbs, 1e-9)
        assertTrue(f.events.isEmpty())

        f.vm.updateEntry(doubled, newAmount = 100.0, newMeal = MealType.LUNCH)
        advanceUntilIdle()
        assertTrue(f.vm.uiState.value.entries.isEmpty())
        val moved = requireNotNull(diary.getEntry(id))
        assertEquals(MealType.LUNCH, moved.meal)
        assertEquals(372.0, moved.kcal, 1e-9)
        assertEquals(MealDetailEvent.Moved("Haferflocken", MealType.LUNCH), f.events.single())
    }

    @Test
    fun updateQuickEntry_changesCaloriesOnly() = runTest(dispatcher) {
        val id = diary.addEntry(
            testEntry(
                today, MealType.SNACK, "Kuchen", kcal = 300.0, amount = 1.0, unitLabel = "", grams = 0.0,
                carbs = 30.0, source = EntrySource.QUICK,
            ),
        )
        val f = createViewModel(meal = MealType.SNACK)
        advanceUntilIdle()

        f.vm.updateQuickEntry(f.vm.uiState.value.entries.single(), newKcal = 420.0, newMeal = MealType.SNACK)
        advanceUntilIdle()
        val updated = requireNotNull(diary.getEntry(id))
        assertEquals(420.0, updated.kcal, 1e-9)
        assertEquals(30.0, updated.carbs, 1e-9)
        assertEquals(1.0, updated.amount, 1e-9)
    }
}
