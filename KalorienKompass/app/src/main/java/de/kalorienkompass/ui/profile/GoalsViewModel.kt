package de.kalorienkompass.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.util.formatInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Editable copy of profile, goal settings and step goal (numbers as text while typing). */
data class GoalsDraft(
    val name: String,
    val sex: Sex,
    val birthYear: String,
    val height: String,
    val startWeight: String,
    val targetWeight: String,
    val activity: ActivityLevel,
    val goalType: GoalType,
    val weeklyRate: Double,
    val customEnabled: Boolean,
    val customCalories: String,
    val carbs: Int,
    val protein: Int,
    val fat: Int,
    val breakfast: Int,
    val lunch: Int,
    val dinner: Int,
    val snack: Int,
    val stepGoal: String,
) {
    val macroSum: Int get() = carbs + protein + fat
    val mealSum: Int get() = breakfast + lunch + dinner + snack

    companion object {
        fun from(profile: UserProfile, goals: NutritionGoalSettings, settings: AppSettings): GoalsDraft = GoalsDraft(
            name = profile.name,
            sex = profile.sex,
            birthYear = profile.birthYear.toString(),
            height = formatInput(profile.heightCm, 1),
            startWeight = formatInput(profile.startWeightKg, 1),
            targetWeight = formatInput(profile.targetWeightKg, 1),
            activity = profile.activityLevel,
            goalType = profile.goalType,
            weeklyRate = profile.weeklyRateKg.takeIf { it in ProfileRules.WEEKLY_RATES } ?: 0.5,
            customEnabled = (goals.customCalorieGoal ?: 0) > 0,
            customCalories = goals.customCalorieGoal?.takeIf { it > 0 }?.toString().orEmpty(),
            carbs = goals.carbsPercent,
            protein = goals.proteinPercent,
            fat = goals.fatPercent,
            breakfast = goals.breakfastPercent,
            lunch = goals.lunchPercent,
            dinner = goals.dinnerPercent,
            snack = goals.snackPercent,
            stepGoal = settings.stepGoal.toString(),
        )
    }
}

/** German validation messages per field (null = ok). */
data class GoalsErrors(
    val birthYear: String? = null,
    val height: String? = null,
    val startWeight: String? = null,
    val targetWeight: String? = null,
    val customCalories: String? = null,
    val macros: String? = null,
    val meals: String? = null,
    val stepGoal: String? = null,
) {
    val hasErrors: Boolean
        get() = listOf(birthYear, height, startWeight, targetWeight, customCalories, macros, meals, stepGoal)
            .any { it != null }
}

/** Computed energy requirement for the draft. */
data class GoalsPreview(
    val weightKg: Double,
    val bmr: Double,
    val tdee: Double,
    val adjustment: Double,
    val computedCalories: Int,
    /** the minimum (1200/1500 kcal) was applied */
    val minimumApplied: Boolean,
    val effectiveCalories: Int,
    val targets: DailyTargets,
)

fun GoalsDraft.validate(): GoalsErrors = GoalsErrors(
    birthYear = ProfileRules.birthYearError(birthYear),
    height = ProfileRules.heightError(height),
    startWeight = ProfileRules.weightError(startWeight, "dein Startgewicht"),
    targetWeight = ProfileRules.weightError(targetWeight, "dein Zielgewicht"),
    customCalories = if (customEnabled && ProfileRules.parseCustomKcal(customCalories) == null) {
        "Bitte einen Wert zwischen ${ProfileRules.MIN_CUSTOM_KCAL} und ${ProfileRules.MAX_CUSTOM_KCAL} kcal eingeben."
    } else {
        null
    },
    macros = if (macroSum != 100) "Die Makronährstoffe ergeben $macroSum % – es müssen genau 100 % sein." else null,
    meals = if (mealSum != 100) "Die Mahlzeiten ergeben $mealSum % – es müssen genau 100 % sein." else null,
    stepGoal = if (ProfileRules.parseStepGoal(stepGoal) == null) {
        "Bitte ein Schrittziel zwischen 1.000 und 100.000 eingeben."
    } else {
        null
    },
)

/** Energy requirement for the draft, using [latestWeightKg] (fallback: start weight). null if inputs are invalid. */
fun GoalsDraft.preview(latestWeightKg: Double?, today: LocalDate = LocalDate.now()): GoalsPreview? {
    val year = ProfileRules.parseBirthYear(birthYear) ?: return null
    val heightCm = ProfileRules.parseHeight(height) ?: return null
    val weight = latestWeightKg ?: ProfileRules.parseWeight(startWeight) ?: return null
    val age = GoalCalculator.age(year, today)
    val bmr = GoalCalculator.bmr(sex, weight, heightCm, age)
    val tdee = GoalCalculator.tdee(bmr, activity)
    val profile = UserProfile(
        sex = sex,
        birthYear = year,
        heightCm = heightCm,
        activityLevel = activity,
        goalType = goalType,
        weeklyRateKg = weeklyRate,
    )
    val adjustment = GoalCalculator.dailyAdjustment(goalType, weeklyRate)
    val computed = GoalCalculator.dailyCalories(profile, weight, today)
    val custom = if (customEnabled) ProfileRules.parseCustomKcal(customCalories) else null
    val effective = custom ?: computed
    val goals = toGoalSettings(NutritionGoalSettings())
    return GoalsPreview(
        weightKg = weight,
        bmr = bmr,
        tdee = tdee,
        adjustment = adjustment,
        computedCalories = computed,
        minimumApplied = ((tdee + adjustment) / 10.0).roundToInt() * 10 < GoalCalculator.minCalories(sex),
        effectiveCalories = effective,
        targets = GoalCalculator.targets(effective, goals),
    )
}

/** Applies the (valid) draft to the stored profile; keeps onboarding flag and other fields. */
fun GoalsDraft.toProfile(current: UserProfile): UserProfile = current.copy(
    name = name.trim().take(ProfileRules.MAX_NAME_LENGTH),
    sex = sex,
    birthYear = ProfileRules.parseBirthYear(birthYear) ?: current.birthYear,
    heightCm = ProfileRules.parseHeight(height) ?: current.heightCm,
    startWeightKg = ProfileRules.parseWeight(startWeight) ?: current.startWeightKg,
    targetWeightKg = ProfileRules.parseWeight(targetWeight) ?: current.targetWeightKg,
    activityLevel = activity,
    goalType = goalType,
    weeklyRateKg = weeklyRate,
)

fun GoalsDraft.toGoalSettings(current: NutritionGoalSettings): NutritionGoalSettings = current.copy(
    customCalorieGoal = if (customEnabled) ProfileRules.parseCustomKcal(customCalories) else null,
    carbsPercent = carbs,
    proteinPercent = protein,
    fatPercent = fat,
    breakfastPercent = breakfast,
    lunchPercent = lunch,
    dinnerPercent = dinner,
    snackPercent = snack,
)

/**
 * Adjusts [values] (percentages) so that they sum to 100: the difference goes to [preferIndex]
 * first, any rest to the other entries (each kept within 0..100).
 */
fun balanceTo100(values: List<Int>, preferIndex: Int): List<Int> {
    val result = values.map { it.coerceIn(0, 100) }.toMutableList()
    var diff = 100 - result.sum()
    val order = listOf(preferIndex) + result.indices.filter { it != preferIndex }.sortedByDescending { result[it] }
    for (i in order) {
        if (diff == 0) break
        val newValue = (result[i] + diff).coerceIn(0, 100)
        diff -= newValue - result[i]
        result[i] = newValue
    }
    return result
}

sealed interface GoalsEvent {
    data object Saved : GoalsEvent
    data class Error(val message: String) : GoalsEvent
}

class GoalsViewModel(
    private val settings: SettingsRepository,
    measurements: MeasurementRepository,
) : ViewModel() {

    /** null while loading */
    var draft: GoalsDraft? by mutableStateOf(null)
        private set

    private var initial: GoalsDraft? by mutableStateOf(null)

    var saving: Boolean by mutableStateOf(false)
        private set

    /** Latest weight entry on/before today (null if there is none). */
    val latestWeight: StateFlow<Double?> = measurements.observeLatestWeightOnOrBefore(LocalDate.now())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val eventChannel = Channel<GoalsEvent>(Channel.BUFFERED)
    val events: Flow<GoalsEvent> = eventChannel.receiveAsFlow()

    val isDirty: Boolean
        get() {
            val d = draft ?: return false
            return d != initial
        }

    init {
        viewModelScope.launch {
            val loaded = GoalsDraft.from(
                settings.profile.first(),
                settings.goalSettings.first(),
                settings.settings.first(),
            )
            initial = loaded
            draft = loaded
        }
    }

    fun update(transform: (GoalsDraft) -> GoalsDraft) {
        draft = draft?.let(transform)
    }

    fun save() {
        val d = draft ?: return
        if (saving || d.validate().hasErrors) return
        saving = true
        viewModelScope.launch {
            try {
                settings.updateProfile { d.toProfile(it) }
                settings.updateGoalSettings { d.toGoalSettings(it) }
                val steps = ProfileRules.parseStepGoal(d.stepGoal)
                if (steps != null) settings.updateSettings { it.copy(stepGoal = steps) }
                initial = d
                eventChannel.send(GoalsEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventChannel.send(GoalsEvent.Error("Speichern fehlgeschlagen: ${e.localizedMessage ?: e.javaClass.simpleName}"))
            } finally {
                saving = false
            }
        }
    }
}
