package de.kalorienkompass.ui.navigation

import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import java.time.LocalDate

/**
 * Route patterns and builders. Dates are passed as epochDay Long, meals/types by enum name.
 * Optional args use -1L / "" / false as "absent" (absent args are simply left out of the route).
 * String values (e.g. barcodes) are percent-encoded, Navigation decodes them again.
 */
object Routes {
    // bottom tabs
    const val DIARY = "diary"
    const val FOODS = "foods"
    const val PROGRESS = "progress"
    const val PROFILE = "profile"

    const val ONBOARDING = "onboarding"

    const val MEAL = "meal/{date}/{meal}"
    fun meal(date: LocalDate, meal: MealType): String = "meal/${date.toEpochDay()}/${meal.name}"

    const val FOOD_SEARCH = "food_search?date={date}&meal={meal}&pick={pick}"
    fun foodSearch(date: LocalDate, meal: MealType): String =
        withQuery("food_search", "date" to date.toEpochDay(), "meal" to meal.name)

    /** Food picker for recipe ingredients (pick = true, no date/meal). */
    fun foodPick(): String = withQuery("food_search", "pick" to true)

    const val BARCODE = "barcode?date={date}&meal={meal}&pick={pick}"
    fun barcode(date: LocalDate?, meal: MealType?, pick: Boolean = false): String =
        withQuery("barcode", "date" to date?.toEpochDay(), "meal" to meal?.name, "pick" to pick.orNull())

    const val AI_PHOTO = "ai_photo/{date}/{meal}"
    fun aiPhoto(date: LocalDate, meal: MealType): String = "ai_photo/${date.toEpochDay()}/${meal.name}"

    const val FOOD_DETAIL = "food_detail/{foodId}?date={date}&meal={meal}&pick={pick}"
    fun foodDetail(foodId: Long, date: LocalDate?, meal: MealType?, pick: Boolean = false): String =
        withQuery("food_detail/$foodId", "date" to date?.toEpochDay(), "meal" to meal?.name, "pick" to pick.orNull())

    const val CUSTOM_FOOD = "custom_food?foodId={foodId}&barcode={barcode}&date={date}&meal={meal}&pick={pick}"
    fun customFood(
        foodId: Long? = null,
        barcode: String? = null,
        date: LocalDate? = null,
        meal: MealType? = null,
        pick: Boolean = false,
    ): String = withQuery(
        "custom_food",
        "foodId" to foodId?.takeIf { it > 0L },
        "barcode" to barcode?.trim()?.takeIf { it.isNotEmpty() },
        "date" to date?.toEpochDay(),
        "meal" to meal?.name,
        "pick" to pick.orNull(),
    )

    const val RECIPE_EDIT = "recipe_edit?recipeId={recipeId}"
    fun recipeEdit(recipeId: Long? = null): String =
        withQuery("recipe_edit", "recipeId" to recipeId?.takeIf { it > 0L })

    const val RECIPE_DETAIL = "recipe_detail/{recipeId}?date={date}&meal={meal}"
    fun recipeDetail(recipeId: Long, date: LocalDate? = null, meal: MealType? = null): String =
        withQuery("recipe_detail/$recipeId", "date" to date?.toEpochDay(), "meal" to meal?.name)

    const val MEASUREMENT_HISTORY = "measurements/{type}"
    fun measurementHistory(type: MeasurementType): String = "measurements/${type.name}"

    const val GOALS = "goals"
    const val SETTINGS = "settings"
    const val HEALTH_CONNECT = "health_connect"
    const val PRIVACY = "privacy"

    /** Routes that show the bottom navigation bar. */
    val TAB_ROUTES: Set<String> = setOf(DIARY, FOODS, PROGRESS, PROFILE)

    private fun Boolean.orNull(): Boolean? = if (this) true else null

    private fun withQuery(base: String, vararg params: Pair<String, Any?>): String {
        val present = params.filter { it.second != null }
        if (present.isEmpty()) return base
        return base + "?" + present.joinToString("&") { (key, value) -> "$key=${encodeRouteArg(value.toString())}" }
    }
}

/** Keys for results passed back via `previousBackStackEntry/getBackStackEntry(...).savedStateHandle`. */
object NavResults {
    const val PICKED_FOOD_ID = "picked_food_id"
    const val PICKED_GRAMS = "picked_grams"
}

/**
 * Percent-encodes [value] for use in a route exactly like `android.net.Uri.encode(value)`:
 * UTF-8 bytes are escaped except letters, digits and the unreserved characters `_-!.~'()*`.
 * Pure Kotlin, so route building also works in plain JVM unit tests.
 */
internal fun encodeRouteArg(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch.isAsciiLetterOrDigit() || ch in UNRESERVED)) {
            out.append(ch)
        } else {
            out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

private const val UNRESERVED = "_-!.~'()*"
private const val HEX = "0123456789ABCDEF"

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Conversion of raw navigation argument values (absent optional values are -1L / "" / false).
 * Invalid values (unknown enum names, out-of-range dates) are treated as absent instead of crashing.
 */
internal object NavArgs {
    fun date(epochDay: Long?): LocalDate? {
        if (epochDay == null || epochDay == ABSENT_LONG) return null
        return try {
            LocalDate.ofEpochDay(epochDay)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    fun id(value: Long?): Long? = value?.takeIf { it > 0L }

    fun meal(name: String?): MealType? = MealType.fromName(name)

    fun measurementType(name: String?): MeasurementType? = MeasurementType.fromName(name)

    fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    const val ABSENT_LONG = -1L
}
