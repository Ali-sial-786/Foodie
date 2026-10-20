package app.nouri.core.progress

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlin.math.abs

/** Energy logged on one day. Days the user didn't log are simply absent. */
data class LoggedEnergyDay(val date: LocalDate, val energyKcal: Double)

/**
 * An optional, user-approved change to the energy target. Never applied
 * automatically. [observedTdeeKcal] is an estimate from the user's own logs and
 * trend weight, rounded to 10 kcal.
 */
data class TargetAdjustmentSuggestion(
    val currentTargetKcal: Double,
    val suggestedTargetKcal: Double,
    val observedTdeeKcal: Double,
    /** Days in the window, from the first logged day to yesterday. */
    val daysUsed: Int,
    /** Share of [daysUsed] with any logging (0..1). */
    val loggedDayShare: Double,
    /** True when the current target was set by the user; the UI should keep the suggestion low-key. */
    val targetsAreManual: Boolean,
    val reason: String,
) {
    val changeKcal: Double get() = suggestedTargetKcal - currentTargetKcal
}

/**
 * Compares logged intake with the weight trend to estimate the energy the user
 * actually maintains on, and suggests a gentle target change when the two
 * disagree.
 *
 * `observedTdee = average logged intake − trendChangeKg × 7700 / days`.
 *
 * Guards, because under-logging is the most common failure mode and would
 * otherwise read as a "slow metabolism":
 * - at least 14 logged days and 70% of days logged between the first logged day
 *   in the window and yesterday (today is excluded as it's usually incomplete);
 * - at least four weigh-in days spanning two weeks in the same window;
 * - implausible results (outside 1000–6000 kcal) produce no suggestion;
 * - only differences of 100 kcal or more are suggested, in steps of at most
 *   200 kcal (rounded to 10), so the user can re-check after a few weeks.
 *
 * The trend lags real change by about a week, and 7700 kcal/kg is approximate,
 * so the output is labelled an estimate. Manual targets may still receive a
 * suggestion, flagged with [TargetAdjustmentSuggestion.targetsAreManual].
 */
object AdaptiveTargetAdvisor {
    const val DEFAULT_WINDOW_DAYS = 28
    const val MIN_LOGGED_DAYS = 14
    const val MIN_LOGGED_SHARE = 0.7
    const val MIN_CHANGE_KCAL = 100.0
    const val MAX_CHANGE_KCAL = 200.0
    private const val MIN_PLAUSIBLE_TDEE = 1000.0
    private const val MAX_PLAUSIBLE_TDEE = 6000.0

    fun suggest(
        intake: List<LoggedEnergyDay>,
        trend: List<TrendPoint>,
        targets: NutritionTargets,
        profile: BodyProfile,
        today: LocalDate,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        unit: WeightUnit = WeightUnit.KG,
    ): TargetAdjustmentSuggestion? {
        require(windowDays >= MIN_LOGGED_DAYS) { "windowDays must be at least $MIN_LOGGED_DAYS" }
        val end = today.minus(1, DateTimeUnit.DAY)
        val windowStart = end.minus(windowDays - 1, DateTimeUnit.DAY)
        val logged = intake
            .filter { it.date in windowStart..end }
            .groupBy { it.date }
            .mapValues { (_, days) -> days.sumOf { it.energyKcal } }
            .filterValues { it > 0.0 }
        if (logged.size < MIN_LOGGED_DAYS) return null
        val firstLogged = logged.keys.min()
        val daysUsed = firstLogged.daysUntil(end) + 1
        val share = logged.size.toDouble() / daysUsed
        if (share < MIN_LOGGED_SHARE) return null
        val averageIntake = logged.values.average()

        val weight = WeightTrend.summarize(trend, firstLogged, end) ?: return null
        if (weight.entryCount < WeightTrend.MIN_RATE_POINTS || weight.days < WeightTrend.MIN_RATE_SPAN_DAYS) return null
        val observedTdee = averageIntake - weight.changeKg * TargetCalculator.KCAL_PER_KG / weight.days
        if (observedTdee !in MIN_PLAUSIBLE_TDEE..MAX_PLAUSIBLE_TDEE) return null

        val bmr = EnergyEstimator.estimate(profile, weight.endTrendKg, today).estimateOrNull()?.bmrKcal
        val ideal = TargetCalculator.energyForGoal(
            observedTdee, bmr, profile.sex, profile.goal, profile.weeklyRateKg, unit,
        ).energyKcal
        val current = targets.energyKcal
        val difference = ideal - current
        if (abs(difference) < MIN_CHANGE_KCAL) return null
        val step = NumberText.roundToStep(difference.coerceIn(-MAX_CHANGE_KCAL, MAX_CHANGE_KCAL), 10.0)
        val suggested = current + step
        val observedRounded = NumberText.roundToStep(observedTdee, 10.0)

        val direction = if (step > 0) "raise" else "lower"
        val aim = when (profile.goal) {
            WeightGoal.MAINTAIN -> "to stay closer to that estimate"
            WeightGoal.LOSE -> "to stay closer to your goal of losing about ${rateText(profile.weeklyRateKg, TargetCalculator.DEFAULT_LOSS_RATE_KG, unit)}"
            WeightGoal.GAIN -> "to stay closer to your goal of gaining about ${rateText(profile.weeklyRateKg, TargetCalculator.DEFAULT_GAIN_RATE_KG, unit)}"
            WeightGoal.MUSCLE_GAIN -> "to keep a modest surplus for muscle gain"
        }
        val stepNote = if (abs(difference) > MAX_CHANGE_KCAL) {
            " Changes are suggested in steps of up to ${NumberText.kcal(MAX_CHANGE_KCAL)} so you can see how each one feels."
        } else {
            ""
        }
        val reason = "Based on your logs and weight trend over the last ${NumberText.count(daysUsed, "day")}, " +
            "your intake averaged about ${NumberText.kcal(averageIntake)} on the ${NumberText.count(logged.size, "day")} " +
            "you logged, and your trend weight changed by ${unit.formatChange(weight.changeKg)}. " +
            "Together these suggest your maintenance is roughly ${NumberText.kcal(observedRounded)} " +
            "(an estimate from your own data). " +
            "You could $direction your daily target from ${NumberText.kcal(current, step = 1.0)} to " +
            "${NumberText.kcal(suggested, step = 1.0)} $aim.$stepNote " +
            "This is optional; nothing changes unless you choose to."

        return TargetAdjustmentSuggestion(
            currentTargetKcal = current,
            suggestedTargetKcal = suggested,
            observedTdeeKcal = observedRounded,
            daysUsed = daysUsed,
            loggedDayShare = share,
            targetsAreManual = targets.manual,
            reason = reason,
        )
    }

    private fun rateText(rateKg: Double, default: Double, unit: WeightUnit): String {
        val rate = rateKg.takeIf { it > 0 } ?: default
        return "${NumberText.format(unit.fromKg(rate), 2, trimZeros = true)} ${unit.symbol} per week"
    }
}
