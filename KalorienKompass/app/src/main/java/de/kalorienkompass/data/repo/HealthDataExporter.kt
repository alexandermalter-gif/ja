package de.kalorienkompass.data.repo

import java.time.LocalDate

/** Writes body values to an external health store (Health Connect). */
interface HealthDataExporter {
    suspend fun exportWeight(date: LocalDate, kg: Double)
}

object NoOpHealthDataExporter : HealthDataExporter {
    override suspend fun exportWeight(date: LocalDate, kg: Double) = Unit
}
