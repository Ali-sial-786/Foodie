package app.nouri.core.insights

import app.nouri.core.model.MacroSplit
import app.nouri.core.model.Nutrients
import app.nouri.core.progress.NutritionTargets
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntakeStatsTest {
    private val today = LocalDate(2026, 10, 20)
    private val week = DateRange.last(RangePreset.SEVEN, today) // 14th..20th
    private fun day(offset: Int) = week.start.plus(offset, DateTimeUnit.DAY)

    @Test
    fun dateRangePresetsAndCustomRanges() {
        assertEquals(LocalDate(2026, 10, 14), week.start)
        assertEquals(today, week.endInclusive)
        assertEquals(7, week.dayCount)
        assertEquals(30, DateRange.last(RangePreset.THIRTY, today).dayCount)
        assertEquals(90, DateRange.last(RangePreset.NINETY, today).dayCount)
        assertTrue(today in week)
        assertFalse(LocalDate(2026, 10, 13) in week)
        val custom = DateRange(LocalDate(2026, 2, 27), LocalDate(2026, 3, 2))
        assertEquals(4, custom.dayCount)
        assertEquals(LocalDate(2026, 3, 1), custom.dates()[2])
        assertFailsWith<IllegalArgumentException> { DateRange(today, LocalDate(2026, 10, 1)) }
    }

    private val intakes = listOf(
        DailyIntake(day(0), Nutrients(1800.0, 100.0, 200.0, 60.0, fiberG = 20.0), 5),
        DailyIntake(day(1), Nutrients.ZERO, 0), // opened the diary, logged nothing
        DailyIntake(day(3), Nutrients(2000.0, 120.0, 220.0, 70.0), 6),
        DailyIntake(day(5), Nutrients(2200.0, 90.0, 260.0, 80.0, fiberG = 30.0), 4),
        DailyIntake(LocalDate(2026, 10, 1), Nutrients(9000.0, 1.0, 1.0, 1.0), 3), // outside the range
    )

    @Test
    fun averagesUseLoggedDaysOnly() {
        val targets = NutritionTargets(2100.0, 100.0, 250.0, 70.0, manual = true, basis = "")
        val s = IntakeStats.summarize(intakes, week, targets)
        assertEquals(7, s.totalDays)
        assertEquals(3, s.loggedDays)
        val avg = assertNotNull(s.averagePerLoggedDay)
        assertEquals(2000.0, avg.energyKcal, 1e-9)
        assertEquals(310.0 / 3, avg.proteinG, 1e-9)
        assertEquals(25.0, assertNotNull(avg.fiberG), 1e-9) // over the two days fibre is known
        assertNull(avg.sodiumMg)
        assertEquals(day(0), s.lowestDay?.date)
        assertEquals(day(5), s.highestDay?.date)
        val total = Nutrients(6000.0, 310.0, 680.0, 210.0)
        assertEquals(MacroSplit.of(total).proteinPct, assertNotNull(s.macroSplit).proteinPct, 1e-9)
        assertEquals(2, s.daysWithinCalorieTarget) // 2000 and 2200 are within ±210
        assertEquals(2, s.proteinTargetHitDays) // 100 and 120 ≥ 100
    }

    @Test
    fun noTargetsAndNoLogsGiveNulls() {
        val s = IntakeStats.summarize(emptyList(), week)
        assertEquals(0, s.loggedDays)
        assertNull(s.averagePerLoggedDay)
        assertNull(s.macroSplit)
        assertNull(s.daysWithinCalorieTarget)
        assertNull(s.proteinTargetHitDays)
    }

    @Test
    fun recordsForTheSameDayAreMerged() {
        val split = listOf(
            DailyIntake(day(2), Nutrients(1000.0, 50.0, 100.0, 30.0), 2),
            DailyIntake(day(2), Nutrients(800.0, 40.0, 90.0, 20.0), 3),
        )
        val s = IntakeStats.summarize(split, week)
        assertEquals(1, s.loggedDays)
        assertEquals(1800.0, s.averagePerLoggedDay!!.energyKcal, 1e-9)
    }

    private fun item(offset: Int, name: String, kcal: Double, meal: String = "Lunch", cuisine: String? = null) =
        ConsumedItem(day(offset), name, cuisine, meal, Nutrients(kcal, 10.0, 10.0, 10.0))

    private val items = listOf(
        item(0, "Greek yogurt", 150.0, "Breakfast"),
        item(1, "greek  yogurt ", 150.0, "Breakfast"),
        item(2, "Greek yogurt", 150.0, "Breakfast"),
        item(2, "Apple", 80.0, "Snack"),
        item(3, "Apple", 80.0, "Snack"),
        item(3, "Nihari", 900.0, "Dinner", "pakistani"),
        item(4, "Chicken karahi", 700.0, "Dinner", "pakistani"),
        item(5, "Masala dosa", 400.0, "Lunch", "indian"),
        ConsumedItem(LocalDate(2026, 9, 1), "Old food", null, "Lunch", Nutrients(5000.0)),
    )

    @Test
    fun topFoodsByFrequencyGroupNamesLoosely() {
        val top = IntakeStats.topFoodsByFrequency(items, week, limit = 2)
        assertEquals(listOf("Greek yogurt", "Apple"), top.map { it.name })
        assertEquals(3, top[0].count)
        assertEquals(450.0, top[0].energyKcal)
        assertEquals(450.0 / 2610.0 * 100, top[0].energySharePct, 1e-9)
    }

    @Test
    fun topFoodsByEnergyContribution() {
        val top = IntakeStats.topFoodsByEnergy(items, week, limit = 3)
        assertEquals(listOf("Nihari", "Chicken karahi", "Greek yogurt"), top.map { it.name })
    }

    @Test
    fun topCuisinesIgnoreItemsWithoutCuisine() {
        val cuisines = IntakeStats.topCuisines(items, week)
        assertEquals(listOf("pakistani", "indian"), cuisines.map { it.cuisineId })
        assertEquals(2, cuisines[0].count)
        assertEquals(1600.0, cuisines[0].energyKcal)
    }

    @Test
    fun averageByMealTypeUsesDaysThatMealWasLogged() {
        val meals = IntakeStats.averageByMealType(items, week).associateBy { it.mealType }
        val dinner = assertNotNull(meals["Dinner"])
        assertEquals(2, dinner.daysWithMeal)
        assertEquals(800.0, dinner.averagePerDay.energyKcal, 1e-9)
        assertEquals(1600.0 / 2610.0 * 100, dinner.energySharePct, 1e-9)
        assertEquals(3, meals["Breakfast"]!!.daysWithMeal)
        assertEquals("Dinner", IntakeStats.averageByMealType(items, week).first().mealType)
    }
}
