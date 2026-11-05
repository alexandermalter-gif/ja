package de.kalorienkompass.data.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.model.ActivityLevel
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.Sex
import de.kalorienkompass.data.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsRepositoryTest {

    private val json = settingsJson(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true })

    @Test
    fun decode_missingBlankOrCorruptValuesYieldDefaults() {
        val default = UserProfile()
        assertEquals(default, decodeSettingOrDefault(json, null, UserProfile.serializer(), default))
        assertEquals(default, decodeSettingOrDefault(json, "   ", UserProfile.serializer(), default))
        assertEquals(default, decodeSettingOrDefault(json, "{kaputt", UserProfile.serializer(), default))
        assertEquals(default, decodeSettingOrDefault(json, "[1,2]", UserProfile.serializer(), default))
        assertEquals(default, decodeSettingOrDefault(json, """{"birthYear": {"x": 1}}""", UserProfile.serializer(), default))
    }

    @Test
    fun decode_unknownKeysAndUnknownEnumValuesAreTolerated() {
        val profile = decodeSettingOrDefault(
            json,
            """{"onboardingDone": true, "sex": "DIVERS", "heightCm": 168, "activityLevel": "MODERATE", "neuesFeld": 1}""",
            UserProfile.serializer(),
            UserProfile(),
        )
        assertEquals(true, profile.onboardingDone)
        assertEquals(Sex.MALE, profile.sex) // unknown enum value → default
        assertEquals(168.0, profile.heightCm, 1e-9)
        assertEquals(ActivityLevel.MODERATE, profile.activityLevel)

        val settings = decodeSettingOrDefault(json, """{"aiModel": "GPT", "themeMode": "LIGHT"}""", AppSettings.serializer(), AppSettings())
        assertEquals(AiModel.OPUS, settings.aiModel)
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val goals = NutritionGoalSettings(customCalorieGoal = 1900, carbsPercent = 40, proteinPercent = 30, fatPercent = 30)
        val text = json.encodeToString(NutritionGoalSettings.serializer(), goals)
        assertEquals(goals, decodeSettingOrDefault(json, text, NutritionGoalSettings.serializer(), NutritionGoalSettings()))
    }

    /**
     * One test method on purpose: DataStore instances are process-wide singletons, so all
     * DataStore-backed checks share one store and start by resetting it.
     */
    @Test
    fun dataStoreRoundTripConcurrentUpdatesAndSecrets() = runBlocking {
        val repo = SettingsRepository(DbTestSupport.context, Json { ignoreUnknownKeys = true })
        repo.replaceAll(UserProfile(), NutritionGoalSettings(), AppSettings())
        repo.setAiApiKey(null)

        repo.updateProfile { it.copy(onboardingDone = true, name = "Kim", birthYear = 1988) }
        assertEquals(UserProfile(onboardingDone = true, name = "Kim", birthYear = 1988), repo.profile.first())

        // concurrent read-modify-write updates are never lost
        (1..25).map { async(Dispatchers.Default) { repo.updateSettings { it.copy(stepGoal = it.stepGoal + 1) } } }.awaitAll()
        assertEquals(AppSettings().stepGoal + 25, repo.settings.first().stepGoal)

        repo.updateGoalSettings { it.copy(customCalorieGoal = 2100) }
        assertEquals(2100, repo.goalSettings.first().customCalorieGoal)

        // the API key lives in its own store: trimmed, blank removes it, replaceAll keeps it
        repo.setAiApiKey("  sk-ant-test  ")
        assertEquals("sk-ant-test", repo.aiApiKey.first())
        repo.replaceAll(UserProfile(name = "Import"), NutritionGoalSettings(), AppSettings(themeMode = ThemeMode.SYSTEM))
        assertEquals("sk-ant-test", repo.aiApiKey.first())
        assertEquals("Import", repo.profile.first().name)
        assertEquals(ThemeMode.SYSTEM, repo.settings.first().themeMode)
        repo.setAiApiKey("   ")
        assertNull(repo.aiApiKey.first())

        // a second repository instance (same process) sees the same data
        assertEquals("Import", SettingsRepository(DbTestSupport.context, Json).profile.first().name)
    }
}
