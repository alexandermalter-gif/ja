package de.kalorienkompass.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeUtilsTest {

    private val nbsp = BarcodeUtils.NBSP

    @Test
    fun checkDigit_matchesKnownCodes() {
        assertEquals(1, BarcodeUtils.checkDigit("400638133393")) // EAN-13
        assertEquals(3, BarcodeUtils.checkDigit("301762042200")) // EAN-13
        assertEquals(4, BarcodeUtils.checkDigit("9638507")) // EAN-8
        assertEquals(2, BarcodeUtils.checkDigit("03600029145")) // UPC-A
    }

    @Test
    fun hasValidCheckDigit_acceptsValidGtins() {
        assertTrue(BarcodeUtils.hasValidCheckDigit("4006381333931"))
        assertTrue(BarcodeUtils.hasValidCheckDigit("5449000000996"))
        assertTrue(BarcodeUtils.hasValidCheckDigit("96385074"))
        assertTrue(BarcodeUtils.hasValidCheckDigit("036000291452"))
        assertTrue(BarcodeUtils.hasValidCheckDigit("04006381333931"))
    }

    @Test
    fun hasValidCheckDigit_rejectsWrongDigitsLengthsAndCharacters() {
        assertFalse(BarcodeUtils.hasValidCheckDigit("4006381333932"))
        assertFalse(BarcodeUtils.hasValidCheckDigit("96385075"))
        assertFalse(BarcodeUtils.hasValidCheckDigit("400638133393"))
        assertFalse(BarcodeUtils.hasValidCheckDigit("12345"))
        assertFalse(BarcodeUtils.hasValidCheckDigit(""))
        assertFalse(BarcodeUtils.hasValidCheckDigit("40063813339a1"))
    }

    @Test
    fun expandUpcE_coversAllPatterns() {
        assertEquals("042100005264", BarcodeUtils.expandUpcE("04252614")) // last digit 1
        assertEquals("012000003455", BarcodeUtils.expandUpcE("01234505")) // last digit 0
        assertEquals("012300000451", BarcodeUtils.expandUpcE("01234531")) // last digit 3
        assertEquals("012340000053", BarcodeUtils.expandUpcE("01234543")) // last digit 4
        assertEquals("012345000065", BarcodeUtils.expandUpcE("01234565")) // last digit 5–9
    }

    @Test
    fun expandUpcE_withoutCheckDigitComputesIt() {
        assertEquals("042100005264", BarcodeUtils.expandUpcE("0425261"))
        assertEquals("042100005264", BarcodeUtils.expandUpcE("425261"))
    }

    @Test
    fun expandUpcE_rejectsInvalidInput() {
        assertNull(BarcodeUtils.expandUpcE("04252615")) // wrong check digit
        assertNull(BarcodeUtils.expandUpcE("24252614")) // number system must be 0 or 1
        assertNull(BarcodeUtils.expandUpcE("12345"))
        assertNull(BarcodeUtils.expandUpcE("0425261x"))
    }

    @Test
    fun normalizeScanned_keepsValidCodesAndExpandsUpcE() {
        assertEquals("4006381333931", BarcodeUtils.normalizeScanned("4006381333931", isUpcE = false))
        assertEquals("96385074", BarcodeUtils.normalizeScanned(" 96385074 ", isUpcE = false))
        assertEquals("042100005264", BarcodeUtils.normalizeScanned("04252614", isUpcE = true))
        assertEquals("042100005264", BarcodeUtils.normalizeScanned("042100005264", isUpcE = true))
        assertEquals("4006381333931", BarcodeUtils.normalizeScanned("04006381333931", isUpcE = false))
    }

    @Test
    fun normalizeScanned_rejectsImplausibleValues() {
        assertNull(BarcodeUtils.normalizeScanned(null, isUpcE = false))
        assertNull(BarcodeUtils.normalizeScanned("", isUpcE = false))
        assertNull(BarcodeUtils.normalizeScanned("https://example.org", isUpcE = false))
        assertNull(BarcodeUtils.normalizeScanned("4006381333932", isUpcE = false))
        assertNull(BarcodeUtils.normalizeScanned("04252615", isUpcE = true))
    }

    @Test
    fun validateManual_acceptsValidCodes() {
        assertEquals(
            BarcodeUtils.ManualInput.Valid("4006381333931"),
            BarcodeUtils.validateManual("4006381333931"),
        )
        assertEquals(
            BarcodeUtils.ManualInput.Valid("4006381333931"),
            BarcodeUtils.validateManual("4 006381 333931"),
        )
        assertEquals(BarcodeUtils.ManualInput.Valid("96385074"), BarcodeUtils.validateManual("9638-5074"))
        assertEquals(BarcodeUtils.ManualInput.Valid("036000291452"), BarcodeUtils.validateManual("036000291452"))
        assertEquals(BarcodeUtils.ManualInput.Valid("4006381333931"), BarcodeUtils.validateManual("04006381333931"))
        // 8 digits that are no valid EAN-8 but a valid UPC-E
        assertEquals(BarcodeUtils.ManualInput.Valid("042100005264"), BarcodeUtils.validateManual("04252614"))
    }

    @Test
    fun validateManual_explainsProblems() {
        assertTrue(BarcodeUtils.validateManual("") is BarcodeUtils.ManualInput.Invalid)
        val length = BarcodeUtils.validateManual("12345")
        assertTrue(length is BarcodeUtils.ManualInput.Invalid)
        assertTrue((length as BarcodeUtils.ManualInput.Invalid).message.contains("(eingegeben: 5)"))
        val checksum = BarcodeUtils.validateManual("4006381333932")
        assertTrue(checksum is BarcodeUtils.ManualInput.Invalid)
        assertTrue((checksum as BarcodeUtils.ManualInput.Invalid).message.contains("Prüfziffer"))
        assertTrue(BarcodeUtils.validateManual("96385075") is BarcodeUtils.ManualInput.Invalid)
    }

    @Test
    fun formatForDisplay_groupsLikePrintedCodes() {
        assertEquals("4${nbsp}006381${nbsp}333931", BarcodeUtils.formatForDisplay("4006381333931"))
        assertEquals("9638${nbsp}5074", BarcodeUtils.formatForDisplay("96385074"))
        assertEquals("0${nbsp}36000${nbsp}29145${nbsp}2", BarcodeUtils.formatForDisplay("036000291452"))
        assertEquals("12345", BarcodeUtils.formatForDisplay("12345"))
        assertEquals("abc", BarcodeUtils.formatForDisplay("abc"))
    }

    @Test
    fun digitsOnly_stripsEverythingElse() {
        assertEquals("4006381333931", BarcodeUtils.digitsOnly(" 4 006381-333931 "))
        assertEquals("", BarcodeUtils.digitsOnly("abc"))
    }
}
