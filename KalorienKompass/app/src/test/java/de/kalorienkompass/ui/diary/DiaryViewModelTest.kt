package de.kalorienkompass.ui.diary

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.repo.NoOpHealthDataExporter
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.health.HealthSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class DiaryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var diary: DiaryRepository
    private lateinit var measurements: MeasurementRepository
    private lateinit var activity: ActivityRepository

    private var today = LocalDate.of(2026, 11, 5)
    private val settings = MutableStateFlow(AppSettings())
    private val profile = MutableStateFlow(UserProfile(onboardingDone = true, startWeightKg = 82.0, targetWeightKg = 75.0))
    private val syncCalls = mutableListOf<Int>()
    private var syncResult = HealthSyncResult(success = true, message = "OK")
    private var sensorReads = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = inMemoryDatabase(dispatcher)
        diary = DiaryRepository(db)
        measurements = MeasurementRepository(db, NoOpHealthDataExporter)
        activity = ActivityRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(savedState: SavedStateHandle = SavedStateHandle()): DiaryViewModel {
        val vm = DiaryViewModel(
            diaryRepository = diary,
            measurementRepository = measurements,
            activityRepository = activity,
            settingsFlow = settings,
            profileFlow = profile,
            targetsFor = { flowOf(TEST_TARGETS) },
            syncHealth = { days ->
                syncCalls += days
                syncResult
            },
            readStepSensor = { sensorReads++ },
            savedStateHandle = savedState,
            clock = { today },
            nowMillis = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    @Test
    fun state_combinesEntriesTargetsWeightAndSteps() = runTest(dispatcher) {
        diary.addEntry(testEntry(today, MealType.BREAKFAST, "Haferflocken", kcal = 400.0, carbs = 60.0, protein = 12.0, fat = 8.0))
        diary.addEntry(testEntry(today, MealType.LUNCH, "Nudeln", kcal = 600.0, carbs = 90.0))
        diary.addEntry(testEntry(today.minusDays(1), MealType.LUNCH, "Gestern", kcal = 999.0))
        measurements.add(MeasurementType.WEIGHT, today.minusDays(2), 80.0)
        activity.setManualSteps(today, 10_000)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertTrue(state.isToday)
        val summary = requireNotNull(state.summary)
        assertEquals(1000.0, summary.eatenKcal, 1e-6)
        assertEquals(400.0, summary.burnedKcal, 1e-6) // 10.000 steps × 80 kg × 0,0005
        assertEquals(1400.0, summary.remainingKcal, 1e-6)
        assertEquals(150.0, summary.carbs, 1e-6)
        assertEquals(400.0, state.meals.first { it.meal == MealType.BREAKFAST }.kcal, 1e-6)
        assertEquals(0, state.meals.first { it.meal == MealType.DINNER }.entryCount)

        val weight = requireNotNull(state.weight)
        assertEquals(80.0, weight.valueKg, 1e-9)
        assertEquals(today.minusDays(2), weight.lastEntryDate)
        assertEquals(75.0, weight.targetKg, 1e-9)

        val steps = requireNotNull(state.activity)
        assertEquals(10_000L, steps.steps)
        assertEquals(DataSource.MANUAL, steps.source)
        assertFalse(steps.burnedFromHealthConnect)

        // burned calories are not added when the setting is off
        settings.value = AppSettings(addBurnedCalories = false)
        advanceUntilIdle()
        assertEquals(1000.0, requireNotNull(vm.uiState.value.summary).remainingKcal, 1e-6)
    }

    @Test
    fun weightFallsBackToStartWeight() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val weight = requireNotNull(vm.uiState.value.weight)
        assertEquals(82.0, weight.valueKg, 1e-9)
        assertNull(weight.lastEntryDate)
    }

    @Test
    fun dayNavigation_showsDataOfSelectedDay() = runTest(dispatcher) {
        diary.addEntry(testEntry(today.minusDays(1), MealType.DINNER, "Suppe", kcal = 300.0))
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(0.0, requireNotNull(vm.uiState.value.summary).eatenKcal, 1e-9)

        vm.previousDay()
        advanceUntilIdle()
        assertEquals(today.minusDays(1), vm.uiState.value.date)
        assertFalse(vm.uiState.value.isToday)
        assertEquals(300.0, requireNotNull(vm.uiState.value.summary).eatenKcal, 1e-9)

        vm.goToToday()
        vm.nextDay()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFuture)
        assertFalse(requireNotNull(vm.uiState.value.weight).editable)
        assertFalse(requireNotNull(vm.uiState.value.activity).editable)
    }

    @Test
    fun weightButtons_showImmediately_andSaveOnceAfterPause() = runTest(dispatcher) {
        measurements.add(MeasurementType.WEIGHT, today, 80.0)
        val vm = createViewModel()
        advanceUntilIdle()

        repeat(3) { vm.changeWeightBy(DiaryViewModel.WEIGHT_STEP_KG) }
        runCurrent()
        assertEquals(80.3, requireNotNull(vm.uiState.value.weight).valueKg, 1e-9)
        assertTrue(requireNotNull(vm.uiState.value.weight).hasPendingChange)

        advanceTimeBy(DiaryViewModel.WEIGHT_SAVE_DEBOUNCE_MS / 2)
        runCurrent()
        assertEquals(listOf(80.0), db.measurementDao().getAll().map { it.value })

        advanceUntilIdle()
        val saved = db.measurementDao().getAll()
        assertEquals(1, saved.size) // the MANUAL value of the day is updated, not duplicated
        assertEquals(80.3, saved.single().value, 1e-9)
        val weight = requireNotNull(vm.uiState.value.weight)
        assertFalse(weight.hasPendingChange)
        assertEquals(80.3, weight.valueKg, 1e-9)
        assertEquals(today, weight.lastEntryDate)
    }

    @Test
    fun changingTheDay_savesPendingWeightRightAway() = runTest(dispatcher) {
        measurements.add(MeasurementType.WEIGHT, today, 80.0)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.changeWeightBy(-DiaryViewModel.WEIGHT_STEP_KG)
        runCurrent()
        vm.previousDay()
        runCurrent()
        val saved = db.measurementDao().getAll().single { it.date == today }
        assertEquals(79.9, saved.value, 1e-9)

        advanceUntilIdle()
        // the previous day shows the latest weight on/before it (none) → start weight
        assertEquals(82.0, requireNotNull(vm.uiState.value.weight).valueKg, 1e-9)
    }

    @Test
    fun setWeight_validatesRange_andIgnoresFutureDays() = runTest(dispatcher) {
        val messages = mutableListOf<DiaryEvent>()
        val vm = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { messages += it } }
        advanceUntilIdle()

        vm.setWeight(5.0)
        advanceUntilIdle()
        assertTrue(db.measurementDao().getAll().isEmpty())
        assertEquals(1, messages.size)

        vm.setWeight(78.45)
        advanceUntilIdle()
        assertEquals(78.45, db.measurementDao().getAll().single().value, 1e-9)

        vm.nextDay()
        vm.setWeight(70.0)
        vm.changeWeightBy(0.1)
        advanceUntilIdle()
        assertEquals(1, db.measurementDao().getAll().size)
    }

    @Test
    fun manualSteps_areStoredForSelectedPastDay() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.previousDay()
        vm.setManualSteps(4_321)
        advanceUntilIdle()
        val stored = db.activityDao().get(today.minusDays(1))
        assertNotNull(stored)
        assertEquals(4_321L, stored!!.steps)
        assertEquals(DataSource.MANUAL, stored.source)
        assertEquals(4_321L, requireNotNull(vm.uiState.value.activity).steps)
    }

    @Test
    fun refresh_usesOnlyEnabledSources_andAutoRefreshIsRateLimited() = runTest(dispatcher) {
        settings.value = AppSettings(healthConnectEnabled = true, stepSensorEnabled = false)
        val vm = createViewModel()
        vm.onScreenStarted()
        advanceUntilIdle()
        assertEquals(listOf(DiaryViewModel.SYNC_DAYS), syncCalls)
        assertEquals(0, sensorReads)

        // automatic refresh again right away: skipped
        vm.onScreenStarted()
        advanceUntilIdle()
        assertEquals(1, syncCalls.size)

        // manual refresh always runs, here with the step sensor only
        settings.value = AppSettings(healthConnectEnabled = false, stepSensorEnabled = true)
        vm.refresh(userInitiated = true)
        advanceUntilIdle()
        assertEquals(1, syncCalls.size)
        assertEquals(1, sensorReads)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun refresh_reportsSyncErrors() = runTest(dispatcher) {
        settings.value = AppSettings(healthConnectEnabled = true)
        syncResult = HealthSyncResult(success = false, message = "Berechtigungen fehlen.")
        val messages = mutableListOf<DiaryEvent>()
        val vm = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { messages += it } }

        vm.refresh(userInitiated = true)
        advanceUntilIdle()
        assertEquals("Berechtigungen fehlen.", requireNotNull(vm.uiState.value.activity).syncError)
        assertEquals(listOf<DiaryEvent>(DiaryEvent.Message("Berechtigungen fehlen.")), messages)

        syncResult = HealthSyncResult(success = true, message = "OK")
        vm.refresh(userInitiated = true)
        advanceUntilIdle()
        assertNull(requireNotNull(vm.uiState.value.activity).syncError)
    }

    @Test
    fun noAutomaticSource_manualRefreshExplainsWhy() = runTest(dispatcher) {
        val messages = mutableListOf<DiaryEvent>()
        val vm = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { messages += it } }
        vm.onScreenStarted()
        advanceUntilIdle()
        assertTrue(messages.isEmpty())

        vm.refresh(userInitiated = true)
        advanceUntilIdle()
        assertEquals(1, messages.size)
        assertTrue(syncCalls.isEmpty())
        assertEquals(0, sensorReads)
    }

    @Test
    fun afterMidnight_todayIsFollowed_butAChosenDayIsKept() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val vm = createViewModel(savedState)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 5), vm.uiState.value.date)

        today = LocalDate.of(2026, 11, 6)
        vm.onScreenStarted()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 6), vm.uiState.value.date)
        assertTrue(vm.uiState.value.isToday)

        vm.selectDate(LocalDate.of(2026, 11, 1))
        today = LocalDate.of(2026, 11, 7)
        vm.onScreenStarted()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 1), vm.uiState.value.date)

        // the chosen day survives a re-creation (process death)
        val restored = createViewModel(savedState)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 1), restored.uiState.value.date)
    }
}
