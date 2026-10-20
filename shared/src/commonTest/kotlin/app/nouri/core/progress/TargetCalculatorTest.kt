package app.nouri.core.progress

import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetCalculatorTest {
    private val today = LocalDate(2026, 10, 20)

    private fun profile(
        sex: Sex = Sex.MALE,
        age: Int = 30,
        heightCm: Double = 180.0,
        activity: ActivityLevel = ActivityLevel.MODERATE,
        goal: WeightGoal = WeightGoal.MAINTAIN,
        rate: Double = 0.5,
    ) = BodyProfile(sex, today.year - age, heightCm, activity, goal, weeklyRateKg = rate)

    private fun suggest(p: BodyProfile, weightKg: Double): NutritionTargets =
        assertNotNull(TargetCalculator.suggest(p, weightKg, today))

    private fun assertMacrosAddUp(t: NutritionTargets) {
        assertTrue(abs(t.macroEnergyKcal - t.energyKcal) <= 15.0, "macros ${t.macroEnergyKcal} vs ${t.energyKcal}")
    }

    @Test
    fun maintainUsesTdeeAndDefaultMacros() {
        // BMR 1780 × 1.55 = 2759 → 2760.
        val t = suggest(profile(), 80.0)
        assertEquals(2760.0, t.energyKcal)
        assertEquals(96.0, t.proteinG) // 1.2 g/kg
        assertEquals(77.0, t.fatG) // 25% of energy
        assertEquals(422.0, t.carbsG, 1.0)
        assertEquals(39.0, t.fiberG) // 14 g per 1000 kcal
        assertNull(t.waterMl)
        assertFalse(t.manual)
        assertTrue(t.basis.contains("2,760 kcal"), t.basis)
        assertMacrosAddUp(t)
    }

    @Test
    fun loseUsesRateBasedDeficit() {
        val t = suggest(profile(goal = WeightGoal.LOSE), 80.0)
        assertEquals(2210.0, t.energyKcal) // 2759 − 550
        assertEquals(128.0, t.proteinG) // 1.6 g/kg
        assertTrue(t.basis.contains("550 kcal below"), t.basis)
        assertFalse(t.basis.contains("limited"), t.basis)
        assertMacrosAddUp(t)
    }

    @Test
    fun deficitIsCappedAtQuarterOfTdeeAndSaysSo() {
        // 1 kg/week would need 1100 kcal/day; 25% of 2759 is 689.75.
        val t = suggest(profile(goal = WeightGoal.LOSE, rate = 1.0), 80.0)
        assertEquals(2070.0, t.energyKcal)
        assertTrue(t.basis.contains("limited to 25%"), t.basis)
        assertTrue(t.basis.contains("roughly 0.63 kg per week"), t.basis)
    }

    @Test
    fun deficitNeverGoesBelowBmr() {
        // Sedentary: 25% under TDEE (1602) would be below BMR (1780).
        val t = suggest(profile(goal = WeightGoal.LOSE, activity = ActivityLevel.SEDENTARY), 80.0)
        assertEquals(1780.0, t.energyKcal)
        assertTrue(t.basis.contains("resting energy"), t.basis)
    }

    @Test
    fun absoluteFloorIs1200ForWomenAndUnspecified() {
        // BMR 1157.75, TDEE 1389.3; a capped deficit would give ~1042.
        val p = profile(Sex.FEMALE, heightCm = 155.0, activity = ActivityLevel.SEDENTARY, goal = WeightGoal.LOSE)
        val t = suggest(p, 50.0)
        assertEquals(1200.0, t.energyKcal)
        assertTrue(t.basis.contains("1,200 kcal") && t.basis.contains("common minimum"), t.basis)
        assertEquals(1200.0, TargetCalculator.minimumEnergyKcal(Sex.UNSPECIFIED))
        // Unspecified sex: BMR (1240.75) is above 1200, so it is the floor.
        assertTrue(suggest(p.copy(sex = Sex.UNSPECIFIED), 50.0).energyKcal >= 1200.0)
    }

    @Test
    fun absoluteFloorIs1500ForMen() {
        // BMR 1336.25, TDEE 1603.5.
        val t = suggest(profile(age = 60, heightCm = 165.0, activity = ActivityLevel.SEDENTARY, goal = WeightGoal.LOSE), 60.0)
        assertEquals(1500.0, t.energyKcal)
        assertTrue(t.basis.contains("1,500 kcal"), t.basis)
    }

    @Test
    fun maintenanceBelowTheFloorIsNotRaisedAboveMaintenance() {
        // BMR 876.5, TDEE 1051.8 — already under 1200, so no deficit and no surplus.
        val p = profile(Sex.FEMALE, age = 70, heightCm = 150.0, activity = ActivityLevel.SEDENTARY, goal = WeightGoal.LOSE)
        val t = suggest(p, 45.0)
        assertEquals(1050.0, t.energyKcal)
        assertTrue(t.basis.contains("set at maintenance"), t.basis)
    }

    @Test
    fun gainSurplusIsCappedAt500() {
        val capped = suggest(profile(goal = WeightGoal.GAIN, rate = 0.5), 80.0)
        assertEquals(3260.0, capped.energyKcal) // 2759 + 500 (550 wanted)
        assertTrue(capped.basis.contains("limited to 500 kcal"), capped.basis)
        assertEquals(96.0, capped.proteinG)

        val gentle = suggest(profile(goal = WeightGoal.GAIN, rate = 0.25), 80.0)
        assertEquals(3030.0, gentle.energyKcal) // 2759 + 275
    }

    @Test
    fun muscleGainAdds250AndHigherProtein() {
        val t = suggest(profile(goal = WeightGoal.MUSCLE_GAIN), 80.0)
        assertEquals(3010.0, t.energyKcal)
        assertEquals(128.0, t.proteinG)
        assertMacrosAddUp(t)
    }

    @Test
    fun proteinIsCappedAt35PercentOfEnergy() {
        // 150 kg, energy floored at BMR 2317.5 → 2320; 1.6 g/kg (240 g) would be 41% of energy.
        val t = suggest(profile(age = 50, heightCm = 170.0, activity = ActivityLevel.SEDENTARY, goal = WeightGoal.LOSE), 150.0)
        assertEquals(2320.0, t.energyKcal)
        assertEquals(203.0, t.proteinG)
        assertTrue(t.proteinG <= 2.2 * 150)
        assertEquals(90.0, t.fatG) // 0.6 g/kg beats 25% of energy here
        assertTrue(t.carbsG >= 0)
        assertTrue(t.basis.contains("limited to 35%"), t.basis)
        assertMacrosAddUp(t)
    }

    @Test
    fun suggestReturnsNullWhenProfileIsIncomplete() {
        assertNull(TargetCalculator.suggest(BodyProfile(goal = WeightGoal.LOSE), 80.0, today))
    }

    @Test
    fun manualTargetsAreStoredUntouched() {
        val result = TargetCalculator.manual(1875.5, 121.3, 201.0, 59.7, fiberG = 31.0, waterMl = 2200.0)
        val t = result.targets
        assertEquals(1875.5, t.energyKcal)
        assertEquals(121.3, t.proteinG)
        assertEquals(201.0, t.carbsG)
        assertEquals(59.7, t.fatG)
        assertEquals(31.0, t.fiberG)
        assertEquals(2200.0, t.waterMl)
        assertTrue(t.manual)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun manualTargetsWithMismatchedMacrosGetAGentleNoteOnly() {
        val result = TargetCalculator.manual(2000.0, 200.0, 300.0, 100.0) // macros = 2900 kcal
        assertEquals(2000.0, result.targets.energyKcal)
        assertEquals(1, result.warnings.size)
        val note = result.warnings.single()
        assertTrue(note.contains("2,900 kcal") && note.contains("2,000 kcal"), note)
        for (word in listOf("should", "must", "error", "invalid")) assertFalse(note.lowercase().contains(word), note)
        // Within 15% → no note; calorie-only targets → no note.
        assertTrue(TargetCalculator.manual(2000.0, 150.0, 220.0, 70.0).warnings.isEmpty()) // 2110 kcal
        assertTrue(TargetCalculator.manual(1800.0, 0.0, 0.0, 0.0).warnings.isEmpty())
    }
}
