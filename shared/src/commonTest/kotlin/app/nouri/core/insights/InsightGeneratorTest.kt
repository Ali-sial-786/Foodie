package app.nouri.core.insights

import app.nouri.core.model.Nutrients
import app.nouri.core.progress.NutritionTargets
import app.nouri.core.progress.TrendPoint
import app.nouri.core.progress.WeightUnit
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

class InsightGeneratorTest {
    private val tuesday = LocalDate(2026, 10, 20)
    private val friday = LocalDate(2026, 10, 23)
    private val targets = NutritionTargets(2000.0, 110.0, 230.0, 65.0, manual = false, basis = "")

    private fun intake(date: LocalDate, kcal: Double, proteinG: Double = 100.0) =
        DailyIntake(date, Nutrients(kcal, proteinG, 200.0, 70.0), entryCount = 4)

    private fun daysBefore(today: LocalDate, offsets: Iterable<Int>) = offsets.map { today.minus(it, DateTimeUnit.DAY) }

    private fun List<Insight>.of(kind: InsightKind) = firstOrNull { it.kind == kind }

    @Test
    fun noDataGivesASingleNotEnoughDataInsight() {
        val insights = InsightGenerator.generate(emptyList(), emptyList(), null, targets, tuesday)
        val only = insights.single()
        assertEquals(InsightKind.NOT_ENOUGH_DATA, only.kind)
        assertTrue(only.body.contains("at least 3 days"), only.body)
        assertEquals(0.0, only.numbers["loggedDays"])
    }

    @Test
    fun twoLoggedDaysIsStillNotEnough() {
        val intakes = daysBefore(tuesday, 1..2).map { intake(it, 1900.0) }
        val only = InsightGenerator.generate(intakes, emptyList(), emptyList(), targets, tuesday).single()
        assertEquals(InsightKind.NOT_ENOUGH_DATA, only.kind)
        assertTrue(only.body.contains("logged food on 2 days"), only.body)
    }

    @Test
    fun weekComparisonUsesThisWeekSoFarAndLastFullWeek() {
        val thisWeek = (19..22).map { intake(LocalDate(2026, 10, it), 1920.0, 112.0) } // Mon–Thu
        val lastWeek = (12..18).map { intake(LocalDate(2026, 10, it), 2050.0, 96.0) }
        val insight = assertNotNull(
            InsightGenerator.generate(thisWeek + lastWeek, emptyList(), null, null, friday).of(InsightKind.WEEK_COMPARISON),
        )
        assertTrue(
            insight.body.contains("Your protein intake averaged 112 g per day this week, compared with 96 g last week."),
            insight.body,
        )
        assertTrue(insight.body.contains("1,920 kcal per day this week, compared with 2,050 kcal last week"), insight.body)
        assertTrue(insight.body.contains("4 so far this week, 7 last week"), insight.body)
        assertEquals(112.0, insight.numbers["thisWeekProteinG"])
        assertEquals(2050.0, insight.numbers["lastWeekKcal"])
    }

    @Test
    fun weekComparisonNeedsFourLoggedDaysInEachWeek() {
        val thisWeek = (19..21).map { intake(LocalDate(2026, 10, it), 1920.0) }
        val lastWeek = (12..18).map { intake(LocalDate(2026, 10, it), 2050.0) }
        val insights = InsightGenerator.generate(thisWeek + lastWeek, emptyList(), null, null, friday)
        assertNull(insights.of(InsightKind.WEEK_COMPARISON))
        // Today is in progress and doesn't count towards this week.
        val withToday = thisWeek + intake(friday, 800.0) + lastWeek
        assertNull(InsightGenerator.generate(withToday, emptyList(), null, null, friday).of(InsightKind.WEEK_COMPARISON))
    }

    @Test
    fun loggingConsistencyCountsTheLastSevenDays() {
        val intakes = daysBefore(tuesday, listOf(1, 2, 4, 5, 6)).map { intake(it, 1900.0) }
        val insight = assertNotNull(
            InsightGenerator.generate(intakes, emptyList(), null, null, tuesday).of(InsightKind.LOGGING_CONSISTENCY),
        )
        assertEquals("You logged food on 5 of the last 7 days.", insight.body)
        // Once something is logged today, the window includes today.
        val withToday = intakes + intake(tuesday, 500.0)
        val updated = InsightGenerator.generate(withToday, emptyList(), null, null, tuesday).of(InsightKind.LOGGING_CONSISTENCY)
        assertEquals("You logged food on 6 of the last 7 days.", updated?.body)
    }

    @Test
    fun caloriesAreComparedWithTheTargetNeutrally() {
        val kcal = listOf(1700.0, 1850.0, 1900.0, 2100.0, 1800.0, 1750.0)
        val intakes = kcal.mapIndexed { i, k -> intake(tuesday.minus(i + 1, DateTimeUnit.DAY), k, proteinG = if (i < 3) 115.0 else 90.0) }
        val insight = assertNotNull(
            InsightGenerator.generate(intakes, emptyList(), null, targets, tuesday).of(InsightKind.CALORIES_VS_TARGET),
        )
        assertEquals(
            "On the 6 days you logged in the past week, your intake averaged 1,850 kcal, about 150 kcal below your " +
                "target of 2,000 kcal. 4 of those days were within 10% of your energy target. " +
                "Your protein intake averaged 103 g per day (target 110 g) and reached the target on 3 days.",
            insight.body,
        )
        assertNull(InsightGenerator.generate(intakes, emptyList(), null, null, tuesday).of(InsightKind.CALORIES_VS_TARGET))
    }

    /** Trend falling linearly by [changeKg] over the 30 days to [today], extended 10 days earlier. */
    private fun trend(today: LocalDate, changeKg: Double, lastScaleOffset: Double = 0.0): List<TrendPoint> =
        (-10..30).map { i ->
            val kg = 80.0 + changeKg * i / 30.0
            val date = today.minus(30 - i, DateTimeUnit.DAY)
            TrendPoint(date, if (date == today) kg + lastScaleOffset else kg, kg)
        }

    @Test
    fun intakeAndWeightAreReportedSideBySide() {
        val intakes = daysBefore(tuesday, 1..30).map { intake(it, 1850.0) }
        val insights = InsightGenerator.generate(intakes, emptyList(), trend(tuesday, -1.1), targets, tuesday)
        val paired = assertNotNull(insights.of(InsightKind.INTAKE_AND_WEIGHT))
        assertEquals(
            "Over the last 30 days your average intake was 1,850 kcal on the 30 days you logged, " +
                "and your trend weight changed by −1.1 kg.",
            paired.body,
        )
        val weight = assertNotNull(insights.of(InsightKind.WEIGHT_TREND))
        assertEquals("Your trend weight is down 1.1 kg over 30 days (about 0.3 kg per week).", weight.body)
        assertEquals(-1.1, weight.numbers["trendChangeKg"])
    }

    @Test
    fun weightInsightsRespectTheDisplayUnit() {
        val insights = InsightGenerator.generate(emptyList(), emptyList(), trend(tuesday, 1.0), null, tuesday, WeightUnit.LB)
        val weight = assertNotNull(insights.of(InsightKind.WEIGHT_TREND))
        assertTrue(weight.body.startsWith("Your trend weight is up 2.2 lb over 30 days"), weight.body)
        assertNull(insights.of(InsightKind.INTAKE_AND_WEIGHT)) // no intake logged
    }

    @Test
    fun topFoodOfTheMonth() {
        val items = listOf(3, 5, 9, 12).map {
            ConsumedItem(LocalDate(2026, 10, it), "Greek yogurt", null, "Breakfast", Nutrients(150.0))
        } + ConsumedItem(LocalDate(2026, 10, 9), "Chicken biryani", "pakistani", "Dinner", Nutrients(750.0)) +
            ConsumedItem(LocalDate(2026, 9, 30), "Last month", null, "Dinner", Nutrients(750.0))
        val insight = assertNotNull(
            InsightGenerator.generate(emptyList(), items, null, null, tuesday).of(InsightKind.TOP_FOOD),
        )
        assertEquals(
            "Your most logged food this month is Greek yogurt, logged 4 times. " +
                "It accounts for about 44% of the energy you logged this month.",
            insight.body,
        )
    }

    @Test
    fun largeNumbersUseThousandsSeparatorsAndNoFalsePrecision() {
        val intakes = daysBefore(tuesday, 1..7).map { intake(it, 2346.7, proteinG = 101.6) }
        val texts = InsightGenerator.generate(intakes, emptyList(), null, targets, tuesday).joinToString(" ") { it.body }
        assertTrue(texts.contains("2,350 kcal"), texts)
        assertFalse(Regex("\\d\\.\\d+ kcal").containsMatchIn(texts), texts)
    }

    @Test
    fun noGeneratedTextUsesCausalLanguage() {
        val causal = Regex(
            "\\b(because|cause[sd]?|causing|led to|leads? to|due to|results? (of|in)|resulted|thanks to|drove|driven by|so that)\\b",
            RegexOption.IGNORE_CASE,
        )
        val richIntake = daysBefore(friday, 1..40).map { intake(it, 1800.0 + (it.day % 5) * 60, 90.0 + it.day % 7) }
        val items = (1..20).map {
            ConsumedItem(LocalDate(2026, 10, it), if (it % 2 == 0) "Dal" else "Roti", "pakistani", "Lunch", Nutrients(300.0))
        }
        val scenarios = listOf(
            InsightGenerator.generate(emptyList(), emptyList(), null, null, friday),
            InsightGenerator.generate(richIntake.take(2), emptyList(), null, targets, friday),
            InsightGenerator.generate(richIntake, items, trend(friday, -1.4, lastScaleOffset = 1.2), targets, friday),
            InsightGenerator.generate(richIntake, items, trend(friday, 0.8, lastScaleOffset = -0.9), targets, friday, WeightUnit.LB),
            InsightGenerator.generate(richIntake, items, trend(friday, 0.0), null, friday),
        )
        val all = scenarios.flatten()
        assertTrue(all.map { it.kind }.toSet().size >= 6, "expected most rules to fire: ${all.map { it.kind }}")
        for (insight in all) {
            val text = insight.title + " " + insight.body
            assertNull(causal.find(text), "causal wording in ${insight.kind}: $text")
        }
    }
}
