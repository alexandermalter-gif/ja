package de.kalorienkompass.ui.onboarding

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.backup.BackupManager
import de.kalorienkompass.backup.BackupSummary
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.ui.profile.ProfileRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

enum class OnboardingStep {
    WELCOME, ABOUT, BODY, ACTIVITY, GOAL, RESULT;

    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == entries.size - 1
}

/** Inputs of the onboarding (numbers as text while typing). */
data class OnboardingDraft(
    val name: String = "",
    val sex: Sex? = null,
    val birthYear: String = "",
    val height: String = "",
    val weight: String = "",
    val activity: ActivityLevel? = null,
    val goalType: GoalType? = null,
    val targetWeight: String = "",
    val weeklyRate: Double = 0.5,
) {
    val weightKg: Double? get() = ProfileRules.parseWeight(weight)
    val heightCm: Double? get() = ProfileRules.parseHeight(height)
    val targetKg: Double? get() = ProfileRules.parseWeight(targetWeight)

    /** Validation message for the target weight (null if ok or not needed). */
    fun targetError(): String? {
        val goal = goalType ?: return null
        if (goal == GoalType.MAINTAIN) return null
        return ProfileRules.weightError(targetWeight, "dein Zielgewicht")
            ?: ProfileRules.targetDirectionProblem(goal, weightKg, targetKg)
    }

    fun isStepValid(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.ABOUT -> sex != null && ProfileRules.birthYearError(birthYear) == null
        OnboardingStep.BODY -> ProfileRules.heightError(height) == null && ProfileRules.weightError(weight) == null
        OnboardingStep.ACTIVITY -> activity != null
        OnboardingStep.GOAL -> goalType != null && targetError() == null
        OnboardingStep.RESULT -> toProfile() != null
    }

    /** Complete profile, or null if something is missing. */
    fun toProfile(): UserProfile? {
        val s = sex ?: return null
        val year = ProfileRules.parseBirthYear(birthYear) ?: return null
        val h = heightCm ?: return null
        val w = weightKg ?: return null
        val level = activity ?: return null
        val goal = goalType ?: return null
        val target = if (goal == GoalType.MAINTAIN) w else targetKg ?: return null
        return UserProfile(
            onboardingDone = true,
            name = name.trim().take(ProfileRules.MAX_NAME_LENGTH),
            sex = s,
            birthYear = year,
            heightCm = h,
            startWeightKg = w,
            targetWeightKg = target,
            activityLevel = level,
            goalType = goal,
            weeklyRateKg = if (goal == GoalType.MAINTAIN) 0.5 else weeklyRate,
        )
    }
}

/** Result shown on the last page. */
data class OnboardingResult(
    val calories: Int,
    val bmr: Double,
    val tdee: Double,
    val adjustment: Double,
    val minimumApplied: Boolean,
    val targets: DailyTargets,
)

sealed interface OnboardingEvent {
    data object Finished : OnboardingEvent
    data class Message(val text: String) : OnboardingEvent
}

/** A picked backup waiting for confirmation. */
data class OnboardingRestore(val uri: Uri, val summary: BackupSummary)

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val measurements: MeasurementRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    var draft: OnboardingDraft by mutableStateOf(OnboardingDraft())
        private set

    var step: OnboardingStep by mutableStateOf(OnboardingStep.WELCOME)
        private set

    var saving: Boolean by mutableStateOf(false)
        private set

    /** Reading or importing a backup. */
    var restoring: Boolean by mutableStateOf(false)
        private set

    var pendingRestore: OnboardingRestore? by mutableStateOf(null)
        private set

    private val goalSettings: StateFlow<NutritionGoalSettings> = settings.goalSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, NutritionGoalSettings())

    private val eventChannel = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = eventChannel.receiveAsFlow()

    fun update(transform: (OnboardingDraft) -> OnboardingDraft) {
        draft = transform(draft)
    }

    fun next() {
        if (!draft.isStepValid(step) || step.isLast) return
        step = OnboardingStep.entries[step.ordinal + 1]
    }

    /** Returns false if already on the first page. */
    fun back(): Boolean {
        if (step.isFirst || saving) return false
        step = OnboardingStep.entries[step.ordinal - 1]
        return true
    }

    fun selectGoal(goal: GoalType) {
        draft = draft.copy(goalType = goal)
    }

    /** Result for the current draft (null while incomplete). */
    fun result(today: LocalDate = LocalDate.now()): OnboardingResult? {
        val profile = draft.toProfile() ?: return null
        val weight = profile.startWeightKg
        val age = GoalCalculator.age(profile.birthYear, today)
        val bmr = GoalCalculator.bmr(profile.sex, weight, profile.heightCm, age)
        val tdee = GoalCalculator.tdee(bmr, profile.activityLevel)
        val adjustment = GoalCalculator.dailyAdjustment(profile.goalType, profile.weeklyRateKg)
        val calories = GoalCalculator.dailyCalories(profile, weight, today)
        val goals = goalSettings.value
        val effective = goals.customCalorieGoal?.takeIf { it > 0 } ?: calories
        return OnboardingResult(
            calories = effective,
            bmr = bmr,
            tdee = tdee,
            adjustment = adjustment,
            minimumApplied = ((tdee + adjustment) / 10.0).roundToInt() * 10 < GoalCalculator.minCalories(profile.sex),
            targets = GoalCalculator.targets(effective, goals),
        )
    }

    /** Saves the profile (start weight = current weight) and today's weight, then finishes. */
    fun finish() {
        if (saving) return
        val profile = draft.toProfile() ?: return
        saving = true
        viewModelScope.launch {
            try {
                measurements.setDailyManualValue(MeasurementType.WEIGHT, LocalDate.now(), profile.startWeightKg)
                settings.updateProfile { current ->
                    current.copy(
                        onboardingDone = true,
                        name = profile.name,
                        sex = profile.sex,
                        birthYear = profile.birthYear,
                        heightCm = profile.heightCm,
                        startWeightKg = profile.startWeightKg,
                        targetWeightKg = profile.targetWeightKg,
                        activityLevel = profile.activityLevel,
                        goalType = profile.goalType,
                        weeklyRateKg = profile.weeklyRateKg,
                    )
                }
                eventChannel.send(OnboardingEvent.Finished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                eventChannel.send(
                    OnboardingEvent.Message("Speichern fehlgeschlagen: ${e.localizedMessage ?: e.javaClass.simpleName}"),
                )
            }
        }
    }

    // ---- restore a backup instead of entering everything again ----

    fun onBackupPicked(uri: Uri) {
        if (restoring) return
        restoring = true
        viewModelScope.launch {
            try {
                pendingRestore = OnboardingRestore(uri, backupManager.readBackupSummary(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventChannel.send(OnboardingEvent.Message(e.message ?: "Das Backup konnte nicht gelesen werden."))
            } finally {
                restoring = false
            }
        }
    }

    fun dismissRestore() {
        pendingRestore = null
    }

    fun confirmRestore() {
        val pending = pendingRestore ?: return
        pendingRestore = null
        restoring = true
        viewModelScope.launch {
            try {
                backupManager.restore(pending.uri)
                // restore() marks the onboarding as done; make sure even for unusual files.
                settings.updateProfile { it.copy(onboardingDone = true) }
                eventChannel.send(OnboardingEvent.Finished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restoring = false
                eventChannel.send(OnboardingEvent.Message(e.message ?: "Der Import ist fehlgeschlagen."))
            }
        }
    }

    fun showMessage(text: String) {
        viewModelScope.launch { eventChannel.send(OnboardingEvent.Message(text)) }
    }
}
