package app.nouri.core.progress

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightTrendTest {
    private val day0 = LocalDate(2026, 9, 1)
    private fun day(n: Int) = day0.plus(n, DateTimeUnit.DAY)
    private fun entry(n: Int, kg: Double, id: String = "w$n-$kg") = WeightEntry(id, day(n), null, kg)

    @Test
    fun firstReadingSeedsTheTrend() {
        val points = WeightTrend.compute(listOf(entry(0, 80.0)))
        assertEquals(1, points.size)
        assertEquals(80.0, points[0].trendKg)
        assertEquals(0.0, points[0].deviationKg)
    }

    @Test
    fun consecutiveDaysMoveTrendByTenPercent() {
        val points = WeightTrend.compute(listOf(entry(0, 80.0), entry(1, 81.0)))
        assertEquals(80.1, points[1].trendKg, 1e-9)
        assertEquals(0.9, points[1].deviationKg, 1e-9)
    }

    @Test
    fun gapsCompoundTheSmoothingFactor() {
        val gapped = WeightTrend.compute(listOf(entry(0, 80.0), entry(3, 83.0)))
        val alpha = 1 - 0.9.pow(3)
        assertEquals(80.0 + alpha * 3.0, gapped[1].trendKg, 1e-9)
        // Same as reading 83 on each of the missed days.
        val daily = WeightTrend.compute(listOf(entry(0, 80.0), entry(1, 83.0), entry(2, 83.0), entry(3, 83.0)))
        assertEquals(daily.last().trendKg, gapped.last().trendKg, 1e-9)
    }

    @Test
    fun sameDayReadingsAreAveragedIntoOnePoint() {
        val points = WeightTrend.compute(
            listOf(entry(0, 80.0), WeightEntry("a", day(1), 420, 81.0), WeightEntry("b", day(1), 1200, 82.0)),
        )
        assertEquals(2, points.size)
        assertEquals(81.5, points[1].scaleKg, 1e-9)
        assertEquals(80.15, points[1].trendKg, 1e-9)
    }

    @Test
    fun unsortedInputGivesTheSameTrend() {
        val sorted = listOf(entry(0, 80.0), entry(2, 79.6), entry(3, 80.4), entry(7, 79.1))
        assertEquals(WeightTrend.compute(sorted), WeightTrend.compute(sorted.reversed()))
    }

    @Test
    fun ratePerWeekFollowsASteadyLoss() {
        // 0.1 kg/day for 60 days; after warm-up the trend slope matches the ramp.
        val points = WeightTrend.compute((0..60).map { entry(it, 90.0 - 0.1 * it) })
        val rate = assertNotNull(WeightTrend.ratePerWeek(points))
        assertEquals(-0.7, rate, 0.05)
    }

    @Test
    fun ratePerWeekWorksWithIrregularWeighIns() {
        val points = (0..28 step 4).map { TrendPoint(day(it), 80.0, 80.0 - 0.05 * it) }
        assertEquals(-0.35, assertNotNull(WeightTrend.ratePerWeek(points)), 1e-9)
    }

    @Test
    fun ratePerWeekNeedsTwoWeeksAndFourPoints() {
        val tenDays = (0..9).map { TrendPoint(day(it), 80.0, 80.0 - 0.1 * it) }
        assertNull(WeightTrend.ratePerWeek(tenDays))
        val threePoints = listOf(0, 10, 20).map { TrendPoint(day(it), 80.0, 80.0 - 0.1 * it) }
        assertNull(WeightTrend.ratePerWeek(threePoints))
        assertNull(WeightTrend.ratePerWeek(emptyList()))
    }

    @Test
    fun ratePerWeekOnlyUsesTheRecentWindow() {
        // Gaining for the first 30 days, then losing 0.1 kg/day for 28 days.
        val early = (0..30).map { TrendPoint(day(it), 0.0, 80.0 + 0.1 * it) }
        val late = (31..60).map { TrendPoint(day(it), 0.0, 83.0 - 0.1 * (it - 30)) }
        assertEquals(-0.7, assertNotNull(WeightTrend.ratePerWeek(early + late, windowDays = 28)), 1e-9)
    }

    @Test
    fun weeklyAveragesUseIsoWeeksAndDailyMeans() {
        val entries = listOf(
            WeightEntry("1", LocalDate(2026, 10, 19), 400, 80.0), // Monday, two readings → 81
            WeightEntry("2", LocalDate(2026, 10, 19), 1300, 82.0),
            WeightEntry("3", LocalDate(2026, 10, 25), null, 83.0), // Sunday, same week
            WeightEntry("4", LocalDate(2026, 10, 18), null, 79.0), // Sunday, previous week
        )
        val weeks = WeightTrend.weeklyAverages(entries)
        assertEquals(2, weeks.size)
        assertEquals(LocalDate(2026, 10, 12), weeks[0].start)
        assertEquals(79.0, weeks[0].averageKg)
        assertEquals(LocalDate(2026, 10, 19), weeks[1].start)
        assertEquals(LocalDate(2026, 10, 25), weeks[1].endInclusive)
        assertEquals(82.0, weeks[1].averageKg, 1e-9)
        assertEquals(2, weeks[1].daysWeighed)
    }

    @Test
    fun isoWeekSpansTheNewYear() {
        val entries = listOf(
            WeightEntry("a", LocalDate(2026, 12, 31), null, 70.0),
            WeightEntry("b", LocalDate(2027, 1, 1), null, 71.0),
            WeightEntry("c", LocalDate(2027, 1, 4), null, 72.0),
        )
        val weeks = WeightTrend.weeklyAverages(entries)
        assertEquals(listOf(LocalDate(2026, 12, 28), LocalDate(2027, 1, 4)), weeks.map { it.start })
        assertEquals(70.5, weeks[0].averageKg, 1e-9)
    }

    @Test
    fun monthlyAveragesUseCalendarMonths() {
        val entries = listOf(
            WeightEntry("a", LocalDate(2026, 2, 1), null, 70.0),
            WeightEntry("b", LocalDate(2026, 2, 28), null, 72.0),
            WeightEntry("c", LocalDate(2026, 3, 1), null, 75.0),
        )
        val months = WeightTrend.monthlyAverages(entries)
        assertEquals(2, months.size)
        assertEquals(LocalDate(2026, 2, 1), months[0].start)
        assertEquals(LocalDate(2026, 2, 28), months[0].endInclusive)
        assertEquals(71.0, months[0].averageKg, 1e-9)
        assertEquals(75.0, months[1].averageKg)
    }

    private fun linearPoints(days: Int, startKg: Double, endKg: Double) =
        (0..days).map { TrendPoint(day(it), startKg + (endKg - startKg) * it / days, startKg + (endKg - startKg) * it / days) }

    @Test
    fun summaryDescribesTrendChangeCautiously() {
        val summary = assertNotNull(WeightTrend.summarize(linearPoints(30, 80.0, 78.8), day(0), day(30)))
        assertEquals(-1.2, summary.changeKg, 1e-9)
        assertEquals(30, summary.days)
        assertEquals(31, summary.entryCount)
        assertEquals(
            "Your trend weight is down 1.2 kg over 30 days (about 0.3 kg per week).",
            summary.describe(WeightUnit.KG),
        )
        assertEquals(
            "Your trend weight is down 2.6 lb over 30 days (about 0.6 lb per week).",
            summary.describe(WeightUnit.LB),
        )
    }

    @Test
    fun summaryExplainsLargeDayToDayFluctuation() {
        val points = linearPoints(30, 80.0, 78.8).toMutableList()
        val last = points.removeAt(points.lastIndex)
        points += last.copy(scaleKg = last.trendKg + 0.9)
        val summary = assertNotNull(WeightTrend.summarize(points, day(0), day(30)))
        assertEquals(
            "Your trend weight is down 1.2 kg over 30 days (about 0.3 kg per week). " +
                "Today's reading is 0.9 kg above your trend; day-to-day changes like this are usually water and food weight.",
            summary.describe(WeightUnit.KG, today = day(30)),
        )
        assertTrue(summary.describe(WeightUnit.KG, today = day(31)).contains("Your latest reading is 0.9 kg above"))
    }

    @Test
    fun smallDeviationAndFlatTrendAreDescribedPlainly() {
        val flat = (0..20).map { TrendPoint(day(it), 70.3, 70.0 + 0.001 * it) }
        val summary = assertNotNull(WeightTrend.summarize(flat, day(0), day(20)))
        assertEquals("Your trend weight has stayed about the same over 20 days.", summary.describe(WeightUnit.KG))
    }

    @Test
    fun shortPeriodsOmitTheWeeklyRate() {
        val summary = assertNotNull(WeightTrend.summarize(linearPoints(10, 80.0, 79.0), day(0), day(10)))
        assertNull(summary.ratePerWeekKg)
        assertEquals("Your trend weight is down 1.0 kg over 10 days.", summary.describe(WeightUnit.KG))
    }

    @Test
    fun recentEarlierWeighInAnchorsThePeriodStart() {
        val points = listOf(
            TrendPoint(day(0), 80.0, 80.0),
            TrendPoint(day(12), 79.0, 79.5),
            TrendPoint(day(20), 79.0, 79.2),
        )
        val anchored = assertNotNull(WeightTrend.summarize(points, day(5), day(20)))
        assertEquals(day(5), anchored.start)
        assertEquals(80.0, anchored.startTrendKg)
        assertEquals(2, anchored.entryCount)
        // A weigh-in more than a week before the period is too stale to anchor it.
        val unanchored = assertNotNull(WeightTrend.summarize(points, day(10), day(20)))
        assertEquals(day(12), unanchored.start)
        assertEquals(-0.3, unanchored.changeKg, 1e-9)
        assertNull(WeightTrend.summarize(points, day(21), day(25)))
    }

    @Test
    fun weightUnitFormatting() {
        assertEquals("72.4 kg", WeightUnit.KG.format(72.43))
        assertEquals("159.7 lb", WeightUnit.LB.format(72.43))
        assertEquals("−1.2 kg", WeightUnit.KG.formatChange(-1.2))
        assertEquals("+0.4 kg", WeightUnit.KG.formatChange(0.44))
        assertEquals("0.0 kg", WeightUnit.KG.formatChange(-0.04))
        assertEquals(70.0, WeightUnit.LB.toKg(WeightUnit.LB.fromKg(70.0)), 1e-9)
        assertFalse(WeightUnit.KG.formatChange(-0.04).contains("−"))
    }
}
