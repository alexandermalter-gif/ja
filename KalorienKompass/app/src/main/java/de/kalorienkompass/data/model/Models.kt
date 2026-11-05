package de.kalorienkompass.data.model

import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
enum class MealType(val label: String, val emoji: String) {
    BREAKFAST("Frühstück", "☕"),
    LUNCH("Mittagessen", "🍝"),
    DINNER("Abendessen", "🥗"),
    SNACK("Snacks", "🍎");

    companion object {
        /** Sensible default meal for "now": Frühstück until 10:59, Mittagessen until 14:59,
         *  Snacks until 16:59, Abendessen until 21:59, otherwise Snacks. */
        fun forTimeOfDay(time: LocalTime = LocalTime.now()): MealType = when (time.hour) {
            in 4..10 -> BREAKFAST
            in 11..14 -> LUNCH
            in 15..16 -> SNACK
            in 17..21 -> DINNER
            else -> SNACK
        }

        fun fromName(name: String?): MealType? = MealType.entries.firstOrNull { it.name == name }
    }
}

@Serializable
enum class FoodSource(val label: String) {
    BUILTIN("Basis"),
    OPEN_FOOD_FACTS("Open Food Facts"),
    CUSTOM("Eigenes"),
    AI("KI-Schätzung"),
}

@Serializable
enum class EntrySource { FOOD, RECIPE, QUICK, AI }

@Serializable
enum class MeasurementType(val label: String, val unit: String) {
    WEIGHT("Gewicht", "kg"),
    BODY_FAT("Körperfett", "%"),
    WAIST("Bauchumfang", "cm"),
    HIP("Hüftumfang", "cm"),
    CHEST("Brustumfang", "cm"),
    UPPER_ARM("Oberarm", "cm"),
    THIGH("Oberschenkel", "cm");

    companion object {
        fun fromName(name: String?): MeasurementType? = MeasurementType.entries.firstOrNull { it.name == name }
    }
}

@Serializable
enum class DataSource(val label: String) {
    MANUAL("Manuell"),
    HEALTH_CONNECT("Health Connect"),
    SENSOR("Schrittsensor"),
}

@Serializable
enum class Sex(val label: String) {
    MALE("Männlich"),
    FEMALE("Weiblich"),
}

@Serializable
enum class ActivityLevel(val label: String, val description: String, val pal: Double) {
    SEDENTARY("Kaum aktiv", "Bürojob, wenig Bewegung", 1.2),
    LIGHT("Leicht aktiv", "Etwas Bewegung im Alltag, 1–2× Sport/Woche", 1.375),
    MODERATE("Mäßig aktiv", "Viel auf den Beinen oder 3–5× Sport/Woche", 1.55),
    ACTIVE("Sehr aktiv", "Körperliche Arbeit oder 6–7× Sport/Woche", 1.725),
    VERY_ACTIVE("Extrem aktiv", "Harte körperliche Arbeit + täglich Sport", 1.9),
}

@Serializable
enum class GoalType(val label: String) {
    LOSE("Abnehmen"),
    MAINTAIN("Gewicht halten"),
    GAIN("Zunehmen"),
}

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Hell"),
    DARK("Dunkel"),
}

@Serializable
enum class AiModel(val modelId: String, val label: String, val costHint: String) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5 – genaueste Erkennung (Standard)", "ca. 4 ct pro Foto"),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5 – schnell & gut", "ca. 2 ct pro Foto"),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 – am günstigsten", "unter 1 ct pro Foto"),
}

/** A named portion of a food, e.g. "1 Scheibe" = 50 g. */
@Serializable
data class FoodPortion(val name: String, val grams: Double)

/** Energy (kcal) and macros (g). Used for per-100 g values as well as totals. */
data class Nutrition(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double) {
    operator fun plus(o: Nutrition): Nutrition =
        Nutrition(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat)

    operator fun times(f: Double): Nutrition =
        Nutrition(kcal * f, protein * f, carbs * f, fat * f)

    companion object {
        val ZERO: Nutrition = Nutrition(0.0, 0.0, 0.0, 0.0)
    }
}
