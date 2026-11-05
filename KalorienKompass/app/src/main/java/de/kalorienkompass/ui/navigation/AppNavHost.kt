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
            composable(
                route = Routes.MEAL,
                arguments = listOf(
                    navArgument("date") { type = NavType.LongType },
                    navArgument("meal") { type = NavType.StringType },
                ),
            ) { entry ->
                val args = entry.arguments
                MealDetailScreen(
                    navController,
                    args.optDate("date") ?: LocalDate.now(),
                    args.optMeal("meal") ?: MealType.forTimeOfDay(),
                )
            }

            // ---- food ----
            composable(
                route = Routes.FOOD_SEARCH,
                arguments = listOf(
                    navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                    navArgument("pick") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val args = entry.arguments
                FoodSearchScreen(
                    navController,
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }
            composable(
                route = Routes.BARCODE,
                arguments = listOf(
                    navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                    navArgument("pick") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val args = entry.arguments
                BarcodeScanScreen(
                    navController,
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }
            composable(
                route = Routes.AI_PHOTO,
                arguments = listOf(
                    navArgument("date") { type = NavType.LongType },
                    navArgument("meal") { type = NavType.StringType },
                ),
            ) { entry ->
                val args = entry.arguments
                AiPhotoScreen(
                    navController,
                    args.optDate("date") ?: LocalDate.now(),
                    args.optMeal("meal") ?: MealType.forTimeOfDay(),
                )
            }
            composable(
                route = Routes.FOOD_DETAIL,
                arguments = listOf(
                    navArgument("foodId") { type = NavType.LongType },
                    navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                    navArgument("pick") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val args = entry.arguments
                FoodDetailScreen(
                    navController,
                    args?.getLong("foodId", -1L) ?: -1L,
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }
            composable(
                route = Routes.CUSTOM_FOOD,
                arguments = listOf(
                    navArgument("foodId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("barcode") { type = NavType.StringType; defaultValue = "" },
                    navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                    navArgument("pick") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val args = entry.arguments
                CustomFoodEditScreen(
                    navController,
                    args.optId("foodId"),
                    args.optString("barcode"),
                    args.optDate("date"),
                    args.optMeal("meal"),
                    args.flag("pick"),
                )
            }

            // ---- recipes ----
            composable(
                route = Routes.RECIPE_EDIT,
                arguments = listOf(
                    navArgument("recipeId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                RecipeEditScreen(navController, entry.arguments.optId("recipeId"))
            }
            composable(
                route = Routes.RECIPE_DETAIL,
                arguments = listOf(
                    navArgument("recipeId") { type = NavType.LongType },
                    navArgument("date") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("meal") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments
                RecipeDetailScreen(
                    navController,
                    args?.getLong("recipeId", -1L) ?: -1L,
                    args.optDate("date"),
                    args.optMeal("meal"),
                )
            }

            // ---- progress ----
            composable(
                route = Routes.MEASUREMENT_HISTORY,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                ),
            ) { entry ->
                MeasurementHistoryScreen(
                    navController,
                    MeasurementType.fromName(entry.arguments?.getString("type")) ?: MeasurementType.WEIGHT,
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
                    if (currentRoute != tab.route) {
                        navController.navigate(tab.route) {
                            // The diary is always the root of the back stack (onboarding pops itself).
                            popUpTo(Routes.DIARY) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
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

// ---- argument helpers (absent optional values are -1L / "" / false) ----

private fun Bundle?.optDate(key: String): LocalDate? {
    val value = this?.getLong(key, -1L) ?: -1L
    return if (value == -1L) null else LocalDate.ofEpochDay(value)
}

private fun Bundle?.optId(key: String): Long? {
    val value = this?.getLong(key, -1L) ?: -1L
    return if (value <= 0L) null else value
}

private fun Bundle?.optMeal(key: String): MealType? = MealType.fromName(this?.getString(key))

private fun Bundle?.optString(key: String): String? = this?.getString(key)?.takeIf { it.isNotBlank() }

private fun Bundle?.flag(key: String): Boolean = this?.getBoolean(key, false) ?: false
