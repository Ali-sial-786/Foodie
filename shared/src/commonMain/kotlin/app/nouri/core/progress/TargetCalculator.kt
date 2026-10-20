package app.nouri.core.progress

import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Manual targets exactly as entered, plus gentle notes the UI may show. Warnings never block saving. */
data class ManualTargets(val targets: NutritionTargets, val warnings: List<String>)

/** Daily energy for a goal before macros are split, with a sentence explaining it. */
internal data class EnergyPlan(val energyKcal: Double, val explanation: String)

/**
 * Suggests starting nutrition targets from an energy estimate and a goal, and
 * builds manual targets the user typed in.
 *
 * Energy:
 * - MAINTAIN: estimated TDEE.
 * - LOSE: deficit of `rate × 7700 / 7` kcal (default 0.5 kg/week), limited to
 *   25% of TDEE and never below max(BMR, 1200 kcal; 1500 kcal for men). If
 *   maintenance itself is below that floor, the target is maintenance.
 * - GAIN: surplus of `rate × 7700 / 7` (default 0.25 kg/week), at most +500.
 * - MUSCLE_GAIN: +250 kcal.
 *
 * Macros: protein 1.6 g/kg (LOSE, MUSCLE_GAIN) or 1.2 g/kg (MAINTAIN, GAIN) of
 * current weight, at most 2.2 g/kg and 35% of energy; fat at least 25% of
 * energy and 0.6 g/kg; carbohydrate is the remainder; fibre 14 g per 1000 kcal.
 * Energy is rounded to 10 kcal and grams to whole grams. Water is left unset:
 * needs vary too much with climate and activity to derive from this profile.
 *
 * 7700 kcal per kg is a rule of thumb; real change is usually slower than it
 * predicts as the body adapts, which [AdaptiveTargetAdvisor] can pick up later.
 */
object TargetCalculator {
    const val KCAL_PER_KG = 7700.0
    const val DEFAULT_LOSS_RATE_KG = 0.5
    const val DEFAULT_GAIN_RATE_KG = 0.25
    const val MAX_DEFICIT_SHARE = 0.25
    const val MAX_GAIN_SURPLUS_KCAL = 500.0
    const val MUSCLE_GAIN_SURPLUS_KCAL = 250.0
    const val MIN_ENERGY_KCAL = 1200.0
    const val MIN_ENERGY_KCAL_MALE = 1500.0
    const val MAX_PROTEIN_G_PER_KG = 2.2
    const val MAX_PROTEIN_ENERGY_SHARE = 0.35
    const val MIN_FAT_ENERGY_SHARE = 0.25
    const val MIN_FAT_G_PER_KG = 0.6
    const val FIBER_G_PER_1000_KCAL = 14.0

    /** Relative gap between macro energy and the energy target above which [manual] adds a note. */
    const val MACRO_ENERGY_TOLERANCE = 0.15

    /**
     * Suggested targets from a profile, or null when an energy estimate isn't
     * possible yet ([EnergyEstimator.estimate] gives the reason to show).
     */
    fun suggest(
        profile: BodyProfile,
        currentWeightKg: Double,
        today: LocalDate,
        unit: WeightUnit = WeightUnit.KG,
    ): NutritionTargets? =
        EnergyEstimator.estimate(profile, currentWeightKg, today).estimateOrNull()
            ?.let { suggest(profile, currentWeightKg, it, unit) }

    /** Suggested targets from an existing [energy] estimate. */
    fun suggest(
        profile: BodyProfile,
        currentWeightKg: Double,
        energy: EnergyEstimate,
        unit: WeightUnit = WeightUnit.KG,
    ): NutritionTargets {
        require(currentWeightKg.isFinite() && currentWeightKg > 0) { "currentWeightKg must be positive" }
        val plan = energyForGoal(energy.tdeeKcal, energy.bmrKcal, profile.sex, profile.goal, profile.weeklyRateKg, unit)
        // Rounding to 10 kcal can sit up to 5 kcal under a BMR floor; that is far inside the estimate's error.
        val kcal = NumberText.roundToStep(plan.energyKcal, 10.0)

        val proteinPerKg = when (profile.goal) {
            WeightGoal.LOSE, WeightGoal.MUSCLE_GAIN -> 1.6
            WeightGoal.MAINTAIN, WeightGoal.GAIN -> 1.2
        }.coerceAtMost(MAX_PROTEIN_G_PER_KG)
        val proteinCap = MAX_PROTEIN_ENERGY_SHARE * kcal / 4.0
        val proteinG = min(proteinPerKg * currentWeightKg, proteinCap)
        val fatG = max(MIN_FAT_ENERGY_SHARE * kcal / 9.0, MIN_FAT_G_PER_KG * currentWeightKg)
            .coerceAtMost(((kcal - proteinG * 4.0) / 9.0).coerceAtLeast(0.0))
        val carbsG = ((kcal - proteinG * 4.0 - fatG * 9.0) / 4.0).coerceAtLeast(0.0)
        val fiberG = FIBER_G_PER_1000_KCAL * kcal / 1000.0

        val proteinText = if (proteinG < proteinPerKg * currentWeightKg - 1e-9) {
            "Protein is limited to 35% of energy (${NumberText.grams(proteinG)})"
        } else {
            "Protein is set at ${NumberText.format(proteinPerKg, 1)} g per kg of your current weight"
        }
        val fatPct = if (kcal > 0) fatG * 9.0 / kcal * 100.0 else 0.0
        val basis = plan.explanation + " " +
            "$proteinText, fat at about ${NumberText.format(fatPct)}% of energy and carbohydrate makes up the rest; " +
            "fibre is 14 g per 1,000 kcal. These are starting estimates and you can change them at any time."

        return NutritionTargets(
            energyKcal = kcal,
            proteinG = NumberText.round(proteinG),
            carbsG = NumberText.round(carbsG),
            fatG = NumberText.round(fatG),
            fiberG = NumberText.round(fiberG),
            waterMl = null,
            manual = false,
            basis = basis,
        )
    }

    /**
     * Targets typed in by the user, stored exactly as given. If protein, carbs
     * and fat (at 4/4/9 kcal/g) add up to more than 15% away from [energyKcal],
     * a gentle note is returned; it never blocks saving. No note is given when
     * no macro targets are set (all zero).
     */
    fun manual(
        energyKcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        fiberG: Double? = null,
        waterMl: Double? = null,
    ): ManualTargets {
        for (v in listOfNotNull(energyKcal, proteinG, carbsG, fatG, fiberG, waterMl)) {
            require(v.isFinite() && v >= 0) { "Targets must be non-negative numbers" }
        }
        val targets = NutritionTargets(
            energyKcal = energyKcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            fiberG = fiberG,
            waterMl = waterMl,
            manual = true,
            basis = "Targets you set yourself.",
        )
        val macroKcal = targets.macroEnergyKcal
        val mismatch = macroKcal > 0 &&
            (energyKcal <= 0 || abs(macroKcal - energyKcal) / energyKcal > MACRO_ENERGY_TOLERANCE)
        val warnings = if (mismatch) {
            listOf(
                "Your protein, carbohydrate and fat targets add up to about ${NumberText.kcal(macroKcal)} " +
                    "(at 4, 4 and 9 kcal per gram), compared with your energy target of " +
                    "${NumberText.kcal(energyKcal, step = 1.0)}. That's fine if it's intended; " +
                    "your targets are saved as you entered them.",
            )
        } else {
            emptyList()
        }
        return ManualTargets(targets, warnings)
    }

    /** Lowest energy target suggested without medical supervision. */
    fun minimumEnergyKcal(sex: Sex): Double = if (sex == Sex.MALE) MIN_ENERGY_KCAL_MALE else MIN_ENERGY_KCAL

    /** Energy for [goal] around a maintenance estimate; shared with [AdaptiveTargetAdvisor]. */
    internal fun energyForGoal(
        tdeeKcal: Double,
        bmrKcal: Double?,
        sex: Sex,
        goal: WeightGoal,
        weeklyRateKg: Double,
        unit: WeightUnit,
    ): EnergyPlan {
        val maintenance = "Your estimated maintenance is about ${NumberText.kcal(tdeeKcal)}."
        fun rateText(kgPerWeek: Double) =
            "${NumberText.format(unit.fromKg(kgPerWeek), 2, trimZeros = true)} ${unit.symbol} per week"
        return when (goal) {
            WeightGoal.MAINTAIN -> EnergyPlan(tdeeKcal, "$maintenance The energy target matches it.")

            WeightGoal.MUSCLE_GAIN -> EnergyPlan(
                tdeeKcal + MUSCLE_GAIN_SURPLUS_KCAL,
                "$maintenance The target adds a modest 250 kcal to support muscle gain alongside strength training.",
            )

            WeightGoal.GAIN -> {
                val rate = weeklyRateKg.takeIf { it > 0 } ?: DEFAULT_GAIN_RATE_KG
                val wanted = rate * KCAL_PER_KG / 7.0
                val surplus = min(wanted, MAX_GAIN_SURPLUS_KCAL)
                val text = if (surplus < wanted) {
                    "$maintenance Gaining ${rateText(rate)} would need about ${NumberText.kcal(wanted)} extra per day; " +
                        "the surplus is limited to 500 kcal, which corresponds to roughly ${rateText(surplus * 7 / KCAL_PER_KG)}."
                } else {
                    "$maintenance The target adds about ${NumberText.kcal(surplus)}, which corresponds to roughly ${rateText(rate)}."
                }
                EnergyPlan(tdeeKcal + surplus, text)
            }

            WeightGoal.LOSE -> {
                val rate = weeklyRateKg.takeIf { it > 0 } ?: DEFAULT_LOSS_RATE_KG
                val wanted = rate * KCAL_PER_KG / 7.0
                val capped = min(wanted, MAX_DEFICIT_SHARE * tdeeKcal)
                val minimum = minimumEnergyKcal(sex)
                val floor = min(max(bmrKcal ?: 0.0, minimum), tdeeKcal)
                val energy = max(tdeeKcal - capped, floor)
                val deficit = tdeeKcal - energy
                val actualRate = rateText(deficit * 7 / KCAL_PER_KG)
                val text = when {
                    deficit <= 0.0 ->
                        "$maintenance That is already at or below the lowest target suggested here " +
                            "(${NumberText.kcal(max(bmrKcal ?: 0.0, minimum))}), so the target is set at maintenance."
                    energy > tdeeKcal - capped -> {
                        val why = if ((bmrKcal ?: 0.0) >= minimum) "your estimated resting energy" else
                            "a common minimum for plans without medical supervision"
                        "$maintenance Losing ${rateText(rate)} would need about ${NumberText.kcal(wanted)} less per day; " +
                            "the target is kept at or above ${NumberText.kcal(floor)} ($why), " +
                            "which corresponds to roughly $actualRate."
                    }
                    capped < wanted ->
                        "$maintenance Losing ${rateText(rate)} would need about ${NumberText.kcal(wanted)} less per day; " +
                            "to keep it sustainable the deficit is limited to 25% of maintenance " +
                            "(${NumberText.kcal(capped)}), which corresponds to roughly $actualRate."
                    else ->
                        "$maintenance The target is about ${NumberText.kcal(deficit)} below it, " +
                            "which corresponds to roughly ${rateText(rate)}."
                }
                EnergyPlan(energy, text)
            }
        }
    }
}
