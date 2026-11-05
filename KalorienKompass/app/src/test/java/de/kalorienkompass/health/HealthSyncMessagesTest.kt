package de.kalorienkompass.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HealthSyncMessagesTest {

    @Test
    fun successWithAllTypes() {
        assertEquals(
            "Abgeglichen: Aktivität für 7 Tage, 3 Gewichtswerte und 1 Körperfettwert.",
            HealthSyncMessages.success(days = 30, activityDays = 7, weights = 3, bodyFatValues = 1, partial = false),
        )
    }

    @Test
    fun singularForms() {
        assertEquals(
            "Abgeglichen: Aktivität für 1 Tag und 1 Gewichtswert.",
            HealthSyncMessages.success(days = 7, activityDays = 1, weights = 1, bodyFatValues = 0, partial = false),
        )
    }

    @Test
    fun noData() {
        assertEquals(
            "Abgeglichen – Health Connect enthält keine Daten für die letzten 7 Tage.",
            HealthSyncMessages.success(days = 7, activityDays = 0, weights = 0, bodyFatValues = 0, partial = false),
        )
        assertEquals(
            "Abgeglichen – Health Connect enthält keine Daten für heute.",
            HealthSyncMessages.success(days = 1, activityDays = 0, weights = 0, bodyFatValues = 0, partial = false),
        )
    }

    @Test
    fun partialFailureIsMentioned() {
        assertEquals(
            "Abgeglichen: 2 Körperfettwerte. Einige Daten konnten nicht gelesen werden.",
            HealthSyncMessages.success(days = 3, activityDays = 0, weights = 0, bodyFatValues = 2, partial = true),
        )
    }

    @Test
    fun joinGerman() {
        assertEquals("", HealthSyncMessages.joinGerman(emptyList()))
        assertEquals("a", HealthSyncMessages.joinGerman(listOf("a")))
        assertEquals("a und b", HealthSyncMessages.joinGerman(listOf("a", "b")))
        assertEquals("a, b und c", HealthSyncMessages.joinGerman(listOf("a", "b", "c")))
    }

    @Test
    fun weightClientRecordIdIsStablePerDay() {
        val date = LocalDate.of(2026, 11, 5)
        assertEquals("kk-weight-${date.toEpochDay()}", HealthConnectManager.weightClientRecordId(date))
        assertEquals(
            HealthConnectManager.weightClientRecordId(date),
            HealthConnectManager.weightClientRecordId(LocalDate.parse("2026-11-05")),
        )
    }

    @Test
    fun localDateUsesRecordOffset() {
        // 23:30 UTC on Nov 4th is already Nov 5th in Berlin (+01:00)
        val time = Instant.parse("2026-11-04T23:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 5), HealthConnectManager.localDateOf(time, ZoneOffset.ofHours(1)))
        assertEquals(LocalDate.of(2026, 11, 4), HealthConnectManager.localDateOf(time, ZoneOffset.UTC))
    }

    @Test
    fun errorDescriptionsAreGerman() {
        assertEquals(
            "Health Connect hat den Zugriff verweigert. Bitte prüfe die Berechtigungen.",
            HealthConnectManager.describeError(SecurityException("denied")),
        )
        assertEquals(
            "Zu viele Anfragen an Health Connect. Bitte in ein paar Minuten erneut versuchen.",
            HealthConnectManager.describeError(IllegalStateException("Rate limit exceeded")),
        )
        assertEquals(
            "Health Connect ist gerade nicht erreichbar. Bitte später erneut versuchen.",
            HealthConnectManager.describeError(IllegalStateException("Service not available")),
        )
    }
}
