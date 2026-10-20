package app.nouri.core.progress

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.previousOrSame
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * One day's weigh-in alongside the smoothed trend. [scaleKg] is the mean of all
 * readings that day; [deviationKg] is how far the scale sits from the trend
 * (positive = above), which is mostly water, food and timing noise.
 */
data class TrendPoint(val date: LocalDate, val scaleKg: Double, val trendKg: Double) {
    val deviationKg: Double get() = scaleKg - trendKg
}

/** Mean scale weight over a calendar period (an ISO week or a month). */
data class WeightPeriodAverage(
    val start: LocalDate,
    val endInclusive: LocalDate,
    /** Mean of the daily means, so a day with three weigh-ins doesn't count three times. */
    val averageKg: Double,
    val daysWeighed: Int,
)

/**
 * Trend-weight change over a period. The trend, not the scale, is compared so a
 * single heavy or light morning doesn't dominate. [ratePerWeekKg] is null when
 * the period is too short or sparse for a meaningful slope.
 */
data class WeightChangeSummary(
    /** Date the starting trend applies to (the period start when a recent earlier weigh-in anchors it). */
    val start: LocalDate,
    /** Date of the latest weigh-in in the period. */
    val end: LocalDate,
    val startTrendKg: Double,
    val endTrendKg: Double,
    val ratePerWeekKg: Double?,
    /** Number of days with at least one weigh-in inside the period. */
    val entryCount: Int,
    val latestScaleKg: Double,
) {
    val changeKg: Double get() = endTrendKg - startTrendKg
    val days: Int get() = start.daysUntil(end)
    val latestDeviationKg: Double get() = latestScaleKg - endTrendKg

    /**
     * Cautious, plain-language description, e.g. "Your trend weight is down
     * 1.2 kg over 30 days (about 0.3 kg per week)." Adds a note about normal
     * fluctuation when the latest reading is more than
     * [WeightTrend.FLUCTUATION_NOTE_KG] away from the trend. Pass [today] to say
     * "Today's reading" rather than "Your latest reading" when it applies.
     */
    fun describe(unit: WeightUnit, today: LocalDate? = null): String {
        val sb = StringBuilder()
        val period = NumberText.count(days, "day")
        val shownChange = NumberText.round(unit.fromKg(changeKg), 1)
        if (shownChange == 0.0) {
            sb.append("Your trend weight has stayed about the same over $period.")
        } else {
            val direction = if (shownChange < 0) "down" else "up"
            sb.append("Your trend weight is $direction ${unit.formatMagnitude(changeKg)} over $period")
            val rate = ratePerWeekKg?.let { NumberText.round(unit.fromKg(it), 1) }
            // A least-squares slope can disagree in sign with the end-to-end change on
            // flat, noisy data; quoting it then would contradict the sentence.
            if (rate != null && rate != 0.0 && rate.sign == shownChange.sign) {
                sb.append(" (about ${unit.formatMagnitude(ratePerWeekKg)} per week)")
            }
            sb.append('.')
        }
        val deviation = latestDeviationKg
        if (abs(deviation) > WeightTrend.FLUCTUATION_NOTE_KG) {
            val reading = if (today != null && end == today) "Today's reading" else "Your latest reading"
            val side = if (deviation > 0) "above" else "below"
            sb.append(" $reading is ${unit.formatMagnitude(deviation)} $side your trend; ")
            sb.append("day-to-day changes like this are usually water and food weight.")
        }
        return sb.toString()
    }
}

/**
 * Exponentially smoothed weight trend in the style of The Hacker's Diet.
 *
 * Each day's mean reading moves the trend by a fraction α of the gap between
 * them (default 10%). Missed days are handled by compounding the smoothing over
 * the gap: after `g` days the effective factor is `1 − (1 − α)^g`, which is
 * what daily updates would give if the unseen days had read the same as the
 * next reading. Caveats: the series is seeded with the first reading, so the
 * first week or two lean on it; and the trend lags real change by roughly
 * `(1 − α) / α` days (~9 days at 10%).
 */
object WeightTrend {
    const val DEFAULT_SMOOTHING = 0.1

    /** Scale-vs-trend gap above which descriptions explain normal fluctuation. */
    const val FLUCTUATION_NOTE_KG = 0.7

    /** Minimum span and number of weigh-in days before a weekly rate is quoted. */
    const val MIN_RATE_SPAN_DAYS = 14
    const val MIN_RATE_POINTS = 4

    /** An earlier weigh-in may anchor a period's starting trend if it is at most this old. */
    private const val MAX_ANCHOR_GAP_DAYS = 7

    /** Trend points, one per weigh-in day, oldest first. Input may be unsorted. */
    fun compute(entries: List<WeightEntry>, smoothing: Double = DEFAULT_SMOOTHING): List<TrendPoint> {
        require(smoothing > 0.0 && smoothing <= 1.0) { "smoothing must be in (0, 1]" }
        val days = dailyMeans(entries)
        if (days.isEmpty()) return emptyList()
        val points = ArrayList<TrendPoint>(days.size)
        var previousDate = days.first().first
        var trend = days.first().second
        for ((date, scale) in days) {
            val gap = previousDate.daysUntil(date)
            if (gap > 0) {
                val alpha = 1.0 - (1.0 - smoothing).pow(gap)
                trend += alpha * (scale - trend)
            }
            points += TrendPoint(date, scale, trend)
            previousDate = date
        }
        return points
    }

    /**
     * Trend change per week (kg, negative = losing) from a least-squares line
     * through the trend over the last [windowDays] days before the latest point.
     * Null when that window spans less than two weeks or has fewer than four
     * weigh-in days.
     */
    fun ratePerWeek(points: List<TrendPoint>, windowDays: Int = 28): Double? {
        require(windowDays > 0) { "windowDays must be positive" }
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.date }
        val windowStart = sorted.last().date.minus(windowDays, DateTimeUnit.DAY)
        return slopePerWeek(sorted.filter { it.date >= windowStart })
    }

    /** Average scale weight per ISO week (Monday–Sunday), oldest first. */
    fun weeklyAverages(entries: List<WeightEntry>): List<WeightPeriodAverage> =
        periodAverages(entries) { date ->
            val monday = date.previousOrSame(DayOfWeek.MONDAY)
            monday to monday.plus(6, DateTimeUnit.DAY)
        }

    /** Average scale weight per calendar month, oldest first. */
    fun monthlyAverages(entries: List<WeightEntry>): List<WeightPeriodAverage> =
        periodAverages(entries) { date ->
            val first = LocalDate(date.year, date.month, 1)
            first to first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        }

    /**
     * Trend change between [start] and [endInclusive]. The starting trend is the
     * trend as of [start] when a weigh-in on or up to a week before it exists,
     * otherwise the first weigh-in inside the period. Null when the period has no
     * weigh-ins or no elapsed time to compare.
     */
    fun summarize(points: List<TrendPoint>, start: LocalDate, endInclusive: LocalDate): WeightChangeSummary? {
        require(start <= endInclusive) { "start must not be after endInclusive" }
        val sorted = points.sortedBy { it.date }
        val inRange = sorted.filter { it.date in start..endInclusive }
        if (inRange.isEmpty()) return null
        val anchor = sorted.lastOrNull { it.date <= start }
            ?.takeIf { it.date.daysUntil(start) <= MAX_ANCHOR_GAP_DAYS }
        val startDate = if (anchor != null) start else inRange.first().date
        val startTrend = anchor?.trendKg ?: inRange.first().trendKg
        val last = inRange.last()
        if (startDate.daysUntil(last.date) <= 0) return null
        return WeightChangeSummary(
            start = startDate,
            end = last.date,
            startTrendKg = startTrend,
            endTrendKg = last.trendKg,
            ratePerWeekKg = slopePerWeek(inRange),
            entryCount = inRange.size,
            latestScaleKg = last.scaleKg,
        )
    }

    /** Least-squares slope of trend vs day, ×7. Expects points sorted by date. */
    internal fun slopePerWeek(sorted: List<TrendPoint>): Double? {
        if (sorted.size < MIN_RATE_POINTS) return null
        val origin = sorted.first().date
        if (origin.daysUntil(sorted.last().date) < MIN_RATE_SPAN_DAYS) return null
        val xs = sorted.map { origin.daysUntil(it.date).toDouble() }
        val ys = sorted.map { it.trendKg }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        if (den == 0.0) return null
        return num / den * 7.0
    }

    /** (date, mean kg) per weigh-in day, oldest first. */
    internal fun dailyMeans(entries: List<WeightEntry>): List<Pair<LocalDate, Double>> =
        entries.groupBy { it.date }
            .map { (date, list) -> date to list.map { it.weightKg }.average() }
            .sortedBy { it.first }

    private fun periodAverages(
        entries: List<WeightEntry>,
        bounds: (LocalDate) -> Pair<LocalDate, LocalDate>,
    ): List<WeightPeriodAverage> =
        dailyMeans(entries)
            .groupBy { bounds(it.first) }
            .map { (period, days) ->
                WeightPeriodAverage(
                    start = period.first,
                    endInclusive = period.second,
                    averageKg = days.map { it.second }.average(),
                    daysWeighed = days.size,
                )
            }
            .sortedBy { it.start }
}
