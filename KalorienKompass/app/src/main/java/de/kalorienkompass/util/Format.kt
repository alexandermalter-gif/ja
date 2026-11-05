package de.kalorienkompass.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * German number and date formatting used everywhere in the UI.
 * Deterministic (does not depend on the device locale).
 */

private val GERMAN_SYMBOLS: DecimalFormatSymbols = DecimalFormatSymbols(Locale.GERMANY)

private fun decimalFormat(pattern: String, grouping: Boolean = true): DecimalFormat =
    DecimalFormat(pattern, GERMAN_SYMBOLS).apply {
        roundingMode = RoundingMode.HALF_UP
        isGroupingUsed = grouping
    }

private val kcalFormat = ThreadLocal.withInitial { decimalFormat("#,##0") }
private val gramsFormat = ThreadLocal.withInitial { decimalFormat("#,##0.#") }

/** Avoids "-0" for tiny negative values. */
private fun Double.noNegativeZero(decimals: Int): Double {
    val threshold = 0.5 * Math.pow(10.0, -decimals.toDouble())
    return if (this < 0 && abs(this) < threshold) 0.0 else this
}

/** 1234.4 → "1.234" (no decimals, thousands dot). */
fun formatKcal(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "–"
    return kcalFormat.get()!!.format(v.roundToLong())
}

/** Integer with thousands dot: 12345 → "12.345". */
fun formatSteps(v: Long): String = kcalFormat.get()!!.format(v)

/** 12.5 → "12,5", 12.0 → "12" (max. 1 decimal). */
fun formatGrams(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "–"
    return gramsFormat.get()!!.format(v.noNegativeZero(1))
}

/** 83.44 → "83,4" (exactly [decimals] decimals). */
fun formatDecimal(v: Double, decimals: Int = 1): String {
    if (v.isNaN() || v.isInfinite()) return "–"
    val d = decimals.coerceIn(0, 6)
    val pattern = if (d == 0) "#,##0" else "#,##0." + "0".repeat(d)
    return decimalFormat(pattern).format(v.noNegativeZero(d))
}

/**
 * Value for an input field: no thousands separator, up to [maxDecimals] decimals, comma:
 * 83.45 → "83,45", 10000.0 → "10000". Round-trips with [parseDecimal].
 */
fun formatInput(v: Double, maxDecimals: Int = 2): String {
    if (v.isNaN() || v.isInfinite()) return ""
    val d = maxDecimals.coerceIn(0, 6)
    val pattern = if (d == 0) "0" else "0." + "#".repeat(d)
    return decimalFormat(pattern, grouping = false).format(v.noNegativeZero(d))
}

private val DECIMAL_REGEX = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)$")

/** Accepts "," or "." as decimal separator ("12,5", "12.5", "1.234,5"). Returns null if invalid. */
fun parseDecimal(s: String): Double? {
    var t = s.trim().replace(" ", "").replace(" ", "")
    if (t.isEmpty()) return null
    if (t.contains(',') && t.contains('.')) {
        // German style with thousands dots: "1.234,5"
        if (t.lastIndexOf('.') > t.lastIndexOf(',')) return null
        t = t.replace(".", "")
    }
    t = t.replace(',', '.')
    if (!DECIMAL_REGEX.matches(t)) return null
    return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

private val WEEKDAYS_SHORT = arrayOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")
private val WEEKDAYS_LONG = arrayOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
private val MONTHS_SHORT = arrayOf(
    "Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez.",
)
private val MONTHS_LONG = arrayOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)

/** "Mo.", "Di.", … */
fun weekdayShort(date: LocalDate): String = WEEKDAYS_SHORT[date.dayOfWeek.value - 1]

/** "Montag", "Dienstag", … */
fun weekdayLong(date: LocalDate): String = WEEKDAYS_LONG[date.dayOfWeek.value - 1]

/** "Jan.", "Feb.", "März", … */
fun monthShort(date: LocalDate): String = MONTHS_SHORT[date.monthValue - 1]

/** "Januar", "Februar", … */
fun monthLong(date: LocalDate): String = MONTHS_LONG[date.monthValue - 1]

/**
 * "Heute", "Gestern", "Morgen", otherwise "Mo., 3. Nov." (with the year appended
 * when it differs from [today]'s year: "Mo., 3. Nov. 2025").
 */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Heute"
    today.minusDays(1) -> "Gestern"
    today.plusDays(1) -> "Morgen"
    else -> buildString {
        append(weekdayShort(date)).append(", ")
        append(date.dayOfMonth).append(". ")
        append(monthShort(date))
        if (date.year != today.year) append(' ').append(date.year)
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
private val SHORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMANY)

/** "03.11.2026" */
fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

/** "03.11." */
fun formatShortDate(date: LocalDate): String = SHORT_DATE_FORMAT.format(date)

/** "03.11.2026, 14:05" (device time zone) */
fun formatDateTime(epochMillis: Long): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
