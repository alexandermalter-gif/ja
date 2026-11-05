package de.kalorienkompass.ui.progress

import android.app.Application
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.repo.ActivityRepository
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.MeasurementRepository
import de.kalorienkompass.data.repo.NoOpHealthDataExporter
import de.kalorienkompass.data.settings.SettingsRepository
import de.kalorienkompass.domain.TargetsUseCase
import de.kalorienkompass.ui.theme.KalorienTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** ProgressScreen / MeasurementHistoryScreen with their real ViewModels on an in-memory database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressScreensIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var measurements: MeasurementRepository
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settings = SettingsRepository(context, Json { ignoreUnknownKeys = true })
        measurements = MeasurementRepository(db, NoOpHealthDataExporter)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    @Test
    fun progressScreen_showsEmptyStateThenDataAndSavesNewBodyMeasurement() {
        val viewModel = ProgressViewModel(
            diaryRepository = DiaryRepository(db),
            measurementRepository = measurements,
            activityRepository = ActivityRepository(db),
            settingsRepository = settings,
            targetsUseCase = TargetsUseCase(settings, measurements),
        ).also { viewModels += it }
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                ProgressScreen(navController = rememberNavController(), viewModel = viewModel)
            }
        }
        compose.waitUntilExists("Gewicht eintragen")
        compose.onNodeWithText("Fortschritt").assertExists()

        runBlocking {
            measurements.setDailyManualValue(MeasurementType.WEIGHT, today.minusDays(3), 84.1)
            measurements.setDailyManualValue(MeasurementType.WEIGHT, today, 83.4)
        }
        compose.waitUntilExists("83,4")

        compose.onNodeWithText("1 J").performClick()
        compose.waitUntilExists("83,4")

        compose.onNodeWithContentDescription("Messwert hinzufügen").performClick()
        compose.onNodeWithText("Bauchumfang").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("91,5")
        compose.onNodeWithText("Speichern").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { db.measurementDao().getAll() }.any { it.type == MeasurementType.WAIST && it.value == 91.5 }
        }
        compose.onNode(hasScrollAction()).performScrollToKey("body_WAIST")
        compose.waitUntilExists("91,5")
    }

    @Test
    fun historyScreen_listsEntriesAndDeletesAfterConfirmation() {
        runBlocking {
            measurements.add(MeasurementType.WEIGHT, today.minusDays(1), 84.0)
            measurements.add(MeasurementType.WEIGHT, today, 83.5)
        }
        val viewModel = MeasurementHistoryViewModel(MeasurementType.WEIGHT, measurements, settings).also { viewModels += it }
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                MeasurementHistoryScreen(
                    navController = rememberNavController(),
                    type = MeasurementType.WEIGHT,
                    viewModel = viewModel,
                )
            }
        }
        compose.waitUntilExists("Gestern")
        compose.onNodeWithText("Heute").performClick()
        compose.onNodeWithText("Eintrag bearbeiten").assertExists()
        compose.onNodeWithContentDescription("Eintrag löschen").performClick()
        compose.onNodeWithText("Löschen").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { db.measurementDao().getAll() }.size == 1 }
        assertEquals(84.0, runBlocking { db.measurementDao().getAll() }.single().value, 1e-9)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Heute").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeContentTestRule.waitUntilExists(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
