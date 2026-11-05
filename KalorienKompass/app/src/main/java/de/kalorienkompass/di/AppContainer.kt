package de.kalorienkompass.di

import android.content.Context
import de.kalorienkompass.KalorienApp
import de.kalorienkompass.ai.FoodPhotoAnalyzer
import de.kalorienkompass.ai.lookup.NutritionLabelReader
import de.kalorienkompass.ai.lookup.ProductWebSearch
import de.kalorienkompass.backup.BackupManager
import de.kalorienkompass.data.builtin.BuiltinFoodSeeder
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.remote.FoodRemoteDataSource
import de.kalorienkompass.data.remote.OpenFoodFactsDataSource
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import de.kalorienkompass.data.repo.HealthDataExporter
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.repo.RecipeRepository
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.domain.TargetsUseCase
import de.kalorienkompass.health.HealthConnectManager
import de.kalorienkompass.health.HealthSyncService
import de.kalorienkompass.health.HealthWeightExporter
import de.kalorienkompass.health.StepSensorTracker
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency injection: one instance per process, created in [KalorienApp.onCreate]. */
class AppContainer(val context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val database: AppDatabase = AppDatabase.build(context)

    val settingsRepository: SettingsRepository = SettingsRepository(context, json)

    val foodRemoteDataSource: FoodRemoteDataSource = OpenFoodFactsDataSource(okHttpClient, json)

    val foodRepository: FoodRepository = FoodRepository(database, foodRemoteDataSource)

    val diaryRepository: DiaryRepository = DiaryRepository(database)

    val healthConnectManager: HealthConnectManager = HealthConnectManager(context)

    val healthWeightExporter: HealthDataExporter = HealthWeightExporter(healthConnectManager, settingsRepository)

    val measurementRepository: MeasurementRepository = MeasurementRepository(database, healthWeightExporter)

    val activityRepository: ActivityRepository = ActivityRepository(database)

    val recipeRepository: RecipeRepository = RecipeRepository(database)

    val targetsUseCase: TargetsUseCase = TargetsUseCase(settingsRepository, measurementRepository)

    val healthSyncService: HealthSyncService =
        HealthSyncService(healthConnectManager, settingsRepository, activityRepository, measurementRepository)

    val stepSensorTracker: StepSensorTracker = StepSensorTracker(context, activityRepository, settingsRepository)

    val foodPhotoAnalyzer: FoodPhotoAnalyzer = FoodPhotoAnalyzer(settingsRepository, json)

    /** Reads a photo of a package's nutrition table (spec §13). */
    val nutritionLabelReader: NutritionLabelReader = NutritionLabelReader(settingsRepository, json)

    /** Researches unknown products on the web by barcode/name (spec §13). */
    val productWebSearch: ProductWebSearch = ProductWebSearch(settingsRepository, json)

    val backupManager: BackupManager = BackupManager(context, database, settingsRepository, json)

    val builtinFoodSeeder: BuiltinFoodSeeder = BuiltinFoodSeeder(context, database, json)
}

/** The process-wide [AppContainer]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as KalorienApp).container
