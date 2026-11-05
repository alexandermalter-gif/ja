package de.kalorienkompass.ui.recipes

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewmodel.CreationExtras
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.data.repo.RecipeRepository
import de.kalorienkompass.di.appContainerFrom

/** Repositories used by the recipe screens. */
internal class RecipeDependencies(
    val recipes: RecipeRepository,
    val foods: FoodRepository,
    val diary: DiaryRepository,
)

/** Replaces the app container's repositories (UI tests); null = use the AppContainer. */
internal val LocalRecipeDependencies = staticCompositionLocalOf<RecipeDependencies?> { null }

/** [override] if set, else the repositories of the process-wide AppContainer. */
internal fun CreationExtras.recipeDependencies(override: RecipeDependencies?): RecipeDependencies =
    override ?: appContainerFrom(this).let {
        RecipeDependencies(it.recipeRepository, it.foodRepository, it.diaryRepository)
    }
