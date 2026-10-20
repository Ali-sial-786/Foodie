package app.nouri.core.progress

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveTargetAdvisorTest {
    private val today = LocalDate(2026, 10, 20)
    private val windowStart = today.minus(28, DateTimeUnit.DAY) // first day of the 28-day window ending yesterday

    /** Trend falling 0.05 kg/day (≈ 385 kcal/day below intake) from well before the window to yesterday. */
    private val trend = (0..35).map { i ->
        val kg = 80.0 - 0.05 * i
        TrendPoint(today.minus(36 - i, DateTimeUnit.DAY), kg, kg)
    }

    /** Intake of [kcal] on window days with the given indices (0 = windowStart, 27 = yesterday). */
    private fun intake(days: Iterable<Int>, kcal: Double = 2015.0) =
        days.map { LoggedEnergyDay(windowStart.plus(it, DateTimeUnit.DAY), kcal) }

    private val fullWindow = intake(0..27)
    private val maintain = BodyProfile(goal = WeightGoal.MAINTAIN)

    private fun targets(kcal: Double, manual: Boolean = false) =
        NutritionTargets(kcal, 120.0, 250.0, 70.0, manual = manual, basis = "test")

    @Test
    fun suggestsMovingTowardObservedMaintenance() {
        val s = assertNotNull(AdaptiveTargetAdvisor.suggest(fullWindow, trend, targets(2250.0), maintain, today))
        // 2015 − (−1.35 kg × 7700) / 27 days = 2400.
        assertEquals(2400.0, s.observedTdeeKcal)
        assertEquals(2250.0, s.currentTargetKcal)
        assertEquals(2400.0, s.suggestedTargetKcal)
        assertEquals(28, s.daysUsed)
        assertEquals(1.0, s.loggedDayShare)
        assertFalse(s.targetsAreManual)
        assertTrue(s.reason.startsWith("Based on your logs and weight trend"), s.reason)
        assertTrue(s.reason.contains("You could raise"), s.reason)
        assertTrue(s.reason.contains("optional"), s.reason)
        for (word in listOf("should", "must", "need to", "failed", "because")) {
            assertFalse(s.reason.lowercase().contains(word), "'$word' in: ${s.reason}")
        }
    }

    @Test
    fun changesAreCappedAt200Kcal() {
        val s = assertNotNull(AdaptiveTargetAdvisor.suggest(fullWindow, trend, targets(2000.0), maintain, today))
        assertEquals(2200.0, s.suggestedTargetKcal)
        assertEquals(200.0, s.changeKcal)
        assertTrue(s.reason.contains("steps of up to 200 kcal"), s.reason)
    }

    @Test
    fun smallDifferencesAreNotWorthSuggesting() {
        assertNull(AdaptiveTargetAdvisor.suggest(fullWindow, trend, targets(2350.0), maintain, today))
        assertNotNull(AdaptiveTargetAdvisor.suggest(fullWindow, trend, targets(2290.0), maintain, today))
    }

    @Test
    fun goalOffsetIsAppliedToObservedMaintenance() {
        val lose = BodyProfile(goal = WeightGoal.LOSE, weeklyRateKg = 0.5)
        val s = assertNotNull(AdaptiveTargetAdvisor.suggest(fullWindow, trend, targets(2000.0), lose, today))
        assertEquals(1850.0, s.suggestedTargetKcal) // 2400 − 550
        assertTrue(s.reason.contains("You could lower"), s.reason)
        assertTrue(s.reason.contains("losing about 0.5 kg per week"), s.reason)
    }

    @Test
    fun needsFourteenLoggedDays() {
        assertNull(AdaptiveTargetAdvisor.suggest(intake(15..27), trend, targets(2000.0), maintain, today))
    }

    @Test
    fun needsSeventyPercentCoverage() {
        // 15 logged days out of 28 since the first logged day.
        assertNull(AdaptiveTargetAdvisor.suggest(intake(0..14), trend, targets(2000.0), maintain, today))
    }

    @Test
    fun coverageCountsFromTheFirstLoggedDay() {
        // A newer user: 18 of the last 20 days logged.
        val days = (8..27).filter { it != 12 && it != 20 }
        val s = assertNotNull(AdaptiveTargetAdvisor.suggest(intake(days), trend, targets(2000.0), maintain, today))
        assertEquals(20, s.daysUsed)
        assertEquals(0.9, s.loggedDayShare, 1e-9)
        assertEquals(2400.0, s.observedTdeeKcal)
    }

    @Test
    fun manualTargetsStillGetAnOptionalSuggestion() {
        val current = targets(2250.0, manual = true)
        val s = assertNotNull(AdaptiveTargetAdvisor.suggest(fullWindow, trend, current, maintain, today))
        assertTrue(s.targetsAreManual)
        assertEquals(2250.0, current.energyKcal) // never applied
    }

    @Test
    fun todaysPartialLogIsIgnored() {
        val withToday = fullWindow + LoggedEnergyDay(today, 300.0)
        assertEquals(
            AdaptiveTargetAdvisor.suggest(fullWindow, trend, targets(2250.0), maintain, today),
            AdaptiveTargetAdvisor.suggest(withToday, trend, targets(2250.0), maintain, today),
        )
    }

    @Test
    fun needsWeighInsAcrossTheWindow() {
        assertNull(AdaptiveTargetAdvisor.suggest(fullWindow, emptyList(), targets(2000.0), maintain, today))
        val lastWeekOnly = trend.takeLast(7)
        assertNull(AdaptiveTargetAdvisor.suggest(fullWindow, lastWeekOnly, targets(2000.0), maintain, today))
    }

    @Test
    fun implausibleMaintenanceSuggestsIncompleteLogsAndIsSkipped() {
        val flat = trend.map { it.copy(scaleKg = 80.0, trendKg = 80.0) }
        assertNull(AdaptiveTargetAdvisor.suggest(intake(0..27, kcal = 600.0), flat, targets(2000.0), maintain, today))
    }
}
