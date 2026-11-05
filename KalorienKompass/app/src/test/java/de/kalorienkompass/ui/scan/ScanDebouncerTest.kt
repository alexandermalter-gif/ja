package de.kalorienkompass.ui.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDebouncerTest {

    @Test
    fun sameCodeIsSuppressedWithinCooldown() {
        val debouncer = ScanDebouncer(cooldownMillis = 2_500)
        assertTrue(debouncer.accept("A", now = 0))
        assertFalse(debouncer.accept("A", now = 100))
        assertFalse(debouncer.accept("A", now = 2_000))
    }

    @Test
    fun everySightingExtendsTheCooldown() {
        val debouncer = ScanDebouncer(cooldownMillis = 2_500)
        assertTrue(debouncer.accept("A", now = 0))
        assertFalse(debouncer.accept("A", now = 2_000))
        assertFalse(debouncer.accept("A", now = 4_000)) // still in view: 2 000 ms since the last sighting
        assertTrue(debouncer.accept("A", now = 7_000)) // out of view for 3 000 ms
    }

    @Test
    fun otherCodesAreAcceptedImmediately() {
        val debouncer = ScanDebouncer(cooldownMillis = 2_500)
        assertTrue(debouncer.accept("A", now = 0))
        assertTrue(debouncer.accept("B", now = 10))
        assertTrue(debouncer.accept("A", now = 20))
    }

    @Test
    fun restartCooldownStartsTheWindowAgain() {
        val debouncer = ScanDebouncer(cooldownMillis = 2_500)
        assertTrue(debouncer.accept("A", now = 0))
        debouncer.restartCooldown(now = 10_000)
        assertFalse(debouncer.accept("A", now = 12_000))
        assertTrue(debouncer.accept("A", now = 15_000))
    }

    @Test
    fun markHandledSuppressesTheCode() {
        val debouncer = ScanDebouncer(cooldownMillis = 2_500)
        debouncer.markHandled("A", now = 0)
        assertFalse(debouncer.accept("A", now = 1_000))
        assertTrue(debouncer.accept("B", now = 1_100))
    }

    @Test
    fun resetForgetsTheLastCode() {
        val debouncer = ScanDebouncer(cooldownMillis = 2_500)
        assertTrue(debouncer.accept("A", now = 0))
        debouncer.reset()
        assertTrue(debouncer.accept("A", now = 100))
    }
}
