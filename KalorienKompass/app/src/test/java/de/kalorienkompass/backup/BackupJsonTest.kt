package de.kalorienkompass.backup

import de.kalorienkompass.backup.BackupFixtures.aiPasta
import de.kalorienkompass.backup.BackupFixtures.apple
import de.kalorienkompass.backup.BackupFixtures.banana
import de.kalorienkompass.backup.BackupFixtures.cake
import de.kalorienkompass.backup.BackupFixtures.oatDrink
import de.kalorienkompass.backup.BackupFixtures.rice
import de.kalorienkompass.data.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class BackupJsonTest {

    @Test
    fun export_containsOnlyNonBuiltinFoods_andReferencedBuiltins() {
        val file = BackupFixtures.backup()

        assertEquals(BackupFile.FORMAT_ID, file.format)
        assertEquals(BackupFile.CURRENT_VERSION, file.version)
        assertEquals(listOf(250L, 251L, 252L), file.foods.map { it.id })
        assertTrue(file.foods.none { it.source == FoodSource.BUILTIN })
        // apple: used in the diary + favorite; rice: recipe ingredient; banana: neither
        assertEquals(listOf("Apfel", "Reis, gekocht"), file.builtinFoods!!.map { it.name })
        val appleRef = file.builtinFoods!!.first()
        assertTrue(appleRef.isFavorite)
        assertEquals(3, appleRef.useCount)
        assertEquals(listOf("Reis, gekocht", "Haferdrink"), file.recipeIngredients.map { it.foodName })
    }

    @Test
    fun json_roundTrip_restoresIdenticalBackup() {
        val file = BackupFixtures.backup()

        val text = BackupCodec.encode(file)
        val decoded = BackupCodec.decode(text)

        assertEquals(file, decoded)
    }

    @Test
    fun json_hasDocumentedLayout() {
        val text = BackupCodec.encode(BackupFixtures.backup())

        assertTrue(text.startsWith("{\"format\":\"kalorienkompass-backup\",\"version\":1,"))
        assertTrue(text.contains("\"exportedAt\":\"2026-11-05T14:05:00+01:00\""))
        // dates as ISO strings, enums by name
        assertTrue(text.contains("\"date\":\"2026-11-03\""))
        assertTrue(text.contains("\"meal\":\"BREAKFAST\""))
        assertTrue(text.contains("\"type\":\"WEIGHT\""))
        for (key in listOf("profile", "goals", "settings", "foods", "recipes", "recipeIngredients", "diary", "measurements", "activity")) {
            assertTrue("missing key $key", text.contains("\"$key\":"))
        }
        // the API key is never part of a backup
        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("sk-ant"))
    }

    @Test
    fun entityDtoRoundTrip_isLossless() {
        for (food in listOf(cake, oatDrink, aiPasta, apple)) assertEquals(food, food.toDto().toEntity())
        assertEquals(BackupFixtures.recipe, BackupFixtures.recipe.toDto().toEntity())
        BackupFixtures.ingredients.forEach { assertEquals(it, it.toDto().toEntity()) }
        BackupFixtures.diary.forEach { assertEquals(it, it.toDto().toEntity()) }
        BackupFixtures.measurements.forEach { assertEquals(it, it.toDto().toEntity()) }
        BackupFixtures.activity.forEach { assertEquals(it, it.toDto().toEntity()) }
    }

    @Test
    fun import_onSameDevice_restoresEverythingUnchanged() {
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupFixtures.backup()))
        // the database after deleting all non-built-in data: built-ins without user state
        val builtinsNow = listOf(apple.copy(isFavorite = false, lastUsedAt = null, useCount = 0), rice, banana.copy(isFavorite = true))

        val plan = BackupImport.plan(decoded, builtinsNow)

        assertEquals(listOf(cake, oatDrink, aiPasta), plan.foods)
        assertEquals(listOf(BackupFixtures.recipe), plan.recipes)
        assertEquals(BackupFixtures.ingredients, plan.ingredients)
        assertEquals(BackupFixtures.diary, plan.diary)
        assertEquals(BackupFixtures.measurements, plan.measurements)
        assertEquals(BackupFixtures.activity, plan.activity)
        assertEquals(0, plan.droppedIngredients)
        // favorite/usage of built-in foods comes from the backup: apple restored, banana reset
        assertEquals(listOf(apple, banana.copy(isFavorite = false)), plan.builtinUpdates)
    }

    @Test
    fun import_onOtherDevice_remapsBuiltinsByName_andMovesCollidingIds() {
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupFixtures.backup()))
        // other app version: different built-in ids, one of them collides with the cached Haferdrink (251)
        val builtinsNow = listOf(
            apple.copy(id = 40, isFavorite = false, lastUsedAt = null, useCount = 0),
            rice.copy(id = 251),
            banana.copy(id = 41),
        )

        val plan = BackupImport.plan(decoded, builtinsNow)

        val oat = plan.foods.single { it.name == "Haferdrink" }
        assertEquals(253L, oat.id) // max(251, 252) + 1
        assertEquals(oatDrink.copy(id = 253L), oat)
        assertEquals(250L, plan.foods.single { it.name == "Omas Kuchen" }.id)

        val byIngredientId = plan.ingredients.associateBy { it.id }
        assertEquals(251L, byIngredientId.getValue(11).foodId) // rice, matched by name
        assertEquals(253L, byIngredientId.getValue(12).foodId) // moved Haferdrink

        val diaryById = plan.diary.associateBy { it.id }
        assertEquals(40L, diaryById.getValue(21).foodId) // apple, matched by name
        assertEquals(253L, diaryById.getValue(24).foodId)
        assertEquals(7L, diaryById.getValue(22).recipeId)

        assertEquals(listOf(apple.copy(id = 40)), plan.builtinUpdates)
    }

    @Test
    fun import_dropsIngredientsWithoutFood_andClearsDanglingReferences() {
        val base = BackupFixtures.backup()
        val damaged = base.copy(
            recipeIngredients = base.recipeIngredients +
                RecipeIngredientDto(id = 13, recipeId = 7, foodId = 999, grams = 50.0) +
                RecipeIngredientDto(id = 14, recipeId = 99, foodId = 250, grams = 10.0), // unknown recipe
            diary = base.diary.map { if (it.id == 21L) it.copy(foodId = 999, recipeId = 98) else it },
        )

        val plan = BackupImport.plan(damaged, BackupFixtures.builtins)

        assertEquals(listOf(11L, 12L), plan.ingredients.map { it.id })
        assertEquals(1, plan.droppedIngredients)
        val entry = plan.diary.single { it.id == 21L }
        assertNull(entry.foodId)
        assertNull(entry.recipeId)
        assertEquals(78.0, entry.kcal, 0.0) // totals stay
    }

    @Test
    fun import_withoutBuiltinInfo_keepsExistingBuiltinIds() {
        val foreign = BackupFixtures.backup().copy(builtinFoods = null)

        val plan = BackupImport.plan(foreign, BackupFixtures.builtins)

        assertEquals(2L, plan.ingredients.single { it.id == 11L }.foodId)
        assertEquals(1L, plan.diary.single { it.id == 21L }.foodId)
        assertTrue(plan.builtinUpdates.isEmpty())
    }

    @Test
    fun import_resolvesDuplicateIdsAndBarcodes() {
        val base = BackupFixtures.backup()
        val copyOfOat = base.foods.single { it.id == 251L }.copy(name = "Haferdrink Kopie")
        val damaged = base.copy(
            foods = base.foods + copyOfOat,
            diary = base.diary + base.diary.first().copy(name = "Doppelt"),
        )

        val plan = BackupImport.plan(damaged, BackupFixtures.builtins)

        assertEquals(plan.foods.size, plan.foods.map { it.id }.toSet().size)
        val copy = plan.foods.single { it.name == "Haferdrink Kopie" }
        assertEquals(253L, copy.id)
        assertNull(copy.barcode) // barcode is unique in the database
        assertEquals(0L, plan.diary.single { it.name == "Doppelt" }.id) // gets a new id from Room
    }

    @Test
    fun import_invalidDate_throwsGermanBackupException() {
        val base = BackupFixtures.backup()
        val damaged = base.copy(measurements = base.measurements.map { it.copy(date = "2026-13-45") })

        val e = assertThrows(BackupException::class.java) { BackupImport.plan(damaged, BackupFixtures.builtins) }
        assertTrue(e.message!!.contains("ungültiges Datum"))
    }

    @Test
    fun decode_rejectsFilesThatAreNoBackup() {
        for (text in listOf("not json", "[1, 2, 3]", "{}", "{\"format\":\"something-else\",\"version\":1}", "\"text\"")) {
            val e = assertThrows(BackupException::class.java) { BackupCodec.decode(text) }
            assertEquals(text, BackupCodec.MSG_NOT_A_BACKUP, e.message)
        }
        val empty = assertThrows(BackupException::class.java) { BackupCodec.decode("   ") }
        assertEquals("Die ausgewählte Datei ist leer.", empty.message)
    }

    @Test
    fun decode_rejectsNewerAndBrokenFiles() {
        val newer = assertThrows(BackupException::class.java) {
            BackupCodec.decode("{\"format\":\"kalorienkompass-backup\",\"version\":2}")
        }
        assertTrue(newer.message!!.contains("neueren Version"))

        val noVersion = assertThrows(BackupException::class.java) {
            BackupCodec.decode("{\"format\":\"kalorienkompass-backup\"}")
        }
        assertEquals(BackupCodec.MSG_CORRUPT, noVersion.message)

        val broken = assertThrows(BackupException::class.java) {
            BackupCodec.decode("{\"format\":\"kalorienkompass-backup\",\"version\":1,\"diary\":\"kaputt\"}")
        }
        assertEquals(BackupCodec.MSG_CORRUPT, broken.message)
    }

    @Test
    fun decode_acceptsBomUnknownFieldsAndMissingSections() {
        val text = "﻿{\"format\":\"kalorienkompass-backup\",\"version\":1," +
            "\"exportedAt\":\"2026-11-05T14:05:00+01:00\",\"fieldFromTheFuture\":42," +
            "\"diary\":[{\"date\":\"2026-11-03\",\"name\":\"Brot\",\"kcal\":240.0,\"extra\":true}]}"

        val file = BackupCodec.decode(text)

        assertEquals(1, file.diary.size)
        assertEquals("Brot", file.diary.single().name)
        assertTrue(file.foods.isEmpty())
        assertNull(file.builtinFoods)
        val summary = BackupCodec.summaryOf(file)
        assertEquals(OffsetDateTime.of(2026, 11, 5, 14, 5, 0, 0, ZoneOffset.ofHours(1)).toInstant().toEpochMilli(), summary.exportedAtMillis)
        assertEquals(1, summary.diaryEntries)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun summary_countsAllSections() {
        val summary = BackupCodec.summaryOf(BackupFixtures.backup())

        assertEquals(3, summary.foods)
        assertEquals(1, summary.recipes)
        assertEquals(4, summary.diaryEntries)
        assertEquals(2, summary.measurements)
        assertEquals(2, summary.activityDays)
        assertEquals("1.0.0", summary.appVersion)
    }

    @Test
    fun timestamp_isIsoWithOffsetAndSeconds() {
        val time = OffsetDateTime.of(2026, 11, 5, 14, 5, 0, 123_000_000, ZoneOffset.ofHours(1))
        assertEquals("2026-11-05T14:05:00+01:00", BackupCodec.timestamp(time))
        assertNull(BackupCodec.parseTimestamp("gestern"))
        assertNull(BackupCodec.parseTimestamp(""))
        assertEquals(0L, BackupCodec.parseTimestamp("1970-01-01T00:00:00Z"))
    }

    @Test
    fun fileNames_containTheDate() {
        val date = java.time.LocalDate.of(2026, 11, 5)
        assertEquals("kalorienkompass-backup-2026-11-05.json", BackupManager.backupFileName(date))
        assertEquals("kalorienkompass-tagebuch-2026-11-05.csv", BackupManager.diaryCsvFileName(date))
        assertEquals("kalorienkompass-koerperwerte-2026-11-05.csv", BackupManager.measurementsCsvFileName(date))
        assertEquals("kalorienkompass-schritte-2026-11-05.csv", BackupManager.activityCsvFileName(date))
    }
}
