package de.kalorienkompass.ui.scan

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.repo.BarcodeLookupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var now = 1_000L
    private val lookups = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun food(id: Long) = FoodEntity(
        id = id,
        name = "Haselnusscreme",
        barcode = CODE,
        kcal = 539.0,
        protein = 6.3,
        carbs = 57.5,
        fat = 30.9,
        source = FoodSource.OPEN_FOOD_FACTS,
    )

    private fun TestScope.createViewModel(
        aiKey: Flow<Boolean> = flowOf(false),
        lookup: suspend (String) -> BarcodeLookupResult,
    ): BarcodeScanViewModel {
        val vm = BarcodeScanViewModel(
            lookup = { code ->
                lookups += code
                lookup(code)
            },
            aiKeyAvailable = aiKey,
            ioDispatcher = dispatcher,
            clock = { now },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    private val BarcodeScanViewModel.status: ScanStatus get() = uiState.value.status

    @Test
    fun detectedCode_isLookedUp_andFoundOnlyLeavesOnce() = runTest(dispatcher) {
        val gate = CompletableDeferred<BarcodeLookupResult>()
        val vm = createViewModel { gate.await() }

        assertTrue(vm.onBarcodeDetected(CODE))
        runCurrent()
        assertEquals(ScanStatus.LookingUp(CODE), vm.status)
        assertFalse(vm.uiState.value.isScanning)

        gate.complete(BarcodeLookupResult.Found(food(42)))
        advanceUntilIdle()
        assertEquals(ScanStatus.Found(CODE, 42), vm.status)

        assertTrue(vm.beginLeaving(success = true))
        assertFalse(vm.beginLeaving(success = true))
        runCurrent()
        assertEquals(ScanStatus.Closing(success = true), vm.status)
        assertEquals(listOf(CODE), lookups)
    }

    @Test
    fun detectionsAreIgnoredWhileNotScanning() = runTest(dispatcher) {
        val gate = CompletableDeferred<BarcodeLookupResult>()
        val vm = createViewModel { gate.await() }

        assertTrue(vm.onBarcodeDetected(CODE))
        assertFalse(vm.onBarcodeDetected(OTHER_CODE))
        runCurrent()
        assertEquals(ScanStatus.LookingUp(CODE), vm.status)
        assertEquals(listOf(CODE), lookups)
    }

    @Test
    fun notFound_rescanSuppressesTheSameCodeButAcceptsOthers() = runTest(dispatcher) {
        val vm = createViewModel { BarcodeLookupResult.NotFound }

        assertTrue(vm.onBarcodeDetected(CODE))
        advanceUntilIdle()
        assertEquals(ScanStatus.NotFound(CODE), vm.status)

        now += 10_000 // the user read the sheet for a while
        vm.resumeScanning()
        runCurrent()
        assertEquals(ScanStatus.Scanning, vm.status)
        assertTrue(vm.uiState.value.isScanning)

        now += 500
        assertFalse(vm.onBarcodeDetected(CODE)) // product still in front of the camera
        assertTrue(vm.onBarcodeDetected(OTHER_CODE))
        advanceUntilIdle()
        assertEquals(ScanStatus.NotFound(OTHER_CODE), vm.status)
        assertEquals(listOf(CODE, OTHER_CODE), lookups)
    }

    @Test
    fun sameCodeIsAcceptedAgainAfterItWasOutOfView() = runTest(dispatcher) {
        val vm = createViewModel { BarcodeLookupResult.NotFound }
        vm.onBarcodeDetected(CODE)
        advanceUntilIdle()
        vm.resumeScanning()

        now += ScanDebouncer.DEFAULT_COOLDOWN_MILLIS + 1
        assertTrue(vm.onBarcodeDetected(CODE))
        advanceUntilIdle()
        assertEquals(listOf(CODE, CODE), lookups)
    }

    @Test
    fun error_isShown_andRetryLooksUpAgain() = runTest(dispatcher) {
        var fail = true
        val vm = createViewModel {
            if (fail) BarcodeLookupResult.Error("Keine Verbindung") else BarcodeLookupResult.Found(food(7))
        }

        vm.onBarcodeDetected(CODE)
        advanceUntilIdle()
        assertEquals(ScanStatus.Failed(CODE, "Keine Verbindung"), vm.status)

        fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(ScanStatus.Found(CODE, 7), vm.status)
        assertEquals(listOf(CODE, CODE), lookups)
    }

    @Test
    fun exceptionDuringLookup_becomesGermanError() = runTest(dispatcher) {
        val vm = createViewModel { throw IOException("timeout") }

        vm.onBarcodeDetected(CODE)
        advanceUntilIdle()
        val status = vm.status
        assertTrue(status is ScanStatus.Failed)
        assertTrue((status as ScanStatus.Failed).message.contains("Produktsuche"))
    }

    @Test
    fun foundFoodWithoutId_isTreatedAsError() = runTest(dispatcher) {
        val vm = createViewModel { BarcodeLookupResult.Found(food(0)) }

        vm.onBarcodeDetected(CODE)
        advanceUntilIdle()
        assertTrue(vm.status is ScanStatus.Failed)
    }

    @Test
    fun cancelLookup_returnsToScanning_andIgnoresTheLateResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<BarcodeLookupResult>()
        val vm = createViewModel { gate.await() }

        vm.onBarcodeDetected(CODE)
        runCurrent()
        vm.cancelLookup()
        runCurrent()
        assertEquals(ScanStatus.Scanning, vm.status)

        gate.complete(BarcodeLookupResult.Found(food(42)))
        advanceUntilIdle()
        assertEquals(ScanStatus.Scanning, vm.status)
    }

    @Test
    fun manualCode_bypassesTheDebounce() = runTest(dispatcher) {
        val vm = createViewModel { BarcodeLookupResult.NotFound }
        vm.onBarcodeDetected(CODE)
        advanceUntilIdle()
        vm.resumeScanning()

        vm.onManualCode(CODE)
        advanceUntilIdle()
        assertEquals(ScanStatus.NotFound(CODE), vm.status)
        assertEquals(listOf(CODE, CODE), lookups)

        // …and the camera does not immediately trigger the typed code again.
        vm.resumeScanning()
        assertFalse(vm.onBarcodeDetected(CODE))
    }

    @Test
    fun closing_blocksEverythingElse() = runTest(dispatcher) {
        val vm = createViewModel { BarcodeLookupResult.NotFound }
        vm.onBarcodeDetected(CODE)
        advanceUntilIdle()

        assertTrue(vm.beginLeaving())
        vm.resumeScanning()
        vm.onManualCode(OTHER_CODE)
        assertFalse(vm.onBarcodeDetected(OTHER_CODE))
        advanceUntilIdle()
        assertEquals(ScanStatus.Closing(success = false), vm.status)
        assertEquals(listOf(CODE), lookups)
    }

    @Test
    fun aiKeyFlagIsExposed() = runTest(dispatcher) {
        val vm = createViewModel(aiKey = flowOf(true)) { BarcodeLookupResult.NotFound }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.aiKeyAvailable)
    }

    private companion object {
        const val CODE = "3017620422003"
        const val OTHER_CODE = "4006381333931"
    }
}
