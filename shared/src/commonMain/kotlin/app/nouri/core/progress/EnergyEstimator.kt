package app.nouri.core.progress

import kotlinx.datetime.LocalDate

/** Which resting-energy equation produced an [EnergyEstimate]. */
enum class EnergyMethod(val label: String) {
    MIFFLIN_ST_JEOR("Mifflin–St Jeor"),
    KATCH_MCARDLE("Katch–McArdle"),
}

/**
 * Estimated resting (BMR) and total daily energy expenditure. Values are
 * unrounded so callers can round for display; equations like these are
 * typically within about ±10% for most adults and can be further off for
 * individuals, which the [explanation] says.
 */
data class EnergyEstimate(
    val bmrKcal: Double,
    val tdeeKcal: Double,
    val method: EnergyMethod,
    val explanation: String,
)

/** Either an estimate, or the plain-language reason one can't be made yet. */
sealed interface EnergyEstimateOutcome {
    data class Estimated(val estimate: EnergyEstimate) : EnergyEstimateOutcome
    data class Unavailable(val reason: String) : EnergyEstimateOutcome

    fun estimateOrNull(): EnergyEstimate? = (this as? Estimated)?.estimate
}

/**
 * Resting and total energy estimates from body data.
 *
 * - Mifflin–St Jeor: `10·kg + 6.25·cm − 5·age + s`, with s = +5 (male) or −161
 *   (female). For [Sex.UNSPECIFIED] the midpoint of the two (s = −78) is used:
 *   it is off by ~83 kcal for either sex, which is within the equation's own
 *   error, and avoids forcing a binary choice.
 * - Katch–McArdle (`370 + 21.6·lean kg`) when body-fat % is known; it needs no
 *   age, height or sex. Consumer body-fat scales are imprecise, so this is not
 *   necessarily more accurate — it is used because the user chose to share it.
 * - TDEE = BMR × [ActivityLevel.multiplier].
 *
 * The equations are validated for adults, so estimates are only offered from
 * age 18.
 */
object EnergyEstimator {
    const val MIN_AGE_YEARS = 18

    /** Estimate from a stored profile; age comes from [BodyProfile.birthYear] and [today]. */
    fun estimate(profile: BodyProfile, weightKg: Double?, today: LocalDate): EnergyEstimateOutcome =
        estimate(
            sex = profile.sex,
            ageYears = profile.ageOn(today),
            heightCm = profile.heightCm,
            weightKg = weightKg,
            activityLevel = profile.activityLevel,
            bodyFatPercent = profile.bodyFatPercent,
        )

    fun estimate(
        sex: Sex,
        ageYears: Int?,
        heightCm: Double?,
        weightKg: Double?,
        activityLevel: ActivityLevel,
        bodyFatPercent: Double? = null,
    ): EnergyEstimateOutcome {
        if (weightKg == null || !weightKg.isFinite() || weightKg <= 0) {
            return EnergyEstimateOutcome.Unavailable("Add a weigh-in to estimate your energy needs.")
        }
        if (ageYears != null && ageYears < MIN_AGE_YEARS) {
            return EnergyEstimateOutcome.Unavailable(
                "Energy estimates here are designed for adults aged $MIN_AGE_YEARS and over.",
            )
        }
        val activity = "multiplied by ${activityLevel.multiplier} for your activity level"
        val caveat = "This is an estimate; individual needs often differ by 10% or more."

        if (bodyFatPercent != null && bodyFatPercent > 0 && bodyFatPercent < 100) {
            val leanKg = weightKg * (1.0 - bodyFatPercent / 100.0)
            val bmr = katchMcArdle(leanKg)
            return EnergyEstimateOutcome.Estimated(
                EnergyEstimate(
                    bmrKcal = bmr,
                    tdeeKcal = bmr * activityLevel.multiplier,
                    method = EnergyMethod.KATCH_MCARDLE,
                    explanation = "Resting energy was estimated with the Katch–McArdle equation from your weight " +
                        "and body-fat percentage (about ${NumberText.kcal(bmr)}), then $activity. $caveat",
                ),
            )
        }

        val missing = buildList {
            if (heightCm == null || !heightCm.isFinite() || heightCm <= 0) add("height")
            if (ageYears == null) add("year of birth")
        }
        if (missing.isNotEmpty()) {
            return EnergyEstimateOutcome.Unavailable(
                "Add your ${missing.joinToString(" and ")} to estimate your energy needs.",
            )
        }
        val bmr = mifflinStJeor(sex, weightKg, heightCm!!, ageYears!!)
        val sexNote = if (sex == Sex.UNSPECIFIED) {
            " As sex isn't specified, the average of the male and female equations was used."
        } else {
            ""
        }
        return EnergyEstimateOutcome.Estimated(
            EnergyEstimate(
                bmrKcal = bmr,
                tdeeKcal = bmr * activityLevel.multiplier,
                method = EnergyMethod.MIFFLIN_ST_JEOR,
                explanation = "Resting energy was estimated with the Mifflin–St Jeor equation from your weight, " +
                    "height, age and sex (about ${NumberText.kcal(bmr)}), then $activity.$sexNote $caveat",
            ),
        )
    }

    /** Mifflin–St Jeor BMR in kcal/day. */
    fun mifflinStJeor(sex: Sex, weightKg: Double, heightCm: Double, ageYears: Int): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        return base + when (sex) {
            Sex.MALE -> 5.0
            Sex.FEMALE -> -161.0
            Sex.UNSPECIFIED -> (5.0 - 161.0) / 2.0
        }
    }

    /** Katch–McArdle BMR in kcal/day from lean (fat-free) mass. */
    fun katchMcArdle(leanMassKg: Double): Double = 370.0 + 21.6 * leanMassKg
}
