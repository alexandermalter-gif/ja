package de.kalorienkompass.ui.navigation

import android.os.Bundle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.di.appContainer
import de.kalorienkompass.ui.ai.AiPhotoScreen
import de.kalorienkompass.ui.diary.DiaryScreen
import de.kalorienkompass.ui.diary.MealDetailScreen
import de.kalorienkompass.ui.food.CustomFoodEditScreen
import de.kalorienkompass.ui.food.FoodDetailScreen
import de.kalorienkompass.ui.food.FoodSearchScreen
import de.kalorienkompass.ui.food.FoodsHubScreen
import de.kalorienkompass.ui.health.HealthConnectScreen
import de.kalorienkompass.ui.health.PrivacyScreen
import de.kalorienkompass.ui.onboarding.OnboardingScreen
import de.kalorienkompass.ui.profile.GoalsScreen
import de.kalorienkompass.ui.profile.ProfileScreen
import de.kalorienkompass.ui.profile.SettingsScreen
import de.kalorienkompass.ui.progress.MeasurementHistoryScreen
import de.kalorienkompass.ui.progress.ProgressScreen
import de.kalorienkompass.ui.recipes.RecipeDetailScreen
import de.kalorienkompass.ui.recipes.RecipeEditScreen
import de.kalorienkompass.ui.scan.BarcodeScanScreen
import java.time.LocalDate

/**
 * Root of the UI: waits for the first profile emission (blank background meanwhile),
 * then starts at the diary (or the onboarding on first launch).
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val profile by container.settingsRepository.profile
        .collectAsStateWithLifecycle<UserProfile?>(initialValue = null)

    val loadedProfile = profile
    if (loadedProfile == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    } else {
        val startDestination = remember {
            if (loadedProfile.onboardingDone) Routes.DIARY else Routes.ONBOARDING
        }
        AppNavHost(startDestination = startDestination)
    }
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val TABS: List<TabItem> = listOf(
    TabItem(Routes.DIARY, "Tagebuch", Icons.Filled.Book),
    TabItem(Routes.FOODS, "Lebensmittel", Icons.Filled.Restaurant),
    TabItem(Routes.PROGRESS, "Fortschritt", Icons.AutoMirrored.Filled.ShowChart),
    TabItem(Routes.PROFILE, "Profil", Icons.Filled.Person),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && currentRoute in Routes.TAB_ROUTES

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Screens draw their own top bars (status bar insets); the bottom bar handles the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(navController = navController, currentRoute = currentRoute)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(220)) },
            exitTransition = { fadeOut(animationSpec = tween(160)) },
            popEnterTransition = { fadeIn(animationSpec = tween(220)) },
            popExitTransition = { fadeOut(animationSpec = tween(160)) },
        ) {
            // ---- bottom tabs ----
            composable(Routes.DIARY) {
                DiaryScreen(navController)
            }
            composable(Routes.FOODS) {
                FoodsHubScreen(navController)
            }
            composable(Routes.PROGRESS) {
                ProgressScreen(navController)
            }
            composable(Routes.PROFILE) {
                ProfileScreen(navController)
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(navController)
            }

            // ---- diary ----
            composable(route = Routes.MEAL, arguments = NavArguments.MEAL) { entry ->
                val args = entry.arguments
                MealDetailScreen(
                    navController,
                    args.optDate("date") ?: LocalDate.now(),
                    args.optMeal("meal") ?: MealType.forTimeOfDay(),
                )
            }

            // ---- food ----
            composable(route = Routes.FOOD_SEARCH, arguments = NavArguments.FOOD_SEARCH) { entry ->
                val args = entry.arguments
                FoodSearchScreen(
                    navController,
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }
            composable(route = Routes.BARCODE, arguments = NavArguments.BARCODE) { entry ->
                val args = entry.arguments
                BarcodeScanScreen(
                    navController,
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }
            composable(route = Routes.AI_PHOTO, arguments = NavArguments.AI_PHOTO) { entry ->
                val args = entry.arguments
                AiPhotoScreen(
                    navController,
                    args.optDate("date") ?: LocalDate.now(),
                    args.optMeal("meal") ?: MealType.forTimeOfDay(),
                )
            }
            composable(route = Routes.FOOD_DETAIL, arguments = NavArguments.FOOD_DETAIL) { entry ->
                val args = entry.arguments
                FoodDetailScreen(
                    navController,
                    args?.getLong("foodId", -1L) ?: -1L,
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }
            composable(route = Routes.CUSTOM_FOOD, arguments = NavArguments.CUSTOM_FOOD) { entry ->
                val args = entry.arguments
                CustomFoodEditScreen(
                    navController,
                    args.optId("foodId"),
                    args.optString("barcode"),
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                    args.optString("action").orEmpty(),
                )
            }

            // ---- recipes ----
            composable(route = Routes.RECIPE_EDIT, arguments = NavArguments.RECIPE_EDIT) { entry ->
                RecipeEditScreen(navController, entry.arguments.optId("recipeId"))
            }
            composable(route = Routes.RECIPE_DETAIL, arguments = NavArguments.RECIPE_DETAIL) { entry ->
                val args = entry.arguments
                RecipeDetailScreen(
                    navController,
                    args?.getLong("recipeId", -1L) ?: -1L,
                    args.optDate("date"),
                    args.optMeal("meal"),
                )
            }

            // ---- progress ----
            composable(route = Routes.MEASUREMENT_HISTORY, arguments = NavArguments.MEASUREMENT_HISTORY) { entry ->
                MeasurementHistoryScreen(
                    navController,
                    NavArgs.measurementType(entry.arguments?.getString("type")) ?: MeasurementType.WEIGHT,
                )
            }

            // ---- profile, settings, health ----
            composable(Routes.GOALS) {
                GoalsScreen(navController)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(navController)
            }
            composable(Routes.HEALTH_CONNECT) {
                HealthConnectScreen(navController)
            }
            composable(Routes.PRIVACY) {
                PrivacyScreen(navController)
            }
        }
    }
}

@Composable
private fun AppBottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    if (currentRoute != tab.route) navController.navigateToTab(tab.route)
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * Standard bottom-tab navigation (also usable for links like "Mehr" → [Routes.PROGRESS]):
 * pops back to the diary root while saving the current tab's back stack, never stacks the same
 * tab twice and restores the target tab's saved state.
 */
fun NavController.navigateToTab(route: String) {
    val rootId = tabRootDestinationId()
    navigate(route) {
        popUpTo(rootId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The diary is the root of the back stack (the onboarding pops itself when it navigates to the
 * diary). Falls back to the graph's start destination if the diary is not on the back stack.
 */
private fun NavController.tabRootDestinationId(): Int =
    try {
        getBackStackEntry(Routes.DIARY).destination.id
    } catch (e: IllegalArgumentException) {
        graph.findStartDestination().id
    }

// ---- argument definitions & helpers (absent optional values are -1L / "" / false, see [NavArgs]) ----

/** Argument definitions of the routes in [Routes] (shared by [AppNavHost] and the navigation tests). */
internal object NavArguments {
    private fun requiredLong(name: String) = navArgument(name) { type = NavType.LongType }
    private fun requiredString(name: String) = navArgument(name) { type = NavType.StringType }
    private fun optionalLong(name: String) = navArgument(name) {
        type = NavType.LongType
        defaultValue = NavArgs.ABSENT_LONG
    }
    private fun optionalString(name: String) = navArgument(name) {
        type = NavType.StringType
        defaultValue = ""
    }
    private fun flag(name: String) = navArgument(name) {
        type = NavType.BoolType
        defaultValue = false
    }

    val MEAL = listOf(requiredLong("date"), requiredString("meal"))
    val FOOD_SEARCH = listOf(optionalLong("date"), optionalString("meal"), flag("pick"))
    val BARCODE = listOf(optionalLong("date"), optionalString("meal"), flag("pick"))
    val AI_PHOTO = listOf(requiredLong("date"), requiredString("meal"))
    val FOOD_DETAIL = listOf(requiredLong("foodId"), optionalLong("date"), optionalString("meal"), flag("pick"))
    val CUSTOM_FOOD = listOf(
        optionalLong("foodId"),
        optionalString("barcode"),
        optionalLong("date"),
        optionalString("meal"),
        flag("pick"),
        optionalString("action"),
    )
    val RECIPE_EDIT = listOf(optionalLong("recipeId"))
    val RECIPE_DETAIL = listOf(requiredLong("recipeId"), optionalLong("date"), optionalString("meal"))
    val MEASUREMENT_HISTORY = listOf(requiredString("type"))
}

internal fun Bundle?.optDate(key: String): LocalDate? = NavArgs.date(this?.getLong(key, NavArgs.ABSENT_LONG))

internal fun Bundle?.optId(key: String): Long? = NavArgs.id(this?.getLong(key, NavArgs.ABSENT_LONG))

internal fun Bundle?.optMeal(key: String): MealType? = NavArgs.meal(this?.getString(key))

internal fun Bundle?.optString(key: String): String? = NavArgs.text(this?.getString(key))

internal fun Bundle?.flag(key: String): Boolean = this?.getBoolean(key, false) ?: false
