package de.kalorienkompass.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import de.kalorienkompass.BuildConfig
import de.kalorienkompass.KalorienApp
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.db.RecipeEntity
import de.kalorienkompass.data.db.RecipeIngredientEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * JSON backup export/import (all data except the API key) and CSV exports.
 *
 * All functions run on [Dispatchers.IO] and throw an [Exception] (a [BackupException]) with a
 * German, user-facing message on error.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    json: Json,
) {
    private val backupJson: Json = BackupCodec.configure(json)

    /** Writes a complete JSON backup to [uri]. */
    suspend fun exportJson(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = db.withTransaction {
                    Snapshot(
                        foods = db.foodDao().getAll(),
                        recipes = db.recipeDao().getAllRecipes(),
                        ingredients = db.recipeDao().getAllIngredients(),
                        diary = db.diaryDao().getAll(),
                        measurements = db.measurementDao().getAll(),
                        activity = db.activityDao().getAll(),
                    )
                }
                val file = BackupExport.build(
                    foods = snapshot.foods,
                    recipes = snapshot.recipes,
                    ingredients = snapshot.ingredients,
                    diary = snapshot.diary,
                    measurements = snapshot.measurements,
                    activity = snapshot.activity,
                    profile = settings.profile.first(),
                    goals = settings.goalSettings.first(),
                    settings = settings.settings.first(),
                    exportedAt = BackupCodec.timestamp(),
                    appVersion = BuildConfig.VERSION_NAME,
                )
                writeText(uri, BackupCodec.encode(file, backupJson))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                throw e
            } catch (e: IOException) {
                throw BackupException(MSG_WRITE_FAILED, e)
            } catch (e: Exception) {
                throw BackupException("Das Backup konnte nicht erstellt werden (${e.describe()}).", e)
            }
        }
    }

    /** Reads and validates the backup at [uri] without changing anything (for a confirmation dialog). */
    suspend fun readBackupSummary(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        BackupCodec.summaryOf(readBackup(uri))
    }

    /** Replaces all data with the backup at [uri]. Built-in foods stay; the API key is not touched. */
    suspend fun importJson(uri: Uri) {
        restore(uri)
    }

    /**
     * Same as [importJson], returns what was imported. Either everything is imported or (on error)
     * the database stays unchanged.
     */
    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val backup = readBackup(uri)
        ensureBuiltinFoods()
        // Once started, the import must not be interrupted half-way (e.g. by leaving the screen).
        withContext(NonCancellable) {
            try {
                db.withTransaction {
                    val foodDao = db.foodDao()
                    val recipeDao = db.recipeDao()
                    val plan = BackupImport.plan(backup, foodDao.getBySource(FoodSource.BUILTIN))

                    db.diaryDao().deleteAll()
                    db.measurementDao().deleteAll()
                    db.activityDao().deleteAll()
                    recipeDao.deleteAllIngredients()
                    recipeDao.deleteAllRecipes() // also cascades to the ingredients
                    foodDao.deleteAllExceptSource(FoodSource.BUILTIN)

                    plan.builtinUpdates.forEach { foodDao.update(it) }
                    if (plan.foods.isNotEmpty()) foodDao.insertAll(plan.foods)
                    plan.recipes.forEach { recipeDao.insertRecipe(it) }
                    if (plan.ingredients.isNotEmpty()) recipeDao.insertIngredients(plan.ingredients)
                    if (plan.diary.isNotEmpty()) db.diaryDao().insertAll(plan.diary)
                    if (plan.measurements.isNotEmpty()) db.measurementDao().insertAll(plan.measurements)
                    if (plan.activity.isNotEmpty()) db.activityDao().upsertAll(plan.activity)
                    if (plan.droppedIngredients > 0) {
                        Log.w(TAG, "Import: ${plan.droppedIngredients} ingredient(s) without food skipped")
                    }
                }
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException(
                    "Der Import ist fehlgeschlagen (${e.describe()}). Deine bisherigen Daten bleiben unverändert.",
                    e,
                )
            }

            try {
                // Health Connect / sensor switches depend on permissions of THIS device: keep them.
                val current = settings.settings.first()
                settings.replaceAll(
                    profile = backup.profile.copy(onboardingDone = true),
                    goals = backup.goals,
                    settings = backup.settings.copy(
                        healthConnectEnabled = current.healthConnectEnabled,
                        writeWeightToHealthConnect = current.writeWeightToHealthConnect,
                        stepSensorEnabled = current.stepSensorEnabled,
                        lastHealthSyncAt = current.lastHealthSyncAt,
                    ),
                )
            } catch (e: Exception) {
                throw BackupException(
                    "Die Daten wurden importiert, aber Profil und Einstellungen konnten nicht übernommen werden.",
                    e,
                )
            }
        }
        BackupCodec.summaryOf(backup)
    }

    /** All diary entries as CSV (`;`, decimal comma, UTF-8 with BOM). */
    suspend fun exportDiaryCsv(uri: Uri) {
        exportCsv(uri) { BackupCsv.diary(db.diaryDao().getAll()) }
    }

    /** All body values (weight, body fat, circumferences) as CSV. */
    suspend fun exportMeasurementsCsv(uri: Uri) {
        exportCsv(uri) { BackupCsv.measurements(db.measurementDao().getAll()) }
    }

    /** Daily steps / active kcal as CSV. */
    suspend fun exportActivityCsv(uri: Uri) {
        exportCsv(uri) { BackupCsv.activity(db.activityDao().getAll()) }
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun exportCsv(uri: Uri, build: suspend () -> String) {
        withContext(Dispatchers.IO) {
            try {
                writeText(uri, build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                throw e
            } catch (e: IOException) {
                throw BackupException(MSG_WRITE_FAILED, e)
            } catch (e: Exception) {
                throw BackupException("Der CSV-Export ist fehlgeschlagen (${e.describe()}).", e)
            }
        }
    }

    private fun readBackup(uri: Uri): BackupFile {
        val text = try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw BackupException(MSG_READ_FAILED)
            input.use { readLimited(it) }.toString(Charsets.UTF_8)
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            throw BackupException(MSG_READ_FAILED, e)
        } catch (e: IOException) {
            throw BackupException(MSG_READ_FAILED, e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(MSG_READ_FAILED, e)
        }
        return BackupCodec.decode(text, backupJson)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BACKUP_BYTES) {
                throw BackupException("Die Datei ist zu groß für ein KalorienKompass-Backup.")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeText(uri: Uri, text: String) {
        val resolver = context.contentResolver
        // "wt" truncates an existing file; not every document provider supports it.
        val stream: OutputStream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } ?: try {
            resolver.openOutputStream(uri, "w")
        } catch (e: SecurityException) {
            throw BackupException(MSG_WRITE_FAILED, e)
        } ?: throw BackupException(MSG_WRITE_FAILED)

        stream.use { out ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(bytes)
            out.flush()
        }
    }

    /** Built-in foods must exist before references to them can be remapped (fresh install). */
    private suspend fun ensureBuiltinFoods() {
        try {
            val app = context.applicationContext as? KalorienApp ?: return
            app.container.builtinFoodSeeder.seedIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Built-in foods could not be checked before import", e)
        }
    }

    private fun Throwable.describe(): String = localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private class Snapshot(
        val foods: List<FoodEntity>,
        val recipes: List<RecipeEntity>,
        val ingredients: List<RecipeIngredientEntity>,
        val diary: List<DiaryEntryEntity>,
        val measurements: List<MeasurementEntity>,
        val activity: List<DailyActivityEntity>,
    )

    companion object {
        private const val TAG = "BackupManager"
        private const val MAX_BACKUP_BYTES = 50L * 1024 * 1024

        const val MIME_JSON: String = "application/json"
        const val MIME_CSV: String = "text/csv"

        /** MIME types offered when picking a backup file (some providers do not know .json). */
        val IMPORT_MIME_TYPES: Array<String> = arrayOf("application/json", "application/octet-stream", "text/plain")

        private const val MSG_WRITE_FAILED =
            "Die Datei konnte nicht gespeichert werden. Bitte wähle einen anderen Speicherort."
        private const val MSG_READ_FAILED = "Die Datei konnte nicht gelesen werden."

        /** "kalorienkompass-backup-2026-11-05.json" */
        fun backupFileName(date: LocalDate = LocalDate.now()): String = "kalorienkompass-backup-$date.json"

        fun diaryCsvFileName(date: LocalDate = LocalDate.now()): String = "kalorienkompass-tagebuch-$date.csv"

        fun measurementsCsvFileName(date: LocalDate = LocalDate.now()): String = "kalorienkompass-koerperwerte-$date.csv"

        fun activityCsvFileName(date: LocalDate = LocalDate.now()): String = "kalorienkompass-schritte-$date.csv"
    }
}
