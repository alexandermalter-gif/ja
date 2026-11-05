package de.kalorienkompass.backup

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.model.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupCsvTest {

    private fun lines(csv: String): List<String> {
        assertTrue("BOM missing", csv.startsWith("﻿"))
        assertTrue("must end with CRLF", csv.endsWith("\r\n"))
        return csv.removePrefix("﻿").removeSuffix("\r\n").split("\r\n")
    }

    @Test
    fun escape_quotesOnlyWhenNeeded() {
        assertEquals("Apfel", BackupCsv.escape("Apfel"))
        assertEquals("Reis, gekocht", BackupCsv.escape("Reis, gekocht"))
        assertEquals("\"Brot; Vollkorn\"", BackupCsv.escape("Brot; Vollkorn"))
        assertEquals("\"Sag \"\"Hallo\"\"\"", BackupCsv.escape("Sag \"Hallo\""))
        assertEquals("\"Zeile 1\nZeile 2\"", BackupCsv.escape("Zeile 1\nZeile 2"))
        assertEquals("\" Leerzeichen\"", BackupCsv.escape(" Leerzeichen"))
        assertEquals("", BackupCsv.escape(""))
    }

    @Test
    fun number_usesDecimalCommaWithoutThousandsSeparator() {
        assertEquals("1234,5", BackupCsv.number(1234.5, 1))
        assertEquals("12", BackupCsv.number(12.0, 1))
        assertEquals("0,25", BackupCsv.number(0.25, 2))
        assertEquals("0,3", BackupCsv.number(0.25, 1)) // half up
        assertEquals("321", BackupCsv.number(320.5, 0))
        assertEquals("0", BackupCsv.number(-0.01, 1)) // no "-0"
        assertEquals("", BackupCsv.number(null))
        assertEquals("", BackupCsv.number(Double.NaN))
    }

    @Test
    fun text_preventsFormulaInjection() {
        assertEquals("'=SUMME(A1:A9)", BackupCsv.text("=SUMME(A1:A9)"))
        assertEquals("'+49 123", BackupCsv.text("+49 123"))
        assertEquals("'-Joghurt", BackupCsv.text("-Joghurt"))
        assertEquals("'@cmd", BackupCsv.text("@cmd"))
        assertEquals("Müsli", BackupCsv.text("  Müsli "))
        assertEquals("", BackupCsv.text(null))
    }

    @Test
    fun document_hasBomHeaderSemicolonsAndCrlf() {
        val csv = BackupCsv.document(listOf("A", "B"), listOf(listOf("1", "x;y"), listOf("", "z")))
        assertEquals("﻿A;B\r\n1;\"x;y\"\r\n;z\r\n", csv)
    }

    @Test
    fun diary_rowsAreSortedAndFormatted() {
        val d1 = LocalDate.of(2026, 11, 3)
        val d2 = LocalDate.of(2026, 11, 4)
        val entries = listOf(
            DiaryEntryEntity(
                id = 3, date = d2, meal = MealType.LUNCH, name = "Haferdrink", brand = "Oatly", amount = 250.0,
                unitLabel = "ml", grams = 250.0, kcal = 115.0, protein = 2.5, carbs = 16.75, fat = 3.75,
                foodId = 9, source = EntrySource.FOOD, createdAt = 3,
            ),
            DiaryEntryEntity(
                id = 2, date = d1, meal = MealType.DINNER, name = "=1+1", amount = 1.0, unitLabel = "",
                grams = 0.0, kcal = 250.0, protein = 0.0, carbs = 0.0, fat = 0.0, source = EntrySource.QUICK,
                createdAt = 2,
            ),
            DiaryEntryEntity(
                id = 1, date = d1, meal = MealType.BREAKFAST, name = "Apfel", amount = 1.0,
                unitLabel = "1 mittelgroßer Apfel", grams = 150.0, kcal = 78.0, protein = 0.45, carbs = 17.1,
                fat = 0.3, foodId = 1, source = EntrySource.FOOD, createdAt = 1,
            ),
            DiaryEntryEntity(
                id = 4, date = d1, meal = MealType.DINNER, name = "Milchreis; süß", amount = 1.5, unitLabel = "Portion",
                grams = 375.0, kcal = 298.54, protein = 6.3, carbs = 57.1, fat = 3.6, recipeId = 7,
                source = EntrySource.RECIPE, createdAt = 5,
            ),
        )

        val rows = lines(BackupCsv.diary(entries))

        assertEquals(
            "Datum;Mahlzeit;Name;Marke;Menge;Einheit;Gramm/ml;kcal;Kohlenhydrate (g);Eiweiß (g);Fett (g);Quelle",
            rows[0],
        )
        assertEquals(5, rows.size)
        assertEquals("03.11.2026;Frühstück;Apfel;;1;1 mittelgroßer Apfel;150;78;17,1;0,5;0,3;Lebensmittel", rows[1])
        assertEquals("03.11.2026;Abendessen;'=1+1;;;;;250;0;0;0;Schnelleintrag", rows[2])
        assertEquals("03.11.2026;Abendessen;\"Milchreis; süß\";;1,5;Portion;375;298,5;57,1;6,3;3,6;Rezept", rows[3])
        assertEquals("04.11.2026;Mittagessen;Haferdrink;Oatly;250;ml;250;115;16,8;2,5;3,8;Lebensmittel", rows[4])
    }

    @Test
    fun diary_emptyListGivesHeaderOnly() {
        assertEquals(1, lines(BackupCsv.diary(emptyList())).size)
    }

    @Test
    fun measurements_rowsWithTypeUnitAndSource() {
        val list = listOf(
            MeasurementEntity(
                id = 2, type = MeasurementType.BODY_FAT, date = LocalDate.of(2026, 11, 4), value = 21.5,
                source = DataSource.HEALTH_CONNECT, externalId = "x", createdAt = 2,
            ),
            MeasurementEntity(id = 1, type = MeasurementType.WEIGHT, date = LocalDate.of(2026, 11, 4), value = 82.45, createdAt = 1),
            MeasurementEntity(id = 3, type = MeasurementType.WAIST, date = LocalDate.of(2026, 11, 1), value = 94.0, createdAt = 3),
        )

        val rows = lines(BackupCsv.measurements(list))

        assertEquals("Datum;Messwert;Wert;Einheit;Quelle", rows[0])
        assertEquals("01.11.2026;Bauchumfang;94;cm;Manuell", rows[1])
        assertEquals("04.11.2026;Gewicht;82,45;kg;Manuell", rows[2])
        assertEquals("04.11.2026;Körperfett;21,5;%;Health Connect", rows[3])
    }

    @Test
    fun activity_rowsWithStepsAndActiveKcal() {
        val list = listOf(
            DailyActivityEntity(date = LocalDate.of(2026, 11, 4), steps = 4000, activeKcal = null, source = DataSource.MANUAL),
            DailyActivityEntity(date = LocalDate.of(2026, 11, 3), steps = 12345, activeKcal = 320.5, source = DataSource.HEALTH_CONNECT),
            DailyActivityEntity(date = LocalDate.of(2026, 11, 5), steps = 789, activeKcal = null, source = DataSource.SENSOR),
        )

        val rows = lines(BackupCsv.activity(list))

        assertEquals("Datum;Schritte;Aktive kcal;Quelle", rows[0])
        assertEquals("03.11.2026;12345;321;Health Connect", rows[1])
        assertEquals("04.11.2026;4000;;Manuell", rows[2])
        assertEquals("05.11.2026;789;;Schrittsensor", rows[3])
    }
}
