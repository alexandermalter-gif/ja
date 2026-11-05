package de.kalorienkompass.ui.recipes

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.IngredientWithFood
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.db.RecipeWithIngredients
import de.kalorienkompass.data.model.Nutrition
import de.kalorienkompass.data.repo.RecipeNutrition
import de.kalorienkompass.data.repo.RecipeRepository
import de.kalorienkompass.domain.NutritionMath
import de.kalorienkompass.util.formatInput
import de.kalorienkompass.util.parseDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Pure (Android-free) model and rules of the recipe feature, unit-tested on the JVM.
 */

internal const val MIN_RECIPE_PORTIONS = 1
internal const val MAX_RECIPE_PORTIONS = 99
internal const val MAX_RECIPE_NAME_LENGTH = 80
internal const val MAX_RECIPE_NOTE_LENGTH = 1000
internal const val MAX_INGREDIENT_AMOUNT = 100_000.0

/** Step of the "portions to log" stepper on the detail screen. */
internal const val LOG_PORTION_STEP = 0.5
internal const val MAX_LOG_PORTIONS = 99.0

private const val EPS = 1e-6

/** One ingredient row of the recipe editor. [food] is null when the food no longer exists. */
internal data class IngredientDraft(
    /** Stable key for UI lists (unique within one editor session). */
    val key: Long,
    val foodId: Long,
    val food: FoodEntity?,
    /** Amount in grams (or ml, see [unit]). */
    val grams: Double,
) {
    val isMissing: Boolean get() = food == null

    /** "g" or "ml". */
    val unit: String get() = food?.unit?.takeIf { it.isNotBlank() } ?: "g"

    val nutrition: Nutrition
        get() = if (food != null) NutritionMath.forGrams(food, grams) else Nutrition.ZERO
}

/** The editable state of a recipe (what the user sees in the editor). */
internal data class RecipeDraft(
    val name: String = "",
    val portions: Int = MIN_RECIPE_PORTIONS,
    val note: String = "",
    val ingredients: List<IngredientDraft> = emptyList(),
) {
    /** Ingredients that are stored on save: the food still exists and the amount is positive. */
    val savableIngredients: List<IngredientDraft>
        get() = ingredients.filter { it.food != null && it.grams > 0.0 }

    val missingIngredientCount: Int get() = ingredients.count { it.isMissing }
}

/** Compact, comparable representation of a draft, used to detect unsaved changes. */
internal data class DraftFingerprint(
    val name: String,
    val portions: Int,
    val note: String,
    val ingredients: List<Pair<Long, Double>>,
)

internal fun RecipeDraft.fingerprint(): DraftFingerprint = DraftFingerprint(
    name = name.trim(),
    portions = portions,
    note = note.trim(),
    ingredients = ingredients.map { it.foodId to it.grams },
)

internal enum class DraftError { MISSING_NAME, NO_INGREDIENTS }

/** Save rules: a name and at least one (existing) ingredient. */
internal fun RecipeDraft.validate(): Set<DraftError> = buildSet {
    if (name.isBlank()) add(DraftError.MISSING_NAME)
    if (savableIngredients.isEmpty()) add(DraftError.NO_INGREDIENTS)
}

/** The draft as a (not persisted) [RecipeWithIngredients], e.g. for [RecipeRepository.nutritionOf]. */
internal fun RecipeDraft.toRecipeWithIngredients(base: RecipeEntity? = null): RecipeWithIngredients {
    val recipe = (base ?: RecipeEntity(name = "")).copy(
        name = name.trim(),
        portions = portions.coerceIn(MIN_RECIPE_PORTIONS, MAX_RECIPE_PORTIONS),
        note = note.trim().ifEmpty { null },
    )
    return RecipeWithIngredients(
        recipe = recipe,
        ingredients = ingredients.mapIndexed { index, item ->
            IngredientWithFood(
                ingredient = RecipeIngredientEntity(
                    recipeId = recipe.id,
                    foodId = item.foodId,
                    grams = item.grams,
                    sortIndex = index,
                ),
                food = item.food,
            )
        },
    )
}

/** Totals and per-portion values, computed exactly like the diary does when logging the recipe. */
internal fun RecipeDraft.nutrition(): RecipeNutrition = RecipeRepository.nutritionOf(toRecipeWithIngredients())

/**
 * Entities for [RecipeRepository.save]. Keeps id, favorite flag and usage data of [original];
 * ingredients whose food no longer exists are dropped.
 */
internal fun RecipeDraft.toSaveModel(original: RecipeEntity?): Pair<RecipeEntity, List<RecipeIngredientEntity>> {
    val recipe = (original ?: RecipeEntity(name = "")).copy(
        name = name.trim(),
        portions = portions.coerceIn(MIN_RECIPE_PORTIONS, MAX_RECIPE_PORTIONS),
        note = note.trim().ifEmpty { null },
    )
    val ingredients = savableIngredients.mapIndexed { index, item ->
        RecipeIngredientEntity(
            recipeId = recipe.id,
            foodId = item.foodId,
            grams = item.grams,
            sortIndex = index,
        )
    }
    return recipe to ingredients
}

/** Editor draft of a stored recipe; [nextKey] provides unique row keys. */
internal fun RecipeWithIngredients.toDraft(nextKey: () -> Long): RecipeDraft = RecipeDraft(
    name = recipe.name,
    portions = recipe.portions.coerceIn(MIN_RECIPE_PORTIONS, MAX_RECIPE_PORTIONS),
    note = recipe.note.orEmpty(),
    ingredients = ingredients.map { item ->
        IngredientDraft(
            key = nextKey(),
            foodId = item.ingredient.foodId,
            food = item.food,
            grams = item.ingredient.grams,
        )
    },
)

/** Amount used when the picker did not deliver one: first portion of the food, else 100 g. */
internal fun defaultIngredientAmount(food: FoodEntity): Double =
    food.portions.firstOrNull { it.grams > 0.0 }?.grams ?: 100.0

/** Food id from the pick-result protocol (tolerates Int/Long/String values). */
internal fun parsePickedFoodId(raw: Any?): Long? {
    val id = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
    return id?.takeIf { it > 0L }
}

/** Grams from the pick-result protocol (tolerates Double/Float/Int/String values). */
internal fun parsePickedGrams(raw: Any?): Double? {
    val grams = when (raw) {
        is Number -> raw.toDouble()
        is String -> parseDecimal(raw)
        else -> null
    }
    return grams?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 }
}

// ---- "portions to log" stepper (0.5 steps) ----

internal fun canDecreaseLogPortions(current: Double): Boolean = current > LOG_PORTION_STEP + EPS

internal fun canIncreaseLogPortions(current: Double): Boolean = current < MAX_LOG_PORTIONS - EPS

/** Next multiple of 0.5 above [current] (0.75 → 1.0, 1.0 → 1.5). */
internal fun nextLogPortions(current: Double): Double {
    val next = (floor(current / LOG_PORTION_STEP + EPS) + 1.0) * LOG_PORTION_STEP
    return next.coerceAtMost(MAX_LOG_PORTIONS)
}

/** Previous multiple of 0.5 below [current] (1.0 → 0.5, 1.25 → 1.0), never below 0.5. */
internal fun previousLogPortions(current: Double): Double {
    if (!canDecreaseLogPortions(current)) return current
    val previous = (ceil(current / LOG_PORTION_STEP - EPS) - 1.0) * LOG_PORTION_STEP
    return previous.coerceAtLeast(LOG_PORTION_STEP)
}

// ---- German labels ----

/** 1.0 → "1", 1.5 → "1,5", 0.75 → "0,75". */
internal fun formatPortions(value: Double): String = formatInput(value, maxDecimals = 2)

/** "1 Portion", "0,5 Portionen", "2 Portionen". */
internal fun portionsLabel(value: Double): String =
    if (abs(value - 1.0) < EPS) "1 Portion" else "${formatPortions(value)} Portionen"

/** "1 Portion", "4 Portionen". */
internal fun portionsLabel(value: Int): String = if (value == 1) "1 Portion" else "$value Portionen"

/** "1 Zutat", "6 Zutaten", "keine Zutaten". */
internal fun ingredientsLabel(count: Int): String = when (count) {
    0 -> "keine Zutaten"
    1 -> "1 Zutat"
    else -> "$count Zutaten"
}
