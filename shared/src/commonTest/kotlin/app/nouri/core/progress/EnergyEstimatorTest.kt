package app.nouri.core.progress

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyEstimatorTest {
    private val today = LocalDate(2026, 10, 20)

    private fun estimated(outcome: EnergyEstimateOutcome): EnergyEstimate =
        assertIs<EnergyEstimateOutcome.Estimated>(outcome).estimate

    @Test
    fun mifflinStJeorKnownValues() {
        assertEquals(1780.0, EnergyEstimator.mifflinStJeor(Sex.MALE, 80.0, 180.0, 30), 1e-9)
        assertEquals(1320.25, EnergyEstimator.mifflinStJeor(Sex.FEMALE, 60.0, 165.0, 30), 1e-9)
    }

    @Test
    fun unspecifiedSexUsesTheMidpointOfBothEquations() {
        val male = EnergyEstimator.mifflinStJeor(Sex.MALE, 70.0, 172.0, 40)
        val female = EnergyEstimator.mifflinStJeor(Sex.FEMALE, 70.0, 172.0, 40)
        assertEquals((male + female) / 2, EnergyEstimator.mifflinStJeor(Sex.UNSPECIFIED, 70.0, 172.0, 40), 1e-9)
        val e = estimated(EnergyEstimator.estimate(Sex.UNSPECIFIED, 40, 172.0, 70.0, ActivityLevel.LIGHT))
        assertTrue(e.explanation.contains("average of the male and female equations"))
    }

    @Test
    fun tdeeAppliesTheActivityMultiplier() {
        val e = estimated(EnergyEstimator.estimate(Sex.MALE, 30, 180.0, 80.0, ActivityLevel.MODERATE))
        assertEquals(EnergyMethod.MIFFLIN_ST_JEOR, e.method)
        assertEquals(1780.0, e.bmrKcal, 1e-9)
        assertEquals(1780.0 * 1.55, e.tdeeKcal, 1e-9)
        assertTrue(e.explanation.contains("Mifflin–St Jeor"))
        assertTrue(e.explanation.contains("estimate"))
    }

    @Test
    fun katchMcArdleIsUsedWhenBodyFatIsKnown() {
        // 80 kg at 20% body fat → 64 kg lean mass → 370 + 21.6 × 64.
        val e = estimated(EnergyEstimator.estimate(Sex.UNSPECIFIED, null, null, 80.0, ActivityLevel.SEDENTARY, 20.0))
        assertEquals(EnergyMethod.KATCH_MCARDLE, e.method)
        assertEquals(1752.4, e.bmrKcal, 1e-9)
        assertEquals(1752.4 * 1.2, e.tdeeKcal, 1e-9)
    }

    @Test
    fun profileAgeComesFromBirthYear() {
        val profile = BodyProfile(sex = Sex.FEMALE, birthYear = 1996, heightCm = 165.0, activityLevel = ActivityLevel.SEDENTARY)
        assertEquals(30, profile.ageOn(today))
        val e = estimated(EnergyEstimator.estimate(profile, 60.0, today))
        assertEquals(1320.25, e.bmrKcal, 1e-9)
    }

    @Test
    fun missingInputsGiveAReasonInsteadOfAGuess() {
        val noHeight = EnergyEstimator.estimate(Sex.MALE, 30, null, 80.0, ActivityLevel.LIGHT)
        assertTrue(assertIs<EnergyEstimateOutcome.Unavailable>(noHeight).reason.contains("height"))
        assertNull(noHeight.estimateOrNull())

        val noAgeOrHeight = EnergyEstimator.estimate(BodyProfile(), 80.0, today)
        val reason = assertIs<EnergyEstimateOutcome.Unavailable>(noAgeOrHeight).reason
        assertTrue(reason.contains("height") && reason.contains("year of birth"), reason)

        val noWeight = EnergyEstimator.estimate(Sex.MALE, 30, 180.0, null, ActivityLevel.LIGHT)
        assertTrue(assertIs<EnergyEstimateOutcome.Unavailable>(noWeight).reason.contains("weigh-in"))
    }

    @Test
    fun estimatesAreForAdultsOnly() {
        val teen = EnergyEstimator.estimate(Sex.FEMALE, 16, 160.0, 55.0, ActivityLevel.LIGHT)
        assertIs<EnergyEstimateOutcome.Unavailable>(teen)
        assertNotNull(EnergyEstimator.estimate(Sex.FEMALE, 18, 160.0, 55.0, ActivityLevel.LIGHT).estimateOrNull())
    }
}
