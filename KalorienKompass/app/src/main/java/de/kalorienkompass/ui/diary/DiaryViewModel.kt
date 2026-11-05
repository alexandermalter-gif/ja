package de.kalorienkompass.ui.diary

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.di.appContainerFrom
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.health.HealthSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** UI state of the diary home screen ("Tagebuch"). */
data class DiaryUiState(
    /** selected day */
    val date: LocalDate,
    val today: LocalDate,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val summary: DaySummary? = null,
    val meals: List<MealSummary> = emptyList(),
    val weight: WeightCardState? = null,
    val activity: ActivityCardState? = null,
    /** pull-to-refresh indicator (only for refreshes started by the user) */
    val isRefreshing: Boolean = false,
) {
    val isToday: Boolean get() = date == today

    /** Weight and steps cannot be entered for future days. */
    val isFuture: Boolean get() = date.isAfter(today)
}

data class WeightCardState(
    /** shown value: unsaved +/- change, else latest weight on/before the day, else the profile's start weight */
    val valueKg: Double,
    /** date of the latest saved weight on/before the day (null: no weight saved yet) */
    val lastEntryDate: LocalDate?,
    val targetKg: Double,
    val startKg: Double,
    /** true while a change waits to be saved */
    val hasPendingChange: Boolean,
    val editable: Boolean,
)

data class ActivityCardState(
    val steps: Long,
    val stepGoal: Int,
    val source: DataSource?,
    val burnedKcal: Double,
    /** true if [burnedKcal] are the active calories reported by Health Connect (else estimated from steps) */
    val burnedFromHealthConnect: Boolean,
    val healthConnectEnabled: Boolean,
    val stepSensorEnabled: Boolean,
    val lastSyncAt: Long?,
    val isSyncing: Boolean,
    val syncError: String?,
    val editable: Boolean,
) {
    val hasAutomaticSource: Boolean get() = healthConnectEnabled || stepSensorEnabled
}

sealed interface DiaryEvent {
    data class Message(val text: String) : DiaryEvent
}

/**
 * Diary home: selected day, calorie overview, meals, weight (+/- with debounced saving) and steps.
 *
 * Dependencies that are not plain repositories are passed as flows/functions so that the
 * ViewModel can be tested with an in-memory database (see DiaryViewModelTest); [Factory] wires
 * the real implementations from the AppContainer.
 */
class DiaryViewModel(
    private val diaryRepository: DiaryRepository,
    private val measurementRepository: MeasurementRepository,
    private val activityRepository: ActivityRepository,
    private val settingsFlow: Flow<AppSettings>,
    private val profileFlow: Flow<UserProfile>,
    private val targetsFor: (LocalDate) -> Flow<DailyTargets>,
    private val syncHealth: suspend (days: Int) -> HealthSyncResult,
    private val readStepSensor: suspend () -> Unit,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val clock: () -> LocalDate = { LocalDate.now() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private data class PendingWeight(val date: LocalDate, val kg: Double)

    private data class SyncStatus(
        val isSyncing: Boolean = false,
        val userInitiated: Boolean = false,
        val error: String? = null,
    )

    private data class DayData(
        val date: LocalDate,
        val entries: List<DiaryEntryEntity>,
        val targets: DailyTargets,
        val activity: DailyActivityEntity?,
        val latestWeight: MeasurementEntity?,
    )

    private sealed interface DayLoad {
        data class Ready(val data: DayData) : DayLoad
        data class Failed(val message: String) : DayLoad
    }

    private val today = MutableStateFlow(clock())
    private val selectedDate = MutableStateFlow(restoreSelectedDate())
    private val reloadTrigger = MutableStateFlow(0)
    private val pendingWeight = MutableStateFlow<PendingWeight?>(null)
    private val syncStatus = MutableStateFlow(SyncStatus())

    private val _events = Channel<DiaryEvent>(Channel.BUFFERED)

    /** One-off messages for the snackbar. */
    val events: Flow<DiaryEvent> = _events.receiveAsFlow()

    /** +/- change that has not been written yet (debounce running). */
    private var unsavedWeight: PendingWeight? = null
    private var weightJob: Job? = null
    private var syncJob: Job? = null
    private var lastRefreshAt: Long? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dayLoad: Flow<DayLoad> = selectedDate
        .flatMapLatest { date ->
            combine(
                diaryRepository.observeDay(date),
                targetsFor(date),
                activityRepository.observeDay(date),
                measurementRepository.observe(MeasurementType.WEIGHT),
            ) { entries, targets, activity, weights ->
                val load: DayLoad = DayLoad.Ready(
                    DayData(
                        date = date,
                        entries = entries,
                        targets = targets,
                        activity = activity,
                        latestWeight = weights.latestOnOrBefore(date),
                    ),
                )
                load
            }.catch { e ->
                Log.e(TAG, "Loading diary day $date failed", e)
                emit(DayLoad.Failed("Die Daten für diesen Tag konnten nicht geladen werden."))
            }
        }

    /** [retry] restarts the whole pipeline (also after errors of the settings flows). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DiaryUiState> = reloadTrigger.flatMapLatest {
        combine(
            dayLoad,
            settingsFlow,
            profileFlow,
            pendingWeight,
            combine(syncStatus, selectedDate, today) { sync, date, todayDate -> Triple(sync, date, todayDate) },
        ) { load, settings, profile, pending, (sync, date, todayDate) ->
            buildState(load, settings, profile, pending, sync, date, todayDate)
        }.catch { e ->
            Log.e(TAG, "Diary state failed", e)
            emit(
                DiaryUiState(
                    date = selectedDate.value,
                    today = today.value,
                    isLoading = false,
                    errorMessage = "Die Daten konnten nicht geladen werden.",
                ),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DiaryUiState(date = selectedDate.value, today = today.value),
    )

    private fun restoreSelectedDate(): LocalDate {
        val followsToday = savedStateHandle.get<Boolean>(KEY_FOLLOWS_TODAY) ?: true
        val saved = savedStateHandle.get<Long>(KEY_DATE)
        return if (followsToday || saved == null) clock() else LocalDate.ofEpochDay(saved)
    }

    private fun buildState(
        load: DayLoad,
        settings: AppSettings,
        profile: UserProfile,
        pending: PendingWeight?,
        sync: SyncStatus,
        date: LocalDate,
        todayDate: LocalDate,
    ): DiaryUiState {
        val base = DiaryUiState(
            date = date,
            today = todayDate,
            isLoading = false,
            isRefreshing = sync.isSyncing && sync.userInitiated,
        )
        val data = when (load) {
            is DayLoad.Failed -> return base.copy(errorMessage = load.message)
            is DayLoad.Ready -> load.data
        }
        // Same fallback as TargetsUseCase: latest weight on/before the day, else the start weight.
        val weightKg = data.latestWeight?.value ?: profile.startWeightKg
        val burned = burnedKcal(data.activity, weightKg)
        val editable = !date.isAfter(todayDate)
        val pendingKg = pending?.takeIf { it.date == date }?.kg
        return base.copy(
            summary = daySummary(data.entries, data.targets, burned, settings.addBurnedCalories),
            meals = mealSummaries(data.entries, data.targets),
            weight = WeightCardState(
                valueKg = pendingKg ?: weightKg,
                lastEntryDate = data.latestWeight?.date,
                targetKg = profile.targetWeightKg,
                startKg = profile.startWeightKg,
                hasPendingChange = pendingKg != null,
                editable = editable,
            ),
            activity = ActivityCardState(
                steps = data.activity?.steps ?: 0L,
                stepGoal = settings.stepGoal,
                source = data.activity?.source,
                burnedKcal = burned,
                burnedFromHealthConnect = usesActiveCalories(data.activity),
                healthConnectEnabled = settings.healthConnectEnabled,
                stepSensorEnabled = settings.stepSensorEnabled,
                lastSyncAt = settings.lastHealthSyncAt,
                isSyncing = sync.isSyncing,
                syncError = sync.error,
                editable = editable,
            ),
        )
    }

    // ---- day navigation ----

    fun selectDate(date: LocalDate) {
        val todayDate = clock()
        today.value = todayDate
        applyDate(date, followsToday = date == todayDate)
    }

    fun previousDay() = selectDate(selectedDate.value.minusDays(1))

    fun nextDay() = selectDate(selectedDate.value.plusDays(1))

    fun goToToday() = selectDate(clock())

    private fun applyDate(date: LocalDate, followsToday: Boolean) {
        if (date != selectedDate.value) flushPendingWeight()
        selectedDate.value = date
        savedStateHandle[KEY_DATE] = date.toEpochDay()
        savedStateHandle[KEY_FOLLOWS_TODAY] = followsToday
    }

    /**
     * Call when the screen becomes visible: moves on to the new day after midnight (if "today"
     * was shown) and refreshes steps/weights from Health Connect and the step sensor.
     */
    fun onScreenStarted() {
        val todayDate = clock()
        today.value = todayDate
        val followsToday = savedStateHandle.get<Boolean>(KEY_FOLLOWS_TODAY) ?: true
        if (followsToday && selectedDate.value != todayDate) applyDate(todayDate, followsToday = true)
        refresh(userInitiated = false)
    }

    fun retry() {
        reloadTrigger.update { it + 1 }
    }

    // ---- Health Connect / step sensor ----

    /**
     * Imports the last [SYNC_DAYS] days from Health Connect and reads the step sensor (each only if
     * enabled in the settings). Automatic refreshes are skipped if the last one was < 1 min ago.
     */
    fun refresh(userInitiated: Boolean = true) {
        if (syncJob?.isActive == true) return
        val now = nowMillis()
        val last = lastRefreshAt
        if (!userInitiated && last != null && now - last in 0 until AUTO_REFRESH_INTERVAL_MS) return
        syncJob = viewModelScope.launch {
            val settings = try {
                settingsFlow.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading settings failed", e)
                return@launch
            }
            val useHealthConnect = settings.healthConnectEnabled
            val useSensor = settings.stepSensorEnabled
            if (!useHealthConnect && !useSensor) {
                if (userInitiated) {
                    _events.send(
                        DiaryEvent.Message(
                            "Keine automatische Schrittquelle aktiv. Verbinde Health Connect oder trage deine Schritte selbst ein.",
                        ),
                    )
                }
                return@launch
            }
            lastRefreshAt = now
            syncStatus.update { it.copy(isSyncing = true, userInitiated = userInitiated) }
            var error: String? = null
            try {
                if (useHealthConnect) {
                    error = try {
                        val result = withContext(ioDispatcher) { syncHealth(SYNC_DAYS) }
                        if (result.success) null else result.message.ifBlank { HC_SYNC_FAILED }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Health Connect sync failed", e)
                        HC_SYNC_FAILED
                    }
                }
                if (useSensor) {
                    try {
                        withContext(ioDispatcher) { readStepSensor() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Reading the step sensor failed", e)
                        if (error == null) error = "Der Schrittsensor konnte nicht gelesen werden."
                    }
                }
            } finally {
                syncStatus.value = SyncStatus(isSyncing = false, userInitiated = false, error = error)
            }
            val message = error
            if (userInitiated && message != null) _events.send(DiaryEvent.Message(message))
        }
    }

    fun setManualSteps(steps: Long) {
        val date = selectedDate.value
        if (date.isAfter(clock())) return
        if (steps < 0L || steps > MAX_STEPS_PER_DAY) {
            _events.trySend(DiaryEvent.Message("Bitte eine Schrittzahl zwischen 0 und 200.000 eingeben."))
            return
        }
        viewModelScope.launch {
            try {
                activityRepository.setManualSteps(date, steps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving steps failed", e)
                _events.send(DiaryEvent.Message("Die Schritte konnten nicht gespeichert werden."))
            }
        }
    }

    // ---- weight ----

    /** +/- button: changes the shown weight immediately, saves after a short pause (debounced). */
    fun changeWeightBy(deltaKg: Double) {
        val date = selectedDate.value
        if (date.isAfter(clock())) return
        val current = pendingWeight.value?.takeIf { it.date == date }?.kg
            ?: uiState.value.weight?.takeIf { uiState.value.date == date }?.valueKg
            ?: return
        val updated = PendingWeight(date, adjustWeight(current, deltaKg))
        pendingWeight.value = updated
        unsavedWeight = updated
        weightJob?.cancel()
        weightJob = viewModelScope.launch {
            delay(WEIGHT_SAVE_DEBOUNCE_MS)
            saveUnsavedWeight()
        }
    }

    /** Weight typed into the dialog: saved immediately. */
    fun setWeight(kg: Double) {
        val date = selectedDate.value
        if (date.isAfter(clock())) return
        if (!isPlausibleWeight(kg)) {
            _events.trySend(DiaryEvent.Message("Bitte ein Gewicht zwischen 20 und 350 kg eingeben."))
            return
        }
        val updated = PendingWeight(date, roundToHundredth(kg))
        pendingWeight.value = updated
        unsavedWeight = updated
        weightJob?.cancel()
        weightJob = viewModelScope.launch { saveUnsavedWeight() }
    }

    /** Saves a waiting +/- change right away (screen left, day changed). */
    fun flushPendingWeight() {
        if (unsavedWeight == null) return
        weightJob?.cancel()
        weightJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { saveUnsavedWeight() }
    }

    private suspend fun saveUnsavedWeight() {
        val pending = unsavedWeight ?: return
        unsavedWeight = null
        val saved = withContext(NonCancellable + ioDispatcher) {
            try {
                measurementRepository.setDailyManualValue(MeasurementType.WEIGHT, pending.date, pending.kg)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Saving weight failed", e)
                false
            }
        }
        if (!saved) {
            pendingWeight.compareAndSet(pending, null)
            _events.trySend(DiaryEvent.Message("Das Gewicht konnte nicht gespeichert werden."))
            return
        }
        // Keep showing the new value until the database delivers it (no flicker back to the old value).
        withTimeoutOrNull(PENDING_WEIGHT_TIMEOUT_MS) {
            measurementRepository.observeLatestWeightOnOrBefore(pending.date).first { it == pending.kg }
        }
        pendingWeight.compareAndSet(pending, null)
    }

    override fun onCleared() {
        val pending = unsavedWeight ?: return
        unsavedWeight = null
        // viewModelScope is already cancelled here; write the last change on an independent scope.
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                measurementRepository.setDailyManualValue(MeasurementType.WEIGHT, pending.date, pending.kg)
            } catch (e: Exception) {
                Log.w(TAG, "Saving weight on clear failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "DiaryViewModel"
        private const val KEY_DATE = "diary_date"
        private const val KEY_FOLLOWS_TODAY = "diary_follows_today"
        private const val HC_SYNC_FAILED = "Health Connect konnte nicht synchronisiert werden."
        private const val AUTO_REFRESH_INTERVAL_MS = 60_000L
        private const val PENDING_WEIGHT_TIMEOUT_MS = 2_000L

        /** Days imported from Health Connect on each refresh. */
        const val SYNC_DAYS = 7

        /** Pause after the last +/- tap before the weight is saved. */
        const val WEIGHT_SAVE_DEBOUNCE_MS = 600L

        /** Step of the +/- buttons. */
        const val WEIGHT_STEP_KG = 0.1

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainerFrom(this)
                DiaryViewModel(
                    diaryRepository = container.diaryRepository,
                    measurementRepository = container.measurementRepository,
                    activityRepository = container.activityRepository,
                    settingsFlow = container.settingsRepository.settings,
                    profileFlow = container.settingsRepository.profile,
                    targetsFor = { date -> container.targetsUseCase.observeTargets(date) },
                    syncHealth = { days -> container.healthSyncService.syncRecent(days) },
                    readStepSensor = { container.stepSensorTracker.readAndStore() },
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}
