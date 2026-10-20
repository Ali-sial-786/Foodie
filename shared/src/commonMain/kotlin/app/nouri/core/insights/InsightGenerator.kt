package app.nouri.core.insights

import app.nouri.core.progress.NumberText
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.TrendPoint
import app.nouri.core.progress.WeightTrend
import app.nouri.core.progress.WeightUnit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.previousOrSame
import kotlin.math.abs

enum class InsightKind {
    NOT_ENOUGH_DATA,
    WEEK_COMPARISON,
    CALORIES_VS_TARGET,
    LOGGING_CONSISTENCY,
    WEIGHT_TREND,
    INTAKE_AND_WEIGHT,
    TOP_FOOD,
}

/**
 * A short, descriptive observation. [numbers] holds the values quoted in the
 * text, rounded as displayed (energy to 10 kcal, grams whole, weight in kg to
 * 0.1), so a UI can chart or restyle them without contradicting the prose.
 */
data class Insight(
    val kind: InsightKind,
    val title: String,
    val body: String,
    val numbers: Map<String, Double> = emptyMap(),
)

/**
 * Turns logged intake and weight into a few plain statements.
 *
 * Principles: describe, don't judge; never imply that one thing caused another
 * (intake and weight are reported side by side, never linked with causal
 * words); state how much data a number rests on; no false precision. Today is
 * treated as in progress and left out of averages, as a half-logged day would
 * drag them down.
 *
 * Rules (each emitted only when its data requirement is met):
 * - week comparison: this ISO week so far vs last full week, ≥ 4 logged days each;
 * - energy vs target: last 7 completed days, ≥ 4 logged days, a target set;
 * - logging consistency: last 7 days (including today once something is logged
 *   today), when food was logged on ≥ 3 of the last 30 days;
 * - weight trend: ≥ 4 weigh-in days spanning ≥ 14 days in the last 30;
 * - intake alongside weight: as above over ≥ 21 days, with ≥ 14 logged days
 *   covering at least half of them;
 * - top food this calendar month: logged ≥ 3 times.
 * If nothing qualifies, a single [InsightKind.NOT_ENOUGH_DATA] insight says
 * what's needed.
 */
object InsightGenerator {
    const val MIN_LOGGED_DAYS_PER_WEEK = 4
    const val MIN_LOGGED_DAYS_FOR_ANY = 3
    const val MIN_TOP_FOOD_COUNT = 3
    private const val PAIRING_MIN_DAYS = 21
    private const val PAIRING_MIN_LOGGED_DAYS = 14
    private const val WEIGHT_WINDOW_DAYS = 30

    fun generate(
        intakes: List<DailyIntake>,
        items: List<ConsumedItem>,
        weights: List<TrendPoint>?,
        targets: NutritionTargets?,
        today: LocalDate,
        weightUnit: WeightUnit = WeightUnit.KG,
    ): List<Insight> {
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val insights = listOfNotNull(
            weekComparison(intakes, today),
            caloriesVsTarget(intakes, targets, yesterday),
            loggingConsistency(intakes, today),
            weightTrend(weights, today, weightUnit),
            intakeAndWeight(intakes, weights, today, weightUnit),
            topFood(items, today),
        )
        return insights.ifEmpty { listOf(notEnoughData(intakes, weights, today)) }
    }

    private fun weekComparison(intakes: List<DailyIntake>, today: LocalDate): Insight? {
        val monday = today.previousOrSame(DayOfWeek.MONDAY)
        if (monday == today) return null // nothing completed yet this week
        val thisWeek = DateRange(monday, today.minus(1, DateTimeUnit.DAY))
        val lastWeek = DateRange(monday.minus(7, DateTimeUnit.DAY), monday.minus(1, DateTimeUnit.DAY))
        val current = IntakeStats.summarize(intakes, thisWeek)
        val previous = IntakeStats.summarize(intakes, lastWeek)
        if (current.loggedDays < MIN_LOGGED_DAYS_PER_WEEK || previous.loggedDays < MIN_LOGGED_DAYS_PER_WEEK) return null
        val now = current.averagePerLoggedDay!!
        val before = previous.averagePerLoggedDay!!
        val body = "Your energy intake averaged ${NumberText.kcal(now.energyKcal)} per day this week, " +
            "compared with ${NumberText.kcal(before.energyKcal)} last week. " +
            "Your protein intake averaged ${NumberText.grams(now.proteinG)} per day this week, " +
            "compared with ${NumberText.grams(before.proteinG)} last week. " +
            "These averages use the days you logged (${current.loggedDays} so far this week, " +
            "${previous.loggedDays} last week)."
        return Insight(
            InsightKind.WEEK_COMPARISON,
            "This week compared with last week",
            body,
            mapOf(
                "thisWeekKcal" to kcal(now.energyKcal),
                "lastWeekKcal" to kcal(before.energyKcal),
                "thisWeekProteinG" to NumberText.round(now.proteinG),
                "lastWeekProteinG" to NumberText.round(before.proteinG),
                "thisWeekLoggedDays" to current.loggedDays.toDouble(),
                "lastWeekLoggedDays" to previous.loggedDays.toDouble(),
            ),
        )
    }

    private fun caloriesVsTarget(intakes: List<DailyIntake>, targets: NutritionTargets?, yesterday: LocalDate): Insight? {
        if (targets == null) return null
        val target = targets.energyKcal.takeIf { it > 0 } ?: return null
        val summary = IntakeStats.summarize(intakes, DateRange.last(7, yesterday), targets)
        if (summary.loggedDays < MIN_LOGGED_DAYS_PER_WEEK) return null
        val dailyAverage = summary.averagePerLoggedDay!!
        val average = dailyAverage.energyKcal
        val difference = kcal(average) - kcal(target)
        val comparison = if (abs(difference) < 50) {
            "close to your target of ${NumberText.kcal(target)}"
        } else {
            "about ${NumberText.kcal(abs(difference))} ${if (difference < 0) "below" else "above"} " +
                "your target of ${NumberText.kcal(target)}"
        }
        val sb = StringBuilder()
        sb.append("On the ${summary.loggedDays} days you logged in the past week, your intake averaged ")
        sb.append("${NumberText.kcal(average)}, $comparison.")
        // Counts are only quoted when non-zero; "0 days" reads as a verdict rather than information.
        val within = summary.daysWithinCalorieTarget!!
        if (within > 0) {
            sb.append(" $within of those days ${if (within == 1) "was" else "were"} within 10% of your energy target.")
        }
        val proteinTarget = targets.proteinG.takeIf { it > 0 }
        val proteinAverage = dailyAverage.proteinG
        val proteinHits = summary.proteinTargetHitDays
        if (proteinTarget != null && proteinHits != null) {
            sb.append(" Your protein intake averaged ${NumberText.grams(proteinAverage)} per day (target ${NumberText.grams(proteinTarget)})")
            if (proteinHits > 0) sb.append(" and reached the target on ${NumberText.count(proteinHits, "day")}")
            sb.append('.')
        }
        val numbers = buildMap {
            put("averageKcal", kcal(average))
            put("targetKcal", kcal(target))
            put("loggedDays", summary.loggedDays.toDouble())
            put("daysWithinTarget", within.toDouble())
            if (proteinTarget != null && proteinHits != null) {
                put("averageProteinG", NumberText.round(proteinAverage))
                put("proteinTargetG", NumberText.round(proteinTarget))
                put("proteinTargetDays", proteinHits.toDouble())
            }
        }
        return Insight(InsightKind.CALORIES_VS_TARGET, "Energy compared with your target", sb.toString(), numbers)
    }

    private fun loggingConsistency(intakes: List<DailyIntake>, today: LocalDate): Insight? {
        if (IntakeStats.loggedDays(intakes, DateRange.last(30, today)).size < MIN_LOGGED_DAYS_FOR_ANY) return null
        val loggedToday = IntakeStats.loggedDays(intakes, DateRange(today, today)).isNotEmpty()
        val end = if (loggedToday) today else today.minus(1, DateTimeUnit.DAY)
        val logged = IntakeStats.loggedDays(intakes, DateRange.last(7, end)).size
        if (logged == 0) return null
        val body = if (logged == 7) {
            "You logged food on all of the last 7 days."
        } else {
            "You logged food on $logged of the last 7 days."
        }
        return Insight(
            InsightKind.LOGGING_CONSISTENCY,
            "Logging",
            body,
            mapOf("loggedDays" to logged.toDouble(), "days" to 7.0),
        )
    }

    private fun weightTrend(weights: List<TrendPoint>?, today: LocalDate, unit: WeightUnit): Insight? {
        val summary = weightSummary(weights, today) ?: return null
        val numbers = buildMap {
            put("trendChangeKg", NumberText.round(summary.changeKg, 1))
            put("days", summary.days.toDouble())
            summary.ratePerWeekKg?.let { put("ratePerWeekKg", NumberText.round(it, 1)) }
            put("latestDeviationKg", NumberText.round(summary.latestDeviationKg, 1))
        }
        return Insight(InsightKind.WEIGHT_TREND, "Weight trend", summary.describe(unit, today), numbers)
    }

    private fun intakeAndWeight(
        intakes: List<DailyIntake>,
        weights: List<TrendPoint>?,
        today: LocalDate,
        unit: WeightUnit,
    ): Insight? {
        val summary = weightSummary(weights, today) ?: return null
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        if (summary.start > yesterday) return null
        val window = DateRange(summary.start, yesterday)
        if (window.dayCount < PAIRING_MIN_DAYS) return null
        val intake = IntakeStats.summarize(intakes, window)
        if (intake.loggedDays < PAIRING_MIN_LOGGED_DAYS || intake.loggedDays * 2 < window.dayCount) return null
        val average = intake.averagePerLoggedDay!!.energyKcal
        val body = "Over the last ${window.dayCount} days your average intake was ${NumberText.kcal(average)} " +
            "on the ${intake.loggedDays} days you logged, and your trend weight changed by " +
            "${unit.formatChange(summary.changeKg)}."
        return Insight(
            InsightKind.INTAKE_AND_WEIGHT,
            "Intake and weight",
            body,
            mapOf(
                "averageKcal" to kcal(average),
                "trendChangeKg" to NumberText.round(summary.changeKg, 1),
                "days" to window.dayCount.toDouble(),
                "loggedDays" to intake.loggedDays.toDouble(),
            ),
        )
    }

    private fun topFood(items: List<ConsumedItem>, today: LocalDate): Insight? {
        val month = DateRange(LocalDate(today.year, today.month, 1), today)
        val top = IntakeStats.topFoodsByFrequency(items, month, limit = 1).firstOrNull() ?: return null
        if (top.count < MIN_TOP_FOOD_COUNT) return null
        val share = NumberText.round(top.energySharePct)
        val shareText = if (share >= 1.0) {
            " It accounts for about ${NumberText.format(share)}% of the energy you logged this month."
        } else {
            ""
        }
        return Insight(
            InsightKind.TOP_FOOD,
            "Top food this month",
            "Your most logged food this month is ${top.name}, logged ${top.count} times.$shareText",
            mapOf("count" to top.count.toDouble(), "energySharePct" to share),
        )
    }

    private fun notEnoughData(intakes: List<DailyIntake>, weights: List<TrendPoint>?, today: LocalDate): Insight {
        val loggedDays = IntakeStats.loggedDays(intakes, DateRange.last(30, today)).size
        val weighIns = weights.orEmpty().count { it.date in DateRange.last(30, today) }
        val progress = if (loggedDays == 0 && weighIns == 0) {
            "There's no food or weight data from the last 30 days yet."
        } else {
            "So far you've logged food on ${NumberText.count(loggedDays, "day")} " +
                "and weighed in on ${NumberText.count(weighIns, "day")} in the last 30 days."
        }
        val body = "$progress Insights appear once there's a little more to go on: " +
            "log food on at least $MIN_LOGGED_DAYS_FOR_ANY days to see your logging summary, " +
            "on $MIN_LOGGED_DAYS_PER_WEEK days in each of two weeks for weekly comparisons, " +
            "or weigh in on ${WeightTrend.MIN_RATE_POINTS} days over ${WeightTrend.MIN_RATE_SPAN_DAYS} days to see your weight trend."
        return Insight(
            InsightKind.NOT_ENOUGH_DATA,
            "Not enough data yet",
            body,
            mapOf("loggedDays" to loggedDays.toDouble(), "weighInDays" to weighIns.toDouble()),
        )
    }

    private fun weightSummary(weights: List<TrendPoint>?, today: LocalDate) =
        weights?.let { WeightTrend.summarize(it, today.minus(WEIGHT_WINDOW_DAYS, DateTimeUnit.DAY), today) }
            ?.takeIf { it.entryCount >= WeightTrend.MIN_RATE_POINTS && it.days >= WeightTrend.MIN_RATE_SPAN_DAYS }

    private fun kcal(value: Double): Double = NumberText.roundToStep(value, 10.0)
}
