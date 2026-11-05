package de.kalorienkompass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Edge cases of the German number parsing/formatting (see also [FormatTest]). */
class FormatEdgeCaseTest {

    private fun assertParsed(expected: Double, input: String) {
        val parsed = parseDecimal(input)
        if (parsed == null) throw AssertionError("parseDecimal(\"$input\") returned null, expected $expected")
        assertEquals("parseDecimal(\"$input\")", expected, parsed, 1e-9)
    }

    @Test
    fun parseDecimal_germanGroupingAndWhitespace() {
        assertParsed(1234.5, "1.234,5")
        assertParsed(1234567.25, "1.234.567,25")
        assertParsed(83.4, "  83,4 ")
        assertParsed(83.4, "\t83,4\n")
        assertParsed(83.4, "\u00A083,4\u00A0") // non-breaking spaces (copied text)
        assertParsed(1234.5, "1 234,5")
        assertParsed(-1234.5, "-1.234,5")
    }

    @Test
    fun parseDecimal_groupingWithoutDecimals() {
        assertParsed(1234567.0, "1.234.567")
        assertParsed(1234567.0, "1,234,567")
        assertParsed(1234.5, "1,234.5") // English style
    }

    @Test
    fun parseDecimal_singleSeparatorIsAlwaysDecimal() {
        assertParsed(1.234, "1.234")
        assertParsed(1.234, "1,234")
        assertParsed(0.5, ".5")
        assertParsed(12.0, "12.")
        assertParsed(-5.0, "−5") // typographic minus
        assertParsed(5.0, "+5")
    }

    @Test
    fun parseDecimal_rejectsGarbage() {
        for (input in listOf("", " ", "-", "+", ",", ".", "-,", "1,2,3", "1.2.3", "1.23,5", "12,5,", "1.234,5,6",
            "1,234.5.6", "12a", "a12", "1e5", "0x10", "NaN", "Infinity", "--5", "+-5", "5-", "1 2,3,4")) {
            assertNull("parseDecimal(\"$input\") should be null", parseDecimal(input))
        }
    }

    @Test
    fun parseDecimal_hugeNumbersAreFiniteOrNull() {
        assertNull(parseDecimal("1" + "0".repeat(400)))
        assertParsed(1e18, "1000000000000000000")
    }

    @Test
    fun formatting_handlesNegativeZeroAndInvalidNumbers() {
        assertEquals("0", formatGrams(-0.04))
        assertEquals("0,0", formatDecimal(-0.04))
        assertEquals("0", formatKcal(-0.4))
        assertEquals("–", formatKcal(Double.NaN))
        assertEquals("–", formatGrams(Double.POSITIVE_INFINITY))
        assertEquals("–", formatDecimal(Double.NaN))
        assertEquals("", formatInput(Double.NaN))
        assertEquals("0", formatInput(-0.001))
    }

    @Test
    fun formatInput_roundTripsThroughParse() {
        for (v in listOf(0.0, 0.5, 1.25, 12.0, 83.45, 999.99, 12345.67, 10000.0)) {
            assertEquals(v, parseDecimal(formatInput(v))!!, 1e-9)
        }
    }

    @Test
    fun dayLabel_aroundYearBoundary() {
        val newYear = LocalDate.of(2027, 1, 1)
        assertEquals("Gestern", dayLabel(LocalDate.of(2026, 12, 31), newYear))
        assertEquals("Morgen", dayLabel(LocalDate.of(2027, 1, 2), newYear))
        assertEquals("Mi., 30. Dez. 2026", dayLabel(LocalDate.of(2026, 12, 30), newYear))
        assertEquals("So., 3. Jan.", dayLabel(LocalDate.of(2027, 1, 3), newYear))
    }
}
