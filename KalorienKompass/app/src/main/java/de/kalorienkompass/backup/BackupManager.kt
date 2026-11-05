package de.kalorienkompass.backup

import android.content.Context
import android.net.Uri
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.serialization.json.Json

/**
 * STUB (owned by PROFILE): JSON backup export/import and CSV exports.
 * All functions throw an Exception with a German message on error.
 * TODO(PROFILE): implement (see spec §11 PROFILE).
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    suspend fun exportJson(uri: Uri) {
        throw IllegalStateException("Der Backup-Export ist noch nicht verfügbar.")
    }

    /** Replaces all data. */
    suspend fun importJson(uri: Uri) {
        throw IllegalStateException("Der Backup-Import ist noch nicht verfügbar.")
    }

    suspend fun exportDiaryCsv(uri: Uri) {
        throw IllegalStateException("Der CSV-Export ist noch nicht verfügbar.")
    }

    suspend fun exportMeasurementsCsv(uri: Uri) {
        throw IllegalStateException("Der CSV-Export ist noch nicht verfügbar.")
    }

    suspend fun exportActivityCsv(uri: Uri) {
        throw IllegalStateException("Der CSV-Export ist noch nicht verfügbar.")
    }
}
