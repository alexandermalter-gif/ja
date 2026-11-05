package de.kalorienkompass.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Male
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.GoalType
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.domain.GoalCalculator
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.util.formatDecimal
import de.kalorienkompass.util.formatGrams
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.monthLong
import de.kalorienkompass.util.parseDecimal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil

/** Input limits and validation shared by onboarding and the goals screen (German messages). */
internal object ProfileRules {
    const val MIN_HEIGHT_CM: Double = 100.0
    const val MAX_HEIGHT_CM: Double = 250.0
    const val MIN_WEIGHT_KG: Double = 30.0
    const val MAX_WEIGHT_KG: Double = 300.0
    const val MIN_CUSTOM_KCAL: Int = 800
    const val MAX_CUSTOM_KCAL: Int = 6000
    const val MIN_STEP_GOAL: Int = 1000
    const val MAX_STEP_GOAL: Int = 100_000
    const val STEP_GOAL_STEP: Int = 500
    const val MAX_NAME_LENGTH: Int = 30
    val WEEKLY_RATES: List<Double> = listOf(0.25, 0.5, 0.75, 1.0)

    fun birthYearRange(today: LocalDate = LocalDate.now()): IntRange = (today.year - 100)..(today.year - 12)

    fun parseBirthYear(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in birthYearRange() }

    fun birthYearError(text: String): String? {
        if (text.isBlank()) return "Bitte gib dein Geburtsjahr ein."
        val range = birthYearRange()
        return if (parseBirthYear(text) == null) {
            "Bitte ein Jahr zwischen ${range.first} und ${range.last} eingeben."
        } else {
            null
        }
    }

    fun parseHeight(text: String): Double? =
        parseDecimal(text)?.takeIf { it in MIN_HEIGHT_CM..MAX_HEIGHT_CM }

    fun heightError(text: String): String? = when {
        text.isBlank() -> "Bitte gib deine Größe ein."
        parseHeight(text) == null -> "Bitte eine Größe zwischen ${MIN_HEIGHT_CM.toInt()} und ${MAX_HEIGHT_CM.toInt()} cm eingeben."
        else -> null
    }

    fun parseWeight(text: String): Double? =
        parseDecimal(text)?.takeIf { it in MIN_WEIGHT_KG..MAX_WEIGHT_KG }

    fun weightError(text: String, what: String = "dein Gewicht"): String? = when {
        text.isBlank() -> "Bitte gib $what ein."
        parseWeight(text) == null -> "Bitte ein Gewicht zwischen ${MIN_WEIGHT_KG.toInt()} und ${MAX_WEIGHT_KG.toInt()} kg eingeben."
        else -> null
    }

    /** Direction check: losing weight needs a lower, gaining a higher target. null if consistent. */
    fun targetDirectionProblem(goal: GoalType, currentKg: Double?, targetKg: Double?): String? {
        if (currentKg == null || targetKg == null) return null
        return when (goal) {
            GoalType.LOSE -> if (targetKg >= currentKg) {
                "Zum Abnehmen sollte das Zielgewicht unter ${formatDecimal(currentKg)} kg liegen."
            } else {
                null
            }
            GoalType.GAIN -> if (targetKg <= currentKg) {
                "Zum Zunehmen sollte das Zielgewicht über ${formatDecimal(currentKg)} kg liegen."
            } else {
                null
            }
            GoalType.MAINTAIN -> null
        }
    }

    fun parseCustomKcal(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in MIN_CUSTOM_KCAL..MAX_CUSTOM_KCAL }

    fun parseStepGoal(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in MIN_STEP_GOAL..MAX_STEP_GOAL }

    /** "≈ 550 kcal weniger pro Tag" for the weekly rate. */
    fun rateDescription(goal: GoalType, rate: Double): String {
        val perDay = abs(GoalCalculator.dailyAdjustment(goal, rate))
        return when (goal) {
            GoalType.LOSE -> "≈ ${formatKcal(perDay)} kcal weniger pro Tag"
            GoalType.GAIN -> "≈ ${formatKcal(perDay)} kcal mehr pro Tag"
            GoalType.MAINTAIN -> ""
        }
    }

    /** "0,25", "0,5", "0,75", "1,0" */
    fun rateLabel(rate: Double): String = if (rate == 1.0) "1,0" else formatGrams(rate)

    /** Rough date when the target weight is reached, e.g. "ca. März 2027 (in 15 Wochen)". null if not applicable. */
    fun estimatedArrival(goal: GoalType, currentKg: Double?, targetKg: Double?, rate: Double, today: LocalDate = LocalDate.now()): String? {
        if (goal == GoalType.MAINTAIN || currentKg == null || targetKg == null || rate <= 0.0) return null
        if (targetDirectionProblem(goal, currentKg, targetKg) != null) return null
        val weeks = ceil(abs(currentKg - targetKg) / rate).toLong().coerceAtLeast(1L)
        val date = today.plusWeeks(weeks)
        val weeksText = if (weeks == 1L) "1 Woche" else "$weeks Wochen"
        return "ca. ${monthLong(date)} ${date.year} (in $weeksText)"
    }
}

internal fun Sex.icon(): ImageVector = when (this) {
    Sex.MALE -> Icons.Filled.Male
    Sex.FEMALE -> Icons.Filled.Female
}

internal fun ActivityLevel.icon(): ImageVector = when (this) {
    ActivityLevel.SEDENTARY -> Icons.Filled.Chair
    ActivityLevel.LIGHT -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityLevel.MODERATE -> Icons.Filled.Hiking
    ActivityLevel.ACTIVE -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityLevel.VERY_ACTIVE -> Icons.Filled.FitnessCenter
}

internal fun GoalType.icon(): ImageVector = when (this) {
    GoalType.LOSE -> Icons.AutoMirrored.Filled.TrendingDown
    GoalType.MAINTAIN -> Icons.AutoMirrored.Filled.TrendingFlat
    GoalType.GAIN -> Icons.AutoMirrored.Filled.TrendingUp
}

/** BMI category color (blue: under, mint: normal, amber: over, red: obese). */
internal fun bmiColor(bmi: Double): Color = when {
    bmi <= 0.0 -> Color.Gray
    bmi < 18.5 -> AppColors.protein
    bmi < 25.0 -> AppColors.kcal
    bmi < 30.0 -> AppColors.carbs
    else -> AppColors.over
}

/** Two big cards "Männlich" / "Weiblich". */
@Composable
internal fun SexChoice(selected: Sex?, onSelect: (Sex) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Sex.entries.forEach { sex ->
            ChoiceCard(
                selected = sex == selected,
                title = sex.label,
                icon = sex.icon(),
                centered = true,
                onClick = { onSelect(sex) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One card per activity level (label, description, PAL factor). */
@Composable
internal fun ActivityLevelChoice(selected: ActivityLevel?, onSelect: (ActivityLevel) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActivityLevel.entries.forEach { level ->
            ChoiceCard(
                selected = level == selected,
                title = level.label,
                subtitle = "${level.description} · Faktor ${formatDecimal(level.pal, 2)}",
                icon = level.icon(),
                onClick = { onSelect(level) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Three cards: Abnehmen / Gewicht halten / Zunehmen. */
@Composable
internal fun GoalTypeChoice(selected: GoalType?, onSelect: (GoalType) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GoalType.entries.forEach { goal ->
            ChoiceCard(
                selected = goal == selected,
                title = goal.label,
                subtitle = when (goal) {
                    GoalType.LOSE -> "Mit einem moderaten Kaloriendefizit"
                    GoalType.MAINTAIN -> "Dein Gewicht stabil halten"
                    GoalType.GAIN -> "Mit einem leichten Kalorienüberschuss"
                },
                icon = goal.icon(),
                onClick = { onSelect(goal) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Segments 0,25 / 0,5 / 0,75 / 1,0 kg per week with the resulting daily energy change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeeklyRateChoice(
    goal: GoalType,
    rate: Double,
    onSelect: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (goal == GoalType.GAIN) "Tempo: Zunahme in kg pro Woche" else "Tempo: Abnahme in kg pro Woche",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ProfileRules.WEEKLY_RATES.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = rate == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ProfileRules.WEEKLY_RATES.size),
                    colors = profileSegmentedColors(),
                    icon = {},
                    label = {
                        Text(
                            text = ProfileRules.rateLabel(value),
                            fontWeight = if (rate == value) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append(ProfileRules.rateDescription(goal, rate))
                if (rate >= 1.0) append(" – sehr ehrgeizig, nur kurzfristig empfehlenswert")
                else if (rate <= 0.25) append(" – sanft und gut durchzuhalten")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rate >= 1.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Mint segmented buttons (the default secondary container would be amber in this theme). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun profileSegmentedColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
    activeContentColor = MaterialTheme.colorScheme.primary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
    inactiveBorderColor = MaterialTheme.colorScheme.outline,
)
