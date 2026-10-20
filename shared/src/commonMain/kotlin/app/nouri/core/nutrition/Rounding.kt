package app.nouri.core.nutrition

import app.nouri.core.model.Nutrients
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Rounding policies. Calculations always run on unrounded values; rounding is
 * applied only for display, so totals of rounded items may differ slightly from
 * the rounded total (documented in NUTRITION_ENGINE.md).
 */
enum class Precision {
    /** Weighed food or label values: whole kcal, macros to 0.1 g below 10 g. */
    MEASURED,

    /** Recipe or reference-dish calculations: kcal to 5, macros to whole grams. */
    CALCULATED,

    /** Photo-based estimates: kcal to 10, macros to whole grams; shown with a range. */
    ESTIMATED,
}

object NutritionRounding {

    /** Round half away from zero (what people expect when reading nutrition numbers). */
    fun roundTo(value: Double, step: Double): Double {
        require(step > 0) { "Step must be positive" }
        val scaled = value / step
        val rounded = if (scaled >= 0) floor(scaled + 0.5) else -floor(-scaled + 0.5)
        return rounded * step
    }

    fun energy(kcal: Double, precision: Precision): Long = when (precision) {
        Precision.MEASURED -> roundTo(kcal, 1.0)
        Precision.CALCULATED -> roundTo(kcal, 5.0)
        Precision.ESTIMATED -> roundTo(kcal, 10.0)
    }.roundToLong()

    fun grams(value: Double, precision: Precision): Double = when {
        precision == Precision.MEASURED && abs(value) < 10 -> roundTo(value, 0.1)
        else -> roundTo(value, 1.0)
    }

    fun sodium(mg: Double): Double = if (mg < 140) roundTo(mg, 5.0) else roundTo(mg, 10.0)

    fun formatGrams(value: Double, precision: Precision = Precision.CALCULATED): String {
        val r = grams(value, precision)
        return if (r == floor(r)) "${r.toLong()} g" else "${(r * 10).roundToLong() / 10.0} g"
    }

    fun formatKcal(value: Double, precision: Precision = Precision.CALCULATED): String = "${energy(value, precision)} kcal"

    /**
     * US FDA nutrition-label rounding (21 CFR 101.9). Used to compare our
     * calculations with printed labels, not for in-app display.
     */
    object UsLabel {
        fun calories(kcal: Double): Double = when {
            kcal < 5 -> 0.0
            kcal <= 50 -> roundTo(kcal, 5.0)
            else -> roundTo(kcal, 10.0)
        }

        fun fat(g: Double): Double = when {
            g < 0.5 -> 0.0
            g < 5 -> roundTo(g, 0.5)
            else -> roundTo(g, 1.0)
        }

        /** Carbohydrate, fibre, sugars and protein. Values 0.5–1 g are declared as "less than 1 g" (returned as 0.5). */
        fun carbLike(g: Double): Double = when {
            g < 0.5 -> 0.0
            g < 1 -> 0.5
            else -> roundTo(g, 1.0)
        }

        fun sodium(mg: Double): Double = when {
            mg < 5 -> 0.0
            mg <= 140 -> roundTo(mg, 5.0)
            else -> roundTo(mg, 10.0)
        }

        fun apply(n: Nutrients): Nutrients = Nutrients(
            energyKcal = calories(n.energyKcal),
            proteinG = carbLike(n.proteinG),
            carbsG = carbLike(n.carbsG),
            fatG = fat(n.fatG),
            fiberG = n.fiberG?.let(::carbLike),
            sugarG = n.sugarG?.let(::carbLike),
            sodiumMg = n.sodiumMg?.let(::sodium),
            saturatedFatG = n.saturatedFatG?.let(::fat),
        )
    }
}
