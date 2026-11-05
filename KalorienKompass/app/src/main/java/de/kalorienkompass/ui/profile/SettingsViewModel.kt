package de.kalorienkompass.ui.profile

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.ai.FoodPhotoAnalyzer
import de.kalorienkompass.backup.BackupManager
import de.kalorienkompass.backup.BackupSummary
import de.kalorienkompass.data.model.AiModel
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class SettingsUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** stored Anthropic API key (null = none) */
    val storedKey: String? = null,
)

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Running : KeyTestState
    data object Success : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

/** Long-running data operations (only one at a time). */
enum class DataAction { EXPORT_JSON, IMPORT_JSON, CSV_DIARY, CSV_MEASUREMENTS, CSV_ACTIVITY }

/** A picked backup file waiting for the user's confirmation. */
data class PendingImport(val uri: Uri, val summary: BackupSummary)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val analyzer: FoodPhotoAnalyzer,
    private val backupManager: BackupManager,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        settingsRepository.aiApiKey,
    ) { settings, key -> SettingsUiState(loaded = true, settings = settings, storedKey = key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Text of the API key field (Compose state: text fields must update synchronously). */
    var keyInput: String by mutableStateOf("")
        private set
    private var keyInputTouched = false

    var keyTest: KeyTestState by mutableStateOf(KeyTestState.Idle)
        private set

    var busyAction: DataAction? by mutableStateOf(null)
        private set

    var pendingImport: PendingImport? by mutableStateOf(null)
        private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-off messages for the snackbar (German). */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val stored = settingsRepository.aiApiKey.first()
            if (!keyInputTouched) keyInput = stored.orEmpty()
        }
    }

    // ---- appearance & budget ----

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.updateSettings { it.copy(themeMode = mode) } }
    }

    fun setAddBurnedCalories(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateSettings { it.copy(addBurnedCalories = enabled) } }
    }

    // ---- AI ----

    fun onKeyInputChange(value: String) {
        keyInputTouched = true
        // API keys never contain whitespace; pasted keys often end with a line break.
        keyInput = value.filterNot { it.isWhitespace() }
        if (keyTest !is KeyTestState.Running) keyTest = KeyTestState.Idle
    }

    fun setModel(model: AiModel) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(aiModel = model) }
            if (keyTest !is KeyTestState.Running) keyTest = KeyTestState.Idle
        }
    }

    fun saveKey() {
        val key = keyInput.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            try {
                settingsRepository.setAiApiKey(key)
                keyTest = KeyTestState.Idle
                messageChannel.send("API-Schlüssel gespeichert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.send("Der Schlüssel konnte nicht gespeichert werden.")
            }
        }
    }

    fun removeKey() {
        viewModelScope.launch {
            try {
                settingsRepository.setAiApiKey(null)
                keyInputTouched = true
                keyInput = ""
                keyTest = KeyTestState.Idle
                messageChannel.send("API-Schlüssel entfernt")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.send("Der Schlüssel konnte nicht entfernt werden.")
            }
        }
    }

    /** Saves a changed key first, then sends a tiny test request with the selected model. */
    fun testKey() {
        if (keyTest is KeyTestState.Running) return
        keyTest = KeyTestState.Running
        viewModelScope.launch {
            keyTest = try {
                val input = keyInput.trim()
                val stored = settingsRepository.aiApiKey.first()
                if (input.isNotEmpty() && input != stored) settingsRepository.setAiApiKey(input)
                if (input.isEmpty() && stored == null) {
                    KeyTestState.Failed("Bitte zuerst einen API-Schlüssel eingeben.")
                } else {
                    analyzer.testApiKey().fold(
                        onSuccess = { KeyTestState.Success },
                        onFailure = { e ->
                            KeyTestState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Der Schlüssel konnte nicht geprüft werden.")
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyTestState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Der Schlüssel konnte nicht geprüft werden.")
            }
        }
    }

    // ---- data ----

    fun exportBackup(uri: Uri) = runAction(DataAction.EXPORT_JSON) {
        backupManager.exportJson(uri)
        "Backup gespeichert"
    }

    fun exportDiaryCsv(uri: Uri) = runAction(DataAction.CSV_DIARY) {
        backupManager.exportDiaryCsv(uri)
        "Tagebuch als CSV gespeichert"
    }

    fun exportMeasurementsCsv(uri: Uri) = runAction(DataAction.CSV_MEASUREMENTS) {
        backupManager.exportMeasurementsCsv(uri)
        "Körperwerte als CSV gespeichert"
    }

    fun exportActivityCsv(uri: Uri) = runAction(DataAction.CSV_ACTIVITY) {
        backupManager.exportActivityCsv(uri)
        "Schritte als CSV gespeichert"
    }

    /** Reads the picked file and asks for confirmation (nothing is changed yet). */
    fun onImportFilePicked(uri: Uri) = runAction(DataAction.IMPORT_JSON) {
        pendingImport = PendingImport(uri, backupManager.readBackupSummary(uri))
        null
    }

    fun confirmImport() {
        val pending = pendingImport ?: return
        pendingImport = null
        runAction(DataAction.IMPORT_JSON) {
            val summary = backupManager.restore(pending.uri)
            if (summary.isEmpty) {
                "Backup importiert (es enthielt keine Einträge)."
            } else {
                "Backup importiert: " + listOf(
                    countLabel(summary.diaryEntries, "Tagebucheintrag", "Tagebucheinträge"),
                    countLabel(summary.measurements, "Körperwert", "Körperwerte"),
                    countLabel(summary.recipes, "Rezept", "Rezepte"),
                ).joinToString(", ")
            }
        }
    }

    fun dismissImport() {
        pendingImport = null
    }

    /** Shows [message] in the snackbar (e.g. errors from the screen itself). */
    fun showMessage(message: String) {
        viewModelScope.launch { messageChannel.send(message) }
    }

    /** Runs [block] (which returns the success message or null) unless another action is running. */
    private fun runAction(action: DataAction, block: suspend () -> String?) {
        if (busyAction != null) return
        busyAction = action
        viewModelScope.launch {
            try {
                block()?.let { messageChannel.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.send(e.message?.takeIf { it.isNotBlank() } ?: "Unbekannter Fehler")
            } finally {
                busyAction = null
            }
        }
    }
}
