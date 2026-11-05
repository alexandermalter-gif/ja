package de.kalorienkompass.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** General settings (included in Android backup): file `files/datastore/settings.preferences_pb`. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsRepository.SETTINGS_STORE_NAME,
    // A corrupt file (e.g. after a crash while writing) is replaced by defaults instead of failing every read/write.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Secrets (API key): file `files/datastore/secrets.preferences_pb`, excluded from Android backup
 * (res/xml/backup_rules.xml, res/xml/data_extraction_rules.xml) and from the JSON export.
 */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsRepository.SECRETS_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Each settings object is stored as ONE JSON string preference. Decoding failures fall back to defaults.
 */
class SettingsRepository(context: Context, json: Json) {

    private val appContext: Context = context.applicationContext
    private val store: DataStore<Preferences> = appContext.settingsDataStore
    private val secrets: DataStore<Preferences> = appContext.secretsDataStore

    private val settingsJson: Json = settingsJson(json)

    private val storeData: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val profile: Flow<UserProfile> = storeData
        .map { decode(it[KEY_PROFILE], UserProfile.serializer(), UserProfile()) }
        .distinctUntilChanged()

    val goalSettings: Flow<NutritionGoalSettings> = storeData
        .map { decode(it[KEY_GOALS], NutritionGoalSettings.serializer(), NutritionGoalSettings()) }
        .distinctUntilChanged()

    val settings: Flow<AppSettings> = storeData
        .map { decode(it[KEY_SETTINGS], AppSettings.serializer(), AppSettings()) }
        .distinctUntilChanged()

    /** null if no key is stored. */
    val aiApiKey: Flow<String?> = secrets.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_API_KEY]?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()

    suspend fun updateProfile(transform: (UserProfile) -> UserProfile) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_PROFILE], UserProfile.serializer(), UserProfile())
            prefs[KEY_PROFILE] = settingsJson.encodeToString(UserProfile.serializer(), transform(current))
        }
    }

    suspend fun updateGoalSettings(transform: (NutritionGoalSettings) -> NutritionGoalSettings) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_GOALS], NutritionGoalSettings.serializer(), NutritionGoalSettings())
            prefs[KEY_GOALS] = settingsJson.encodeToString(NutritionGoalSettings.serializer(), transform(current))
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_SETTINGS], AppSettings.serializer(), AppSettings())
            prefs[KEY_SETTINGS] = settingsJson.encodeToString(AppSettings.serializer(), transform(current))
        }
    }

    /** null or blank removes the key. */
    suspend fun setAiApiKey(key: String?) {
        val trimmed = key?.trim().orEmpty()
        secrets.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_API_KEY)
            } else {
                prefs[KEY_API_KEY] = trimmed
            }
        }
    }

    /** Replaces profile, goals and settings at once (backup import). The API key is not touched. */
    suspend fun replaceAll(profile: UserProfile, goals: NutritionGoalSettings, settings: AppSettings) {
        store.edit { prefs ->
            prefs[KEY_PROFILE] = settingsJson.encodeToString(UserProfile.serializer(), profile)
            prefs[KEY_GOALS] = settingsJson.encodeToString(NutritionGoalSettings.serializer(), goals)
            prefs[KEY_SETTINGS] = settingsJson.encodeToString(AppSettings.serializer(), settings)
        }
    }

    private fun <T> decode(raw: String?, serializer: KSerializer<T>, default: T): T =
        decodeSettingOrDefault(settingsJson, raw, serializer, default)

    companion object {
        /** DataStore file names (`files/datastore/<name>.preferences_pb`). */
        const val SETTINGS_STORE_NAME = "settings"
        const val SECRETS_STORE_NAME = "secrets"

        private val KEY_PROFILE = stringPreferencesKey("user_profile")
        private val KEY_GOALS = stringPreferencesKey("nutrition_goals")
        private val KEY_SETTINGS = stringPreferencesKey("app_settings")
        private val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
    }
}

/** Json used for the stored settings objects: unknown keys ignored, defaults encoded, invalid enum values → defaults. */
internal fun settingsJson(base: Json): Json = Json(from = base) {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/** Decodes one stored settings object; a missing, blank or undecodable value yields [default]. */
internal fun <T> decodeSettingOrDefault(json: Json, raw: String?, serializer: KSerializer<T>, default: T): T {
    if (raw.isNullOrBlank()) return default
    return try {
        json.decodeFromString(serializer, raw)
    } catch (e: Exception) {
        // SerializationException / IllegalArgumentException for corrupt or incompatible JSON
        default
    }
}
