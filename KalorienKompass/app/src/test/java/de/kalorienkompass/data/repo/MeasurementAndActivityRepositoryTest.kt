package de.kalorienkompass.data.repo

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.DbTestSupport
import de.kalorienkompass.data.db.AppDatabase
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MeasurementAndActivityRepositoryTest {

    private lateinit var db: AppDatabase
    private val day = LocalDate.of(2026, 11, 5)

    /** Records the export calls and on which thread they ran. */
    private class RecordingExporter(private val failure: Throwable? = null) : HealthDataExporter {
        val calls: MutableList<Pair<LocalDate, Double>> = Collections.synchronizedList(mutableListOf())
        @Volatile var ranOnMainThread = false

        override suspend fun exportWeight(date: LocalDate, kg: Double) {
            if (Looper.myLooper() == Looper.getMainLooper()) ranOnMainThread = true
            calls += date to kg
            failure?.let { throw it }
        }
    }

    @Before
    fun setUp() {
        db = DbTestSupport.inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- MeasurementRepository ----

    @Test
    fun setDailyManualValue_keepsOneManualValuePerDay() = runBlocking {
        val exporter = RecordingExporter()
        val repo = MeasurementRepository(db, exporter)
        repo.setDailyManualValue(MeasurementType.WEIGHT, day, 80.0)
        repo.setDailyManualValue(MeasurementType.WEIGHT, day, 80.1)
        repo.setDailyManualValue(MeasurementType.WEIGHT, day, 80.2)
        repo.setDailyManualValue(MeasurementType.WEIGHT, day.plusDays(1), 79.9)

        val all = repo.observe(MeasurementType.WEIGHT).first()
        assertEquals(listOf(80.2, 79.9), all.map { it.value })
        assertTrue(all.all { it.source == DataSource.MANUAL })
        assertEquals(4, exporter.calls.size)
        assertEquals(day to 80.2, exporter.calls[2])
        assertFalse("export must not run on the main thread", exporter.ranOnMainThread)
    }

    @Test
    fun setDailyManualValue_concurrentCallsDoNotCreateDuplicates() = runBlocking {
        val repo = MeasurementRepository(db, NoOpHealthDataExporter)
        (1..20).map { i ->
            async(Dispatchers.Default) { repo.setDailyManualValue(MeasurementType.BODY_FAT, day, 20.0 + i / 10.0) }
        }.awaitAll()
        assertEquals(1, repo.observe(MeasurementType.BODY_FAT).first().size)
    }

    @Test
    fun manualValueWinsOverAnEarlierHealthConnectValueOfTheSameDay() = runBlocking {
        val repo = MeasurementRepository(db, NoOpHealthDataExporter)
        repo.setDailyManualValue(MeasurementType.WEIGHT, day, 80.0)
        delay(5)
        repo.upsertFromHealthConnect(MeasurementType.WEIGHT, day, 81.0, "hc-1")
        assertEquals(81.0, repo.getLatestOnOrBefore(MeasurementType.WEIGHT, day)!!.value, 1e-9)
        delay(5)
        // setting the daily value again (even unchanged) makes it the latest value of the day
        repo.setDailyManualValue(MeasurementType.WEIGHT, day, 80.0)
        assertEquals(80.0, repo.getLatestOnOrBefore(MeasurementType.WEIGHT, day)!!.value, 1e-9)
        assertEquals(2, repo.observe(MeasurementType.WEIGHT).first().size)
    }

    @Test
    fun exporterFailuresAreSwallowed() = runBlocking {
        for (failure in listOf(IllegalStateException("HC kaputt"), SecurityException("keine Berechtigung"), NoClassDefFoundError("hc"))) {
            val exporter = RecordingExporter(failure)
            val repo = MeasurementRepository(db, exporter)
            repo.setDailyManualValue(MeasurementType.WEIGHT, day, 75.0)
            val id = repo.add(MeasurementType.WEIGHT, day.minusDays(1), 76.0)
            assertTrue(id > 0)
            assertEquals(2, exporter.calls.size)
        }
        // a cancellation inside the exporter (e.g. its own timeout) is not a cancellation of the caller
        val repo = MeasurementRepository(db, object : HealthDataExporter {
            override suspend fun exportWeight(date: LocalDate, kg: Double) {
                withTimeout(1) { delay(1_000) }
            }
        })
        repo.setDailyManualValue(MeasurementType.WEIGHT, day, 74.0)
        assertEquals(74.0, repo.getLatestOnOrBefore(MeasurementType.WEIGHT, day)!!.value, 1e-9)
    }

    @Test
    fun onlyWeightsAreExported() = runBlocking {
        val exporter = RecordingExporter()
        val repo = MeasurementRepository(db, exporter)
        repo.add(MeasurementType.WAIST, day, 90.0)
        repo.setDailyManualValue(MeasurementType.BODY_FAT, day, 22.0)
        assertTrue(exporter.calls.isEmpty())
    }

    @Test
    fun upsertFromHealthConnect_isIdempotentAndUpdatesChangedRecords() = runBlocking {
        val repo = MeasurementRepository(db, NoOpHealthDataExporter)
        repeat(3) { repo.upsertFromHealthConnect(MeasurementType.WEIGHT, day, 81.0, "hc-1") }
        val first = repo.observe(MeasurementType.WEIGHT).first().single()
        assertEquals(DataSource.HEALTH_CONNECT, first.source)
        assertEquals("hc-1", first.externalId)

        repo.upsertFromHealthConnect(MeasurementType.WEIGHT, day.minusDays(1), 81.5, "hc-1")
        val updated = repo.observe(MeasurementType.WEIGHT).first().single()
        assertEquals(first.id, updated.id)
        assertEquals(81.5, updated.value, 1e-9)
        assertEquals(day.minusDays(1), updated.date)

        repo.upsertFromHealthConnect(MeasurementType.WEIGHT, day, Double.NaN, "hc-2")
        repo.upsertFromHealthConnect(MeasurementType.WEIGHT, day, 80.0, " ")
        assertEquals(1, repo.observe(MeasurementType.WEIGHT).first().size)

        (1..10).map {
            async(Dispatchers.Default) { repo.upsertFromHealthConnect(MeasurementType.BODY_FAT, day, 21.0, "hc-fat") }
        }.awaitAll()
        assertEquals(1, repo.observe(MeasurementType.BODY_FAT).first().size)
    }

    @Test
    fun observeLatestWeightOnOrBefore_reEmitsWhenTheTableChanges() = runBlocking {
        val repo = MeasurementRepository(db, NoOpHealthDataExporter)
        val values: MutableList<Double?> = Collections.synchronizedList(mutableListOf())
        val job = launch(Dispatchers.Default) {
            repo.observeLatestWeightOnOrBefore(day).collect { values += it }
        }
        try {
            awaitSize(values, 1)
            repo.add(MeasurementType.WEIGHT, day.minusDays(2), 82.0)
            awaitSize(values, 2)
            repo.add(MeasurementType.WEIGHT, day.plusDays(1), 70.0) // after the date: no new value
            repo.add(MeasurementType.WAIST, day, 95.0) // other type: no new value
            repo.add(MeasurementType.WEIGHT, day.minusDays(1), 81.0)
            awaitSize(values, 3)
            delay(200)
            assertEquals(listOf(null, 82.0, 81.0), values.toList())
        } finally {
            job.cancel()
        }
    }

    private suspend fun awaitSize(list: List<*>, size: Int) {
        withTimeout(5_000) {
            while (list.size < size) delay(10)
        }
    }

    // ---- ActivityRepository: MANUAL > HEALTH_CONNECT > SENSOR ----

    @Test
    fun activityPrecedence() = runBlocking {
        val repo = ActivityRepository(db)
        repo.upsertFromSensor(day, 1_000)
        assertEquals(DataSource.SENSOR, repo.observeDay(day).first()!!.source)

        repo.upsertFromHealthConnect(day, 4_000, 150.0)
        assertEquals(4_000L, repo.observeDay(day).first()!!.steps)
        assertEquals(DataSource.HEALTH_CONNECT, repo.observeDay(day).first()!!.source)

        repo.upsertFromSensor(day, 9_999) // sensor never overrides Health Connect
        assertEquals(4_000L, repo.observeDay(day).first()!!.steps)

        repo.setManualSteps(day, 6_000)
        repo.upsertFromHealthConnect(day, 7_000, 300.0) // nothing overrides manual steps
        repo.upsertFromSensor(day, 8_000)
        val manual = repo.observeDay(day).first()!!
        assertEquals(6_000L, manual.steps)
        assertEquals(DataSource.MANUAL, manual.source)
        assertNull(manual.activeKcal)

        repo.setManualSteps(day, -5)
        assertEquals(0L, repo.observeDay(day).first()!!.steps)
    }

    @Test
    fun healthConnectWithoutDataKeepsSensorSteps() = runBlocking {
        val repo = ActivityRepository(db)
        repo.upsertFromSensor(day, 3_000)
        repo.upsertFromHealthConnect(day, 0, null)
        assertEquals(3_000L, repo.observeDay(day).first()!!.steps)
        assertEquals(DataSource.SENSOR, repo.observeDay(day).first()!!.source)

        repo.upsertFromHealthConnect(day.plusDays(1), 0, null) // empty day: stored as 0 steps
        assertEquals(0L, repo.observeDay(day.plusDays(1)).first()!!.steps)

        repo.upsertFromHealthConnect(day.plusDays(2), 500, Double.NaN) // invalid kcal is dropped
        assertNull(repo.observeDay(day.plusDays(2)).first()!!.activeKcal)
    }

    @Test
    fun sensorUpdatesItsOwnDayAndRangesAreOrdered() = runBlocking {
        val repo = ActivityRepository(db)
        repo.upsertFromSensor(day, 100)
        repo.upsertFromSensor(day, 2_500)
        repo.upsertFromSensor(day.minusDays(1), 8_000)
        assertEquals(2_500L, repo.observeDay(day).first()!!.steps)
        assertEquals(
            listOf(day.minusDays(1), day),
            repo.observeRange(day.minusDays(7), day).first().map { it.date },
        )
    }
}
