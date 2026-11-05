package de.kalorienkompass.domain

object StepCalories {
    /** Rough estimate of the energy burned by walking: steps × kg × 0,0005 kcal. */
    fun kcal(steps: Long, weightKg: Double): Double =
        if (steps <= 0L || !(weightKg > 0.0) || weightKg.isInfinite()) 0.0 else steps * weightKg * 0.0005
}
