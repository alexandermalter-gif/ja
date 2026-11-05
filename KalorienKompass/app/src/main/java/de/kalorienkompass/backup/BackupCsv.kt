package de.kalorienkompass.backup

import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DiaryEntryEntity
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.EntrySource
import de.kalorienkompass.util.formatDate
import de.kalorienkompass.util.formatInput

/**
 * CSV exports for spreadsheets (German Excel/LibreOffice conventions):
 * `;` as separator, decimal comma, dates "05.11.2026", UTF-8 with BOM, CRLF line endings,
 * header row. Pure functions (unit-tested).
 */
object BackupCsv {

    const val SEPARATOR: Char = ';'
    const val BOM: String = "﻿"
    const val LINE_END: String = "\r\n"

    val DIARY_HEADER: List<String> = listOf(
        "Datum", "Mahlzeit", "Name", "Marke", "Menge", "Einheit", "Gramm/ml",
        "kcal", "Kohlenhydrate (g)", "Eiweiß (g)", "Fett (g)", "Quelle",
    )
    val MEASUREMENTS_HEADER: List<String> = listOf("Datum", "Messwert", "Wert", "Einheit", "Quelle")
    val ACTIVITY_HEADER: List<String> = listOf("Datum", "Schritte", "Aktive kcal", "Quelle")

    /** Quotes a cell if it contains the separator, quotes, line breaks or surrounding spaces. */
    fun escape(cell: String): String {
        val needsQuotes = cell.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' } ||
            cell.startsWith(' ') || cell.endsWith(' ')
        return if (needsQuotes) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }

    /**
     * Free text (food names, brands): prevents spreadsheet formula injection by prefixing
     * cells that start with `=`, `+`, `-`, `@` (or a tab/CR) with an apostrophe.
     */
    fun text(value: String?): String {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return ""
        return if (s[0] in FORMULA_START) "'$s" else s
    }

    /** Number with decimal comma, no thousands separator, up to [maxDecimals] decimals; empty for null/NaN. */
    fun number(value: Double?, maxDecimals: Int = 1): String =
        if (value == null || value.isNaN() || value.isInfinite()) "" else formatInput(value, maxDecimals)

    /** One CSV line (cells are escaped) including the line ending. */
    fun line(cells: List<String>): String = cells.joinToString(SEPARATOR.toString()) { escape(it) } + LINE_END

    /** Complete document: BOM, header row and rows. */
    fun document(header: List<String>, rows: List<List<String>>): String = buildString {
        append(BOM)
        append(line(header))
        rows.forEach { append(line(it)) }
    }

    /** Diary entries, sorted by date, meal and time of entry. */
    fun diary(entries: List<DiaryEntryEntity>): String {
        val rows = entries
            .sortedWith(compareBy({ it.date }, { it.meal.ordinal }, { it.createdAt }, { it.id }))
            .map { e ->
                val quick = e.source == EntrySource.QUICK
                listOf(
                    formatDate(e.date),
                    e.meal.label,
                    text(e.name),
                    text(e.brand),
                    if (quick) "" else number(e.amount, 2),
                    text(e.unitLabel),
                    if (quick || e.grams <= 0.0) "" else number(e.grams, 1),
                    number(e.kcal, 1),
                    number(e.carbs, 1),
                    number(e.protein, 1),
                    number(e.fat, 1),
                    sourceLabel(e.source),
                )
            }
        return document(DIARY_HEADER, rows)
    }

    /** Body values (weight, body fat, circumferences), sorted by date and type. */
    fun measurements(list: List<MeasurementEntity>): String {
        val rows = list
            .sortedWith(compareBy({ it.date }, { it.type.ordinal }, { it.createdAt }, { it.id }))
            .map { m ->
                listOf(
                    formatDate(m.date),
                    m.type.label,
                    number(m.value, 2),
                    m.type.unit,
                    m.source.label,
                )
            }
        return document(MEASUREMENTS_HEADER, rows)
    }

    /** Daily steps (and active kcal from Health Connect), sorted by date. */
    fun activity(list: List<DailyActivityEntity>): String {
        val rows = list
            .sortedBy { it.date }
            .map { a ->
                listOf(
                    formatDate(a.date),
                    a.steps.toString(),
                    number(a.activeKcal, 0),
                    a.source.label,
                )
            }
        return document(ACTIVITY_HEADER, rows)
    }

    fun sourceLabel(source: EntrySource): String = when (source) {
        EntrySource.FOOD -> "Lebensmittel"
        EntrySource.RECIPE -> "Rezept"
        EntrySource.QUICK -> "Schnelleintrag"
        EntrySource.AI -> "KI-Schätzung"
    }

    private val FORMULA_START: Set<Char> = setOf('=', '+', '-', '@', '\t', '\r')
}
