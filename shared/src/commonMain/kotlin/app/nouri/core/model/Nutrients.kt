package app.nouri.core.model

import kotlinx.serialization.Serializable

/**
 * An absolute amount of nutrients (for a portion, a meal, a day, or per 100 g when
 * used inside a [app.nouri.core.fooddb.FoodReference]).
 *
 * Energy, protein, carbohydrate and fat are always known (possibly zero). Fibre,
 * sugar, sodium and saturated fat are optional because many sources omit them;
 * `null` means "unknown", which is different from zero.
 *
 * Carbohydrate is *total* carbohydrate (including fibre), matching USDA, US and
 * Canadian label conventions. See NUTRITION_ENGINE.md for the discussion of
 * label/database discrepancies.
 */
@Serializable
data class Nutrients(
    val energyKcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    val saturatedFatG: Double? = null,
) {
    operator fun plus(other: Nutrients): Nutrients = Nutrients(
        energyKcal = energyKcal + other.energyKcal,
        proteinG = proteinG + other.proteinG,
        carbsG = carbsG + other.carbsG,
        fatG = fatG + other.fatG,
        fiberG = addNullable(fiberG, other.fiberG),
        sugarG = addNullable(sugarG, other.sugarG),
        sodiumMg = addNullable(sodiumMg, other.sodiumMg),
        saturatedFatG = addNullable(saturatedFatG, other.saturatedFatG),
    )

    operator fun minus(other: Nutrients): Nutrients = this + other * -1.0

    operator fun times(factor: Double): Nutrients = Nutrients(
        energyKcal = energyKcal * factor,
        proteinG = proteinG * factor,
        carbsG = carbsG * factor,
        fatG = fatG * factor,
        fiberG = fiberG?.times(factor),
        sugarG = sugarG?.times(factor),
        sodiumMg = sodiumMg?.times(factor),
        saturatedFatG = saturatedFatG?.times(factor),
    )

    operator fun div(divisor: Double): Nutrients {
        require(divisor != 0.0) { "Cannot divide nutrients by zero" }
        return this * (1.0 / divisor)
    }

    /** Net carbohydrate (total minus fibre) when fibre is known. */
    val netCarbsG: Double? get() = fiberG?.let { (carbsG - it).coerceAtLeast(0.0) }

    /**
     * Energy implied by the macronutrients using general Atwater factors
     * (4/4/9 kcal per g). Useful to sanity-check AI or label values; it will not
     * exactly equal [energyKcal] because databases use food-specific factors and
     * fibre and alcohol are handled differently.
     */
    val atwaterEnergyKcal: Double get() = proteinG * 4.0 + carbsG * 4.0 + fatG * 9.0

    fun isNonNegative(): Boolean =
        energyKcal >= 0 && proteinG >= 0 && carbsG >= 0 && fatG >= 0 &&
            (fiberG ?: 0.0) >= 0 && (sugarG ?: 0.0) >= 0 && (sodiumMg ?: 0.0) >= 0 &&
            (saturatedFatG ?: 0.0) >= 0

    /** Clamps tiny negative values produced by floating point subtraction. */
    fun clampNonNegative(): Nutrients = Nutrients(
        energyKcal = energyKcal.coerceAtLeast(0.0),
        proteinG = proteinG.coerceAtLeast(0.0),
        carbsG = carbsG.coerceAtLeast(0.0),
        fatG = fatG.coerceAtLeast(0.0),
        fiberG = fiberG?.coerceAtLeast(0.0),
        sugarG = sugarG?.coerceAtLeast(0.0),
        sodiumMg = sodiumMg?.coerceAtLeast(0.0),
        saturatedFatG = saturatedFatG?.coerceAtLeast(0.0),
    )

    companion object {
        val ZERO = Nutrients()

        fun sum(items: Iterable<Nutrients>): Nutrients = items.fold(ZERO) { acc, n -> acc + n }

        private fun addNullable(a: Double?, b: Double?): Double? =
            if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
    }
}

/** Share of energy contributed by each macronutrient, using Atwater factors. */
data class MacroSplit(val proteinPct: Double, val carbsPct: Double, val fatPct: Double) {
    companion object {
        fun of(n: Nutrients): MacroSplit {
            val total = n.atwaterEnergyKcal
            if (total <= 0.0) return MacroSplit(0.0, 0.0, 0.0)
            return MacroSplit(
                proteinPct = n.proteinG * 4.0 / total * 100.0,
                carbsPct = n.carbsG * 4.0 / total * 100.0,
                fatPct = n.fatG * 9.0 / total * 100.0,
            )
        }
    }
}
