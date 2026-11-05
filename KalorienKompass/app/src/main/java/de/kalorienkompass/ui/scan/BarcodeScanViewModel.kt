package de.kalorienkompass.ui.scan

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.repo.BarcodeLookupResult
import de.kalorienkompass.data.repo.FoodRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What the scanner is doing right now. */
sealed interface ScanStatus {
    /** The camera is looking for a barcode. */
    data object Scanning : ScanStatus

    /** [code] was detected (or typed in) and is being looked up. */
    data class LookingUp(val code: String) : ScanStatus

    /** Neither the local database nor Open Food Facts know [code]. */
    data class NotFound(val code: String) : ScanStatus

    /** The lookup failed; [message] is German and user-facing. */
    data class Failed(val code: String, val message: String) : ScanStatus

    /** The product is in the local database now; the screen navigates to its detail page. */
    data class Found(val code: String, val foodId: Long) : ScanStatus

    /** The screen is navigating away; the camera analysis stays paused. */
    data class Closing(val success: Boolean) : ScanStatus
}

data class BarcodeScanUiState(
    val status: ScanStatus = ScanStatus.Scanning,
    /** True if an Anthropic API key is stored (needed for the AI options of the "not found" sheet). */
    val aiKeyAvailable: Boolean = false,
) {
    /** True while camera frames should be analysed. */
    val isScanning: Boolean get() = status is ScanStatus.Scanning
}

/**
 * State machine of the barcode scanner: detection → (debounce) → lookup via
 * [FoodRepository.lookupBarcode] → Found / NotFound / Failed.
 *
 * [lookup] is `foodRepository::lookupBarcode` in the app (a function so that the class is testable).
 */
class BarcodeScanViewModel(
    private val lookup: suspend (String) -> BarcodeLookupResult,
    aiKeyAvailable: Flow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    private val status = MutableStateFlow<ScanStatus>(ScanStatus.Scanning)
    private val debouncer = ScanDebouncer()
    private var lookupJob: Job? = null

    val uiState: StateFlow<BarcodeScanUiState> = combine(
        status,
        aiKeyAvailable
            .catch { emit(false) }
            .onStart { emit(false) }
            .distinctUntilChanged(),
    ) { currentStatus, hasKey ->
        BarcodeScanUiState(status = currentStatus, aiKeyAvailable = hasKey)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BarcodeScanUiState())

    /**
     * Called for every (normalized) barcode the camera detects. Returns true if a lookup was
     * started – the screen then gives haptic feedback.
     */
    fun onBarcodeDetected(code: String): Boolean {
        if (code.isBlank() || status.value !is ScanStatus.Scanning) return false
        if (!debouncer.accept(code, clock())) return false
        startLookup(code)
        return true
    }

    /** A validated, manually typed barcode. Always looked up (no debounce). */
    fun onManualCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || status.value is ScanStatus.Closing) return
        debouncer.markHandled(trimmed, clock())
        startLookup(trimmed)
    }

    /** Repeats the failed lookup. */
    fun retry() {
        val current = status.value
        if (current is ScanStatus.Failed) startLookup(current.code)
    }

    /**
     * Back to scanning ("Erneut scannen", sheet dismissed, lookup cancelled). The last code stays
     * suppressed while it is still in front of the camera.
     */
    fun resumeScanning() {
        if (status.value is ScanStatus.Closing) return
        lookupJob?.cancel()
        lookupJob = null
        debouncer.restartCooldown(clock())
        status.value = ScanStatus.Scanning
    }

    /** Cancels a running lookup and continues scanning. */
    fun cancelLookup() = resumeScanning()

    /**
     * Marks the screen as leaving (before navigating away). Returns false if that already happened,
     * so a navigation can never be triggered twice.
     */
    fun beginLeaving(success: Boolean = false): Boolean {
        if (status.value is ScanStatus.Closing) return false
        lookupJob?.cancel()
        lookupJob = null
        status.value = ScanStatus.Closing(success)
        return true
    }

    private fun startLookup(code: String) {
        lookupJob?.cancel()
        status.value = ScanStatus.LookingUp(code)
        lookupJob = viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) { lookup(code) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BarcodeLookupResult.Error(
                    "Bei der Produktsuche ist ein Fehler aufgetreten: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            if (status.value != ScanStatus.LookingUp(code)) return@launch
            status.value = when (result) {
                is BarcodeLookupResult.Found ->
                    if (result.food.id > 0L) {
                        ScanStatus.Found(code, result.food.id)
                    } else {
                        ScanStatus.Failed(code, "Das Produkt konnte nicht gespeichert werden. Bitte versuche es erneut.")
                    }
                BarcodeLookupResult.NotFound -> ScanStatus.NotFound(code)
                is BarcodeLookupResult.Error -> ScanStatus.Failed(code, result.message)
            }
        }
    }
}
