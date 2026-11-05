package de.kalorienkompass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FormatTest {

    @Test
    fun formatKcal_noDecimalsWithThousandsDot() {
        assertEquals("1.234", formatKcal(1234.4))
        assertEquals("1.000", formatKcal(999.6))
        assertEquals("52", formatKcal(52.0))
        assertEquals("0", formatKcal(0.0))
        assertEquals("0", formatKcal(-0.4))
        assertEquals("-150", formatKcal(-150.0))
        assertEquals("12.345", formatKcal(12345.0))
    }

    @Test
    fun formatSteps_thousandsDot() {
        assertEquals("10.000", formatSteps(10_000))
        assertEquals("0", formatSteps(0))
    }

    @Test
    fun formatGrams_maxOneDecimalDropsZero() {
        assertEquals("12,5", formatGrams(12.5))
        assertEquals("12", formatGrams(12.0))
        assertEquals("12", formatGrams(12.04))
        assertEquals("0,3", formatGrams(0.25))
        assertEquals("1.234,5", formatGrams(1234.5))
        assertEquals("0", formatGrams(-0.01))
    }

    @Test
    fun formatDecimal_fixedDecimals() {
        assertEquals("83,4", formatDecimal(83.44))
        assertEquals("83,0", formatDecimal(83.0))
        assertEquals("83,45", formatDecimal(83.45, 2))
        assertEquals("3", formatDecimal(2.5, 0))
        assertEquals("1.234,6", formatDecimal(1234.56))
    }

    @Test
    fun formatInput_roundTripsWithParse() {
        assertEquals("83,45", formatInput(83.45))
        assertEquals("83,4", formatInput(83.4))
        assertEquals("10000", formatInput(10000.0))
        assertEquals(83.45, parseDecimal(formatInput(83.45))!!, 1e-9)
    }

    @Test
    fun parseDecimal_acceptsCommaAndDot() {
        assertEquals(12.5, parseDecimal("12,5")!!, 1e-9)
        assertEquals(12.5, parseDecimal("12.5")!!, 1e-9)
        assertEquals(7.0, parseDecimal(" 7 ")!!, 1e-9)
        assertEquals(12.0, parseDecimal("12,")!!, 1e-9)
        assertEquals(0.5, parseDecimal(",5")!!, 1e-9)
        assertEquals(1234.5, parseDecimal("1.234,5")!!, 1e-9)
    }

    @Test
    fun parseDecimal_rejectsInvalid() {
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("   "))
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal("1e5"))
        assertNull(parseDecimal("1,2,3"))
        assertNull(parseDecimal(","))
        assertNull(parseDecimal("NaN"))
    }

    @Test
    fun dayLabel_relativeAndAbsolute() {
        val today = LocalDate.of(2026, 11, 5) // Donnerstag
        assertEquals("Heute", dayLabel(today, today))
        assertEquals("Gestern", dayLabel(today.minusDays(1), today))
        assertEquals("Morgen", dayLabel(today.plusDays(1), today))
        assertEquals("Mo., 2. Nov.", dayLabel(LocalDate.of(2026, 11, 2), today))
        assertEquals("Mi., 11. März", dayLabel(LocalDate.of(2026, 3, 11), today))
        assertEquals("Mo., 3. Nov. 2025", dayLabel(LocalDate.of(2025, 11, 3), today))
    }

    @Test
    fun formatDate_variants() {
        val date = LocalDate.of(2026, 11, 3)
        assertEquals("03.11.2026", formatDate(date))
        assertEquals("03.11.", formatShortDate(date))
    }

    @Test
    fun formatDateTime_usesDeviceZone() {
        val millis = LocalDateTime.of(2026, 11, 3, 14, 5)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals("03.11.2026, 14:05", formatDateTime(millis))
    }
}
