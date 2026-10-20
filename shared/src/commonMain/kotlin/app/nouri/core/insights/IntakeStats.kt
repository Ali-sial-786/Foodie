package app.nouri.core.insights

import app.nouri.core.model.MacroSplit
import app.nouri.core.model.Nutrients
import app.nouri.core.progress.NutritionTargets
import kotlin.math.abs

/**
 * Intake over a [range]. Averages are per *logged* day; [loggedDays] of
 * [totalDays] says how much data they rest on. Optional nutrients (fibre,
 * sugar, sodium, saturated fat) are averaged over the days where they are
 * known. All values are estimates inherited from the logged entries.
 */
data class IntakeSummary(
    val range: DateRange,
    val loggedDays: Int,
    /** Null when nothing was logged in the range. */
    val averagePerLoggedDay: Nutrients?,
    val lowestDay: DailyIntake?,
    val highestDay: DailyIntake?,
    /** Energy share of protein, carbs and fat across the whole range (4/4/9). */
    val macroSplit: MacroSplit?,
    /** Logged days within ±10% of the energy target; null without a target. */
    val daysWithinCalorieTarget: Int?,
    /** Logged days at or above the protein target; null without a target. */
    val proteinTargetHitDays: Int?,
) {
    val totalDays: Int get() = range.dayCount
    val loggedDayShare: Double get() = loggedDays.toDouble() / totalDays
}

/** Average intake from one meal type, over the days that meal was logged. */
data class MealTypeAverage(
    val mealType: String,
    val daysWithMeal: Int,
    val averagePerDay: Nutrients,
    /** Share of all logged energy in the range (0..100). */
    val energySharePct: Double,
)

/** A food grouped by name (case- and whitespace-insensitive). */
data class FoodRank(
    val name: String,
    val count: Int,
    val energyKcal: Double,
    /** Share of all logged energy in the range (0..100). */
    val energySharePct: Double,
)

data class CuisineRank(val cuisineId: String, val count: Int, val energyKcal: Double)

/** Pure statistics over logged intake; no wording, no judgement. */
object IntakeStats {
    /** ±10% band used for "close to target" days. */
    const val CALORIE_TARGET_TOLERANCE = 0.10

    fun summarize(intakes: List<DailyIntake>, range: DateRange, targets: NutritionTargets? = null): IntakeSummary {
        val days = loggedDays(intakes, range)
        val total = Nutrients.sum(days.map { it.nutrients })
        val energyTarget = targets?.energyKcal?.takeIf { it > 0 }
        val proteinTarget = targets?.proteinG?.takeIf { it > 0 }
        return IntakeSummary(
            range = range,
            loggedDays = days.size,
            averagePerLoggedDay = if (days.isEmpty()) null else average(days.map { it.nutrients }),
            lowestDay = days.minByOrNull { it.nutrients.energyKcal },
            highestDay = days.maxByOrNull { it.nutrients.energyKcal },
            macroSplit = if (days.isEmpty()) null else MacroSplit.of(total),
            daysWithinCalorieTarget = energyTarget?.let { t ->
                days.count { abs(it.nutrients.energyKcal - t) <= CALORIE_TARGET_TOLERANCE * t }
            },
            proteinTargetHitDays = proteinTarget?.let { t -> days.count { it.nutrients.proteinG >= t } },
        )
    }

    /** Per meal type, largest energy share first. */
    fun averageByMealType(items: List<ConsumedItem>, range: DateRange): List<MealTypeAverage> {
        val inRange = items.filter { it.date in range }
        val totalEnergy = inRange.sumOf { it.nutrients.energyKcal }
        return inRange.groupBy { it.mealType.trim() }
            .map { (meal, list) ->
                val perDay = list.groupBy { it.date }.values.map { day -> Nutrients.sum(day.map { it.nutrients }) }
                MealTypeAverage(
                    mealType = meal,
                    daysWithMeal = perDay.size,
                    averagePerDay = average(perDay),
                    energySharePct = share(list.sumOf { it.nutrients.energyKcal }, totalEnergy),
                )
            }
            .sortedWith(compareByDescending<MealTypeAverage> { it.energySharePct }.thenBy { it.mealType })
    }

    /** Most frequently logged foods; ties go to the larger energy contribution. */
    fun topFoodsByFrequency(items: List<ConsumedItem>, range: DateRange, limit: Int = 5): List<FoodRank> =
        rankFoods(items, range)
            .sortedWith(compareByDescending<FoodRank> { it.count }.thenByDescending { it.energyKcal }.thenBy { it.name })
            .take(limit)

    /** Foods contributing the most energy over the range. */
    fun topFoodsByEnergy(items: List<ConsumedItem>, range: DateRange, limit: Int = 5): List<FoodRank> =
        rankFoods(items, range)
            .sortedWith(compareByDescending<FoodRank> { it.energyKcal }.thenByDescending { it.count }.thenBy { it.name })
            .take(limit)

    /** Most frequently logged cuisines (items without a cuisine are ignored). */
    fun topCuisines(items: List<ConsumedItem>, range: DateRange, limit: Int = 5): List<CuisineRank> =
        items.filter { it.date in range && !it.cuisineId.isNullOrBlank() }
            .groupBy { it.cuisineId!!.trim() }
            .map { (id, list) -> CuisineRank(id, list.size, list.sumOf { it.nutrients.energyKcal }) }
            .sortedWith(compareByDescending<CuisineRank> { it.count }.thenByDescending { it.energyKcal }.thenBy { it.cuisineId })
            .take(limit)

    /**
     * Logged days in [range], oldest first. Several records for the same date
     * are merged so callers can pass per-source totals.
     */
    fun loggedDays(intakes: List<DailyIntake>, range: DateRange): List<DailyIntake> =
        intakes.filter { it.date in range }
            .groupBy { it.date }
            .map { (date, list) ->
                if (list.size == 1) list.first()
                else DailyIntake(date, Nutrients.sum(list.map { it.nutrients }), list.sumOf { it.entryCount })
            }
            .filter { it.isLogged }
            .sortedBy { it.date }

    /** Mean of [days]; optional nutrients use only the days where they're known. */
    internal fun average(days: List<Nutrients>): Nutrients {
        require(days.isNotEmpty()) { "Cannot average zero days" }
        fun mean(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()
        return Nutrients(
            energyKcal = days.sumOf { it.energyKcal } / days.size,
            proteinG = days.sumOf { it.proteinG } / days.size,
            carbsG = days.sumOf { it.carbsG } / days.size,
            fatG = days.sumOf { it.fatG } / days.size,
            fiberG = mean(days.map { it.fiberG }),
            sugarG = mean(days.map { it.sugarG }),
            sodiumMg = mean(days.map { it.sodiumMg }),
            saturatedFatG = mean(days.map { it.saturatedFatG }),
        )
    }

    private fun rankFoods(items: List<ConsumedItem>, range: DateRange): List<FoodRank> {
        val inRange = items.filter { it.date in range && it.name.isNotBlank() }
        val totalEnergy = inRange.sumOf { it.nutrients.energyKcal }
        return inRange.groupBy { normalizeName(it.name) }
            .map { (_, list) ->
                // Show the spelling the user logged most often.
                val display = list.groupingBy { it.name.trim() }.eachCount().maxBy { it.value }.key
                val energy = list.sumOf { it.nutrients.energyKcal }
                FoodRank(display, list.size, energy, share(energy, totalEnergy))
            }
    }

    private val whitespace = Regex("\\s+")

    private fun normalizeName(name: String): String = name.trim().lowercase().replace(whitespace, " ")

    private fun share(part: Double, total: Double): Double = if (total > 0) part / total * 100.0 else 0.0
}
