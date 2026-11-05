package de.kalorienkompass.ui.food

import de.kalorienkompass.ai.lookup.NutritionLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Disclaimer text after an AI lookup on the custom food form (spec §13). */
class CustomFoodLookupNoticeTest {

    private val complete = NutritionLookupResult(kcal = 450.0, fat = 17.0, carbs = 61.0, protein = 9.0)

    @Test
    fun labelAndWebDisclaimers() {
        assertEquals(
            "Von der KI gelesen – bitte Werte mit der Packung abgleichen.",
            lookupNotice(complete, AssistKind.LABEL),
        )
        assertEquals(
            "Von der KI recherchiert – bitte Werte mit der Packung abgleichen.",
            lookupNotice(complete, AssistKind.WEB),
        )
    }

    @Test
    fun missingValuesUncertaintyAndNotesAreMentioned() {
        val text = lookupNotice(
            complete.copy(fat = null, protein = null, confident = false, notes = "Ballaststoffe unleserlich."),
            AssistKind.LABEL,
        )
        assertTrue(text.contains("Nicht gefunden: Fett, Eiweiß – bitte ergänzen."))
        assertTrue(text.contains("nicht bei allen Werten sicher"))
        assertTrue(text.endsWith("Hinweis der KI: Ballaststoffe unleserlich."))
        assertFalse(text.contains("Energie"))
    }
}
